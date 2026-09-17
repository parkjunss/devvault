// Start the frontend on 3107. API calls are intercepted; no backend or user files are touched.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.env.DEVVAULT_PLAYWRIGHT_MODULE || "playwright");
const { PDFDocument, degrees } = require("pdf-lib");

(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.DEVVAULT_BROWSER_CHANNEL || "chrome" });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("dialog", dialog => dialog.accept());
    const pdf = await PDFDocument.create();
    pdf.addPage([400, 600]).drawText("Original preserved", { x: 40, y: 500 });
    pdf.addPage([400, 600]).setRotation(degrees(90));
    const original = Buffer.from(await pdf.save());
    const image = Buffer.from(await page.evaluate(() => { const c = document.createElement("canvas"); c.width = 400; c.height = 300; const ctx = c.getContext("2d"); ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, 400, 300); return c.toDataURL().split(",")[1]; }), "base64");
    const files = new Map([[1, { name: "sample.pdf", type: "application/pdf", bytes: original }], [2, { name: "sample.png", type: "image/png", bytes: image }]]);
    let lastId = 2;
    const metadata = (id, value) => ({ id, originalName: value.name, contentType: value.type, size: value.bytes.length, folderId: 7, createdAt: new Date().toISOString() });
    await context.route("**/api/**", async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      if (pathname === "/api/files" && request.method() === "POST") {
        const form = await new Request("http://localhost/upload", { method: "POST", headers: { "content-type": request.headers()["content-type"] }, body: request.postDataBuffer() }).formData();
        assert.equal(form.get("folderId"), "7");
        const file = form.get("file"), id = ++lastId;
        const value = { name: file.name, type: file.type, bytes: Buffer.from(await file.arrayBuffer()) };
        files.set(id, value);
        return route.fulfill({ status: 201, json: metadata(id, value) });
      }
      const match = pathname.match(/^\/api\/files\/(\d+)(\/download)?$/);
      if (match && files.has(Number(match[1]))) {
        const id = Number(match[1]), value = files.get(id);
        return route.fulfill(match[2] ? { status: 200, contentType: value.type, body: value.bytes } : { json: metadata(id, value) });
      }
      return route.fulfill({ status: 404, json: { message: "Unmocked request" } });
    });
    async function ready(id) {
      await page.goto(`http://localhost:3107/files/${id}/edit`);
      await page.getByRole("button", { name: "펜", exact: true }).waitFor();
      await page.waitForFunction(() => { const c = document.querySelector(".editorPaper canvas"); return c && c.width > 1 && !document.querySelector(".editorToolbar").disabled; });
      assert.equal(await page.locator(".editorError").count(), 0, (await page.locator(".editorError").allTextContents()).join(" "));
    }
    async function draw() {
      const box = await page.locator(".editorInk").boundingBox();
      await page.mouse.move(box.x + box.width * .2, box.y + box.height * .2);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width * .5, box.y + box.height * .2, { steps: 6 });
      await page.mouse.up();
    }
    await ready(1);
    await draw();
    await page.getByRole("button", { name: "다음 페이지" }).click();
    await page.waitForFunction(() => !document.querySelector(".editorToolbar").disabled);
    await page.getByRole("button", { name: "형광펜", exact: true }).click();
    await draw();
    await page.getByRole("button", { name: "텍스트", exact: true }).click();
    await page.getByRole("textbox", { name: "추가할 텍스트" }).fill("한글 메모");
    await page.locator(".editorInk").click({ position: { x: 80, y: 120 } });
    if (process.env.DEVVAULT_SCREENSHOT_DIR) {
      fs.mkdirSync(process.env.DEVVAULT_SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: path.join(process.env.DEVVAULT_SCREENSHOT_DIR, "pdf-editor.png"), fullPage: true });
    }
    await page.getByRole("button", { name: "편집본 저장", exact: true }).click();
    await page.getByText("저장 완료:", { exact: false }).waitFor();
    assert.equal(lastId, 3);
    assert.deepEqual(files.get(1).bytes, original);
    await ready(3);
    assert.equal(await page.locator(".editorMarkList > div").count(), 1);
    await page.getByRole("button", { name: "다음 페이지" }).click();
    await page.waitForFunction(() => !document.querySelector(".editorToolbar").disabled);
    assert.equal(await page.locator(".editorMarkList > div").count(), 2);
    await page.locator(".editorMarkList > div").first().getByRole("button", { name: "삭제", exact: true }).click();
    await page.getByRole("button", { name: "편집본 저장", exact: true }).click();
    await page.getByText("저장 완료:", { exact: false }).waitFor();
    await ready(4);
    await page.getByRole("button", { name: "다음 페이지" }).click();
    await page.waitForFunction(() => !document.querySelector(".editorToolbar").disabled);
    assert.equal(await page.locator(".editorMarkList > div").count(), 1);
    await ready(2);
    await draw();
    await page.getByRole("button", { name: "오른쪽 90° 회전" }).click();
    await page.waitForFunction(() => document.querySelector(".editorPaper canvas").width === 300 && !document.querySelector(".editorToolbar").disabled);
    await page.getByLabel("가로(px)").fill("200");
    await page.getByLabel("세로(px)").fill("100");
    await page.getByRole("button", { name: "크기 적용" }).click();
    await page.waitForFunction(() => document.querySelector(".editorPaper canvas").width === 200 && !document.querySelector(".editorToolbar").disabled);
    await page.getByRole("button", { name: "영역 드래그로 자르기" }).click();
    const box = await page.locator(".editorInk").boundingBox();
    await page.mouse.move(box.x + 20, box.y + 10); await page.mouse.down();
    await page.mouse.move(box.x + 180, box.y + 90); await page.mouse.up();
    await page.waitForFunction(() => document.querySelector(".editorPaper canvas").width === 160 && !document.querySelector(".editorToolbar").disabled);
    await page.getByRole("button", { name: "편집본 저장", exact: true }).click();
    await page.getByText("저장 완료:", { exact: false }).waitFor();
    assert.equal(files.get(5).type, "image/png");
    assert.deepEqual(files.get(2).bytes, image);
    await ready(5);
    assert.equal(await page.locator(".editorPaper canvas").first().evaluate(canvas => canvas.width), 160);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, "mobile controls must fit the screen");
    if (process.env.DEVVAULT_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.DEVVAULT_SCREENSHOT_DIR, "image-editor-mobile.png"), fullPage: true });
    assert.deepEqual(errors, []);
    console.log("PASS: PDF pen/highlight/Korean text, save/reopen/delete/resave; image rotate/resize/crop/save; originals unchanged; no browser errors");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
