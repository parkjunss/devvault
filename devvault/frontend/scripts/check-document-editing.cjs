const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const ts = require("typescript");
const { PDFDocument, degrees, rgb } = require("pdf-lib");

const filename = path.resolve(__dirname, "../lib/document-editing.ts");
const compiled = ts.transpileModule(fs.readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const loaded = new Module(filename, module);
loaded.filename = filename;
loaded.paths = module.paths;
loaded._compile(compiled, filename);
const { changeMarkHistory, readMarks, drawMarks, createPdfCopy, SOURCE_ATTACHMENT, MARKS_ATTACHMENT } = loaded.exports;

(async () => {
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  const { createCanvas } = require("@napi-rs/canvas");
  const ink = createCanvas(400, 600);
  const strokes = [
    { page: 1, tool: "pen", width: .02, color: "#ff0000", points: [{ x: .1, y: .2 }, { x: .9, y: .2 }] },
    { page: 1, tool: "erase", width: .05, color: "#000000", points: [{ x: .5, y: .1 }, { x: .5, y: .3 }] }
  ];
  const empty = [];
  let history = { past: [], present: empty, future: [] };
  assert.equal(changeMarkHistory(history, { type: "undo" }), history);
  const pen = [strokes[0]];
  history = changeMarkHistory(history, { type: "edit", marks: pen });
  history = changeMarkHistory(history, { type: "edit", marks: strokes });
  history = changeMarkHistory(history, { type: "undo" });
  assert.equal(history.present, pen, "undo eraser restores the exact pen snapshot");
  history = changeMarkHistory(history, { type: "undo" });
  assert.equal(history.present, empty, "undo returns to the saved baseline by reference");
  history = changeMarkHistory(history, { type: "redo" });
  history = changeMarkHistory(history, { type: "redo" });
  assert.equal(history.present, strokes);
  history = changeMarkHistory(history, { type: "undo" });
  const highlighted = [...pen, { ...strokes[0], page: 2, tool: "highlight" }];
  history = changeMarkHistory(history, { type: "edit", marks: highlighted });
  assert.equal(history.future.length, 0, "a new edit invalidates redo");
  assert.equal(changeMarkHistory(history, { type: "redo" }), history);
  for (let index = 0; index < 110; index++) history = changeMarkHistory(history, { type: "edit", marks: [...highlighted] });
  assert.equal(history.past.length, 100);
  history = changeMarkHistory(history, { type: "reset", marks: pen });
  assert.deepEqual(history, { past: [], present: pen, future: [] });
  assert.deepEqual(readMarks(JSON.stringify(strokes), 1), strokes);
  drawMarks(ink.getContext("2d"), strokes, 400, 600);
  assert.equal(ink.getContext("2d").getImageData(200, 120, 1, 1).data[3], 0);
  assert.equal(ink.getContext("2d").getImageData(100, 120, 1, 1).data[3], 255);
  const base = createCanvas(400, 600), baseContext = base.getContext("2d");
  baseContext.fillStyle = "#00ff00";
  baseContext.fillRect(0, 0, 400, 600);
  baseContext.drawImage(ink, 0, 0);
  assert.deepEqual([...baseContext.getImageData(200, 120, 1, 1).data], [0, 255, 0, 255], "erased ink reveals the unchanged original");
  const coloredPdf = await PDFDocument.create();
  coloredPdf.addPage([400, 600]).drawRectangle({ x: 0, y: 0, width: 400, height: 600, color: rgb(0, 1, 0) });
  const erasedPdf = await createPdfCopy(await coloredPdf.save(), strokes, async (marks, width, height) => {
    const overlay = createCanvas(width, height);
    drawMarks(overlay.getContext("2d"), marks, width, height);
    return overlay.toBuffer("image/png");
  });
  const erasedTask = pdfjs.getDocument({ data: erasedPdf });
  try {
    const savedDocument = await erasedTask.promise;
    const savedPage = await savedDocument.getPage(1);
    const rendered = createCanvas(400, 600);
    await savedPage.render({ canvas: rendered, viewport: savedPage.getViewport({ scale: 1 }) }).promise;
    assert.deepEqual([...rendered.getContext("2d").getImageData(200, 120, 1, 1).data], [0, 255, 0, 255], "exported PDF must reveal the original under erased ink");
  } finally { await erasedTask.destroy(); }
  const input = await PDFDocument.create();
  for (const rotation of [0, 90, 180, 270]) {
    const page = input.addPage([500, 700]);
    page.setCropBox(30, 40, 400, 600);
    page.setRotation(degrees(rotation));
    page.drawText("Preserved source text", { x: 70, y: 350, size: 16 });
  }
  const source = await input.save();
  const before = Buffer.from(source);
  const marks = [1, 2, 3, 4].map(page => ({ page, tool: "pen", width: 0.01, color: "#ff0000", points: [{ x: .1, y: .1 }, { x: .2, y: .1 }] }));
  assert.deepEqual(readMarks(JSON.stringify(marks), 4), marks);
  for (const invalid of [{ ...marks[0], page: 5 }, { ...marks[0], width: -1 }, { ...marks[0], points: [{ x: 2, y: 0 }] }, { ...marks[0], color: "bad" }]) {
    assert.throws(() => readMarks(JSON.stringify([invalid]), 4));
  }
  const edited = await createPdfCopy(source, marks, async (_, width, height) => {
    const canvas = createCanvas(Math.round(width), Math.round(height));
    const context = canvas.getContext("2d");
    context.fillStyle = "#ff0000";
    context.fillRect(width * .1, height * .1, width * .1, height * .1);
    return canvas.toBuffer("image/png");
  });
  assert.deepEqual(Buffer.from(source), before, "original bytes must remain unchanged");
  const task = pdfjs.getDocument({ data: edited, useSystemFonts: true });
  const document = await task.promise;
  try {
    assert.equal(document.numPages, 4);
    const attachments = await document.getAttachments();
    for (const [id, attachment] of attachments) {
      const content = await document.getAttachmentContent(id);
      if (attachment.filename === SOURCE_ATTACHMENT) assert.deepEqual(Buffer.from(content), before);
      if (attachment.filename === MARKS_ATTACHMENT) assert.deepEqual(readMarks(new TextDecoder().decode(content), 4), marks);
    }
    assert.equal(attachments.size, 2);
    for (let pageNumber = 1; pageNumber <= 4; pageNumber++) {
      const page = await document.getPage(pageNumber);
      assert.match((await page.getTextContent()).items.map(item => item.str).join(" "), /Preserved source text/);
      const viewport = page.getViewport({ scale: 1 });
      const canvas = createCanvas(viewport.width, viewport.height);
      await page.render({ canvas, viewport }).promise;
      const pixel = canvas.getContext("2d").getImageData(Math.floor(viewport.width * .15), Math.floor(viewport.height * .15), 1, 1).data;
      assert.ok(pixel[0] > 230 && pixel[1] < 30 && pixel[2] < 30, `overlay position must survive rotation on page ${pageNumber}`);
    }
  } finally { await task.destroy(); }
  console.log("PASS: annotation validation, original preservation, editable attachments, text preservation, and cropped PDF rotations 0/90/180/270");
})().catch(error => { console.error(error); process.exitCode = 1; });
