// Start the frontend on 3107. API calls are intercepted; no backend or user files are touched.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.env.DEVVAULT_PLAYWRIGHT_MODULE || "playwright");
const { PDFDocument, degrees } = require("pdf-lib");

(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.DEVVAULT_BROWSER_CHANNEL || "chrome" });
  try {
    const context = await browser.newContext({ viewport: { width: 820, height: 1180 }, hasTouch: true });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("dialog", dialog => dialog.accept());
    const pdf = await PDFDocument.create();
    pdf.addPage([400, 600]).drawText("Original preserved", { x: 40, y: 500 });
    pdf.addPage([400, 600]).setRotation(degrees(90));
    for (let index = 3; index <= 20; index++) pdf.addPage([400, 600]).drawText(`Page ${index}`, { x: 40, y: 500 });
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
    const sheet = number => page.locator(`.editorPaper[data-page="${number}"]`);
    const ink = number => sheet(number).locator(".editorInk");
    async function ready(id) {
      await page.goto(`http://localhost:3107/files/${id}/edit`);
      await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]')?.dataset.rendered === "true");
      assert.equal(await page.getByRole("dialog").isVisible(), false);
      assert.equal(await page.locator(".editorError").count(), 0);
      const viewport = await page.locator(".editorViewport").boundingBox();
      assert.equal(viewport.x, 0); assert.equal(viewport.y, 0);
      assert.equal(viewport.width, page.viewportSize().width); assert.equal(viewport.height, page.viewportSize().height);
      assert.equal(await ink(1).getAttribute("data-tool"), "read");
    }
    async function scrollTo(number) {
      await sheet(number).evaluate(element => element.scrollIntoView({ block: "start" }));
      await page.waitForFunction(n => document.querySelector(`.editorPaper[data-page="${n}"]`)?.dataset.rendered === "true", number);
    }
    async function menu() {
      if (!await page.getByRole("dialog").isVisible()) await page.getByRole("button", { name: "편집 메뉴 열기", exact: true }).click();
      await page.getByRole("dialog").waitFor();
    }
    async function choose(name) {
      await menu();
      await page.getByRole("button", { name, exact: true }).click();
      if (name !== "텍스트") await page.getByRole("dialog").waitFor({ state: "hidden" });
    }
    async function stroke(number, x1=.2, y1=.2, x2=.5, y2=.2) {
      const box = await ink(number).boundingBox();
      await page.mouse.move(box.x + box.width*x1, box.y + box.height*y1); await page.mouse.down();
      await page.mouse.move(box.x + box.width*x2, box.y + box.height*y2, { steps: 8 }); await page.mouse.up();
    }
    async function alpha(number,x,y) {
      return ink(number).evaluate((canvas,p) => canvas.getContext("2d").getImageData(Math.floor(canvas.width*p.x),Math.floor(canvas.height*p.y),1,1).data[3], {x,y});
    }
    async function save() {
      await menu(); await page.getByRole("button", { name: "편집본 저장", exact: true }).click();
      await page.getByRole("dialog").locator(".editorSuccess").waitFor();
    }
    await ready(1);
    assert.equal(await page.locator(".editorPaper").count(), 20);
    assert.equal(await page.getByRole("button", { name: "다음 페이지" }).count(), 0);
    assert.ok((await sheet(1).boundingBox()).width >= 800);
    // Use native touch events to verify finger scrolling does not make a mark in reading mode.
    const touch = await context.newCDPSession(page);
    await touch.send("Input.dispatchTouchEvent", {type:"touchStart",touchPoints:[{x:600,y:850}]});
    for (let y=800;y>=350;y-=50) await touch.send("Input.dispatchTouchEvent", {type:"touchMove",touchPoints:[{x:600,y}]});
    await page.waitForTimeout(200); // Stop the gesture before lifting so it does not fling past the drawing target.
    await touch.send("Input.dispatchTouchEvent", {type:"touchEnd",touchPoints:[]});
    await page.waitForFunction(() => document.querySelector(".editorViewport").scrollTop > 100);
    await scrollTo(1);
    await menu(); assert.equal(await page.getByRole("button",{name:"편집본 저장",exact:true}).isDisabled(),true);
    await page.keyboard.press("Escape");
    await page.getByRole("dialog").waitFor({state:"hidden"});
    assert.equal(await page.getByRole("button",{name:"편집 메뉴 열기",exact:true}).evaluate(e=>e===document.activeElement),true);
    await choose("펜"); await stroke(1);
    await page.waitForFunction(() => { const c=document.querySelector('.editorPaper[data-page="1"] .editorInk'); return c.getContext('2d').getImageData(Math.floor(c.width*.35),Math.floor(c.height*.2),1,1).data[3]>0; }, null, {timeout:5000}).catch(async error => { console.error(await page.evaluate(() => ({scroll:document.querySelector('.editorViewport').scrollTop, marks:document.querySelector('.editorMarkList').textContent, errors:[...document.querySelectorAll('.editorError')].map(e=>e.textContent), first:document.querySelector('.editorPaper').getBoundingClientRect().toJSON()}))); throw error; });
    await choose("지우개"); await stroke(1,.35,.15,.35,.25);
    assert.equal(await alpha(1,.35,.2),0,"eraser removes only the crossed ink");
    assert.ok(await alpha(1,.24,.2)>0,"uncrossed stroke remains");
    await menu(); await page.getByRole("button", {name:"현재 페이지 마지막 작업 취소"}).click();
    await page.getByRole("button", {name:"편집 메뉴 닫기"}).click();
    assert.ok(await alpha(1,.35,.2)>0,"undo restores erased ink");
    await stroke(1,.35,.15,.35,.25);
    await choose("읽기 / 스크롤");
    await scrollTo(10);
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"] canvas').width===1);
    assert.ok(await page.locator('.editorPaper[data-rendered="true"]').count()<6,"off-screen pages release their canvases");
    await scrollTo(1); assert.equal(await alpha(1,.35,.2),0); assert.ok(await alpha(1,.24,.2)>0);
    await scrollTo(2); await choose("형광펜"); await stroke(2);
    await choose("텍스트"); await page.getByRole("textbox",{name:"추가할 텍스트"}).fill("한글 메모");
    await page.getByRole("button",{name:"문서에 텍스트 배치"}).click();
    await ink(2).click({position:{x:80,y:180}});
    await save(); assert.equal(lastId,3); assert.deepEqual(files.get(1).bytes,original);
    await ready(3); assert.equal(await alpha(1,.35,.2),0); assert.ok(await alpha(1,.24,.2)>0);
    await scrollTo(2); await menu(); await page.locator(".editorMarkList summary").click();
    assert.equal(await page.locator(".editorMarkList > div").count(),2);
    await page.locator(".editorMarkList > div").first().getByRole("button",{name:"삭제",exact:true}).click();
    await save(); await ready(4); await scrollTo(2);
    await menu(); assert.equal(await page.locator(".editorMarkList > div").count(),1);
    await page.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    await scrollTo(1);
    if (process.env.DEVVAULT_SCREENSHOT_DIR) {
      fs.mkdirSync(process.env.DEVVAULT_SCREENSHOT_DIR,{recursive:true});
      await page.screenshot({path:path.join(process.env.DEVVAULT_SCREENSHOT_DIR,"pdf-reader-tablet.png")});
      await menu(); await page.screenshot({path:path.join(process.env.DEVVAULT_SCREENSHOT_DIR,"pdf-tools-tablet.png")});
      await page.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    }
    await page.setViewportSize({width:1280,height:800});
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').dataset.rendered==="true");
    assert.equal((await sheet(1).boundingBox()).width,1280);
    await menu(); await page.getByLabel("확대",{exact:true}).selectOption("150");
    await page.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    assert.equal((await sheet(1).boundingBox()).width,1920);
    await menu(); await page.getByRole("button",{name:"전체 화면 전환"}).click();
    await page.waitForFunction(() => !!document.fullscreenElement);
    await page.getByRole("dialog").waitFor({state:"hidden"});
    await menu(); await page.getByRole("button",{name:"전체 화면 전환"}).click();
    await page.waitForFunction(() => !document.fullscreenElement);
    await page.getByRole("dialog").waitFor({state:"hidden"});
    await ready(2); await choose("펜"); await stroke(1);
    await choose("지우개"); await stroke(1,.35,.15,.35,.25);
    await menu(); await page.getByRole("button",{name:"오른쪽 90° 회전"}).click();
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').dataset.rendered==="true" && !document.querySelector("dialog").open);
    await menu(); assert.equal(await page.getByLabel("가로(px)").inputValue(),"300");
    await page.getByLabel("가로(px)").fill("200"); await page.getByLabel("세로(px)").fill("100");
    await page.getByRole("button",{name:"크기 적용"}).click();
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').dataset.rendered==="true" && !document.querySelector("dialog").open);
    await choose("영역 드래그로 자르기"); await stroke(1,.1,.1,.9,.9);
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').dataset.rendered==="true" && !document.querySelector("dialog").open);
    await menu(); assert.equal(await page.getByLabel("가로(px)").inputValue(),"160");
    await save(); assert.equal(files.get(5).type,"image/png"); assert.deepEqual(files.get(2).bytes,image);
    await ready(5);
    await page.setViewportSize({width:390,height:844});
    await menu();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth<=window.innerWidth),true);
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"160");
    assert.deepEqual(errors, []);
    console.log("PASS: tablet edge-to-edge continuous PDF, hidden drawer, native touch scroll, lazy pages, partial erasure/save/reopen, Korean text, image transforms, portrait/landscape/zoom, original preservation");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
