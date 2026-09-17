const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const ts = require("typescript");
const { PDFDocument, degrees } = require("pdf-lib");

const filename = path.resolve(__dirname, "../lib/document-editing.ts");
const compiled = ts.transpileModule(fs.readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const loaded = new Module(filename, module);
loaded.filename = filename;
loaded.paths = module.paths;
loaded._compile(compiled, filename);
const { readMarks, createPdfCopy, SOURCE_ATTACHMENT, MARKS_ATTACHMENT } = loaded.exports;

(async () => {
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  const { createCanvas } = require("@napi-rs/canvas");
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
