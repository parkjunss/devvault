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
    const history = new Map([[1, []], [2, []]]);
    const metadata = (id, value) => ({ id, originalName: value.name, contentType: value.type, size: value.bytes.length, folderId: 7, version: value.version ?? 1, checksum: require("node:crypto").createHash("sha256").update(value.bytes).digest("hex"), createdAt: new Date().toISOString() });
    const routeApi = async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      const contentRoute = pathname.match(/^\/api\/files\/(\d+)\/content$/);
      if (contentRoute) {
        const id=Number(contentRoute[1]), current=files.get(id), prior=history.get(id);
        const form=await new Request("http://localhost/upload",{method:"POST",headers:{"content-type":request.headers()["content-type"]},body:request.postDataBuffer()}).formData();
        if(Number(form.get("expectedVersion"))!==(current.version??1)) return route.fulfill({status:409,json:{message:"다른 탭에서 파일을 변경했습니다."}});
        const file=form.get("file");
        if(!prior.length) prior.push(current);
        const value={name:current.name,type:file.type,bytes:Buffer.from(await file.arrayBuffer()),version:(current.version??1)+1};
        files.set(id,value);
        return route.fulfill({json:metadata(id,value)});
      }
      const match = pathname.match(/^\/api\/files\/(\d+)(\/download)?$/);
      if (match && files.has(Number(match[1]))) {
        const id = Number(match[1]), value = files.get(id);
        return route.fulfill(match[2] ? { status: 200, contentType: value.type, body: value.bytes } : { json: metadata(id, value) });
      }
      return route.fulfill({ status: 404, json: { message: "Unmocked request" } });
    };
    await context.route("**/api/**", routeApi);
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
      await page.getByRole("dialog").getByRole("button", { name, exact: true }).click();
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
      await menu(); await page.getByRole("button", { name: "저장", exact: true }).click();
      await page.getByRole("dialog").locator(".editorSuccess").waitFor();
    }
    await ready(1);
    if (process.env.DEVVAULT_PEN_REPRO) {
      for(const name of ["형광펜","지우개","읽기 / 스크롤","펜"]) {
        const button=page.locator(".editorQuickTools").getByRole("button",{name,exact:true});
        await button.click(); assert.equal(await button.getAttribute("aria-pressed"),"true");
      }
      for(let i=0;i<30;i++) await stroke(1,.2,.2+i*.008,.4,.2+i*.008);
      await page.evaluate(() => {
        window.inkLines=0;
        const lineTo=CanvasRenderingContext2D.prototype.lineTo;
        CanvasRenderingContext2D.prototype.lineTo=function(...args){ window.inkLines++; return lineTo.apply(this,args); };
      });
      const box=await ink(1).boundingBox();
      await page.mouse.move(box.x+box.width*.2,box.y+box.height*.5); await page.mouse.down();
      await page.evaluate(() => { window.inkLines=0; });
      await page.mouse.move(box.x+box.width*.4,box.y+box.height*.5);
      const lines=await page.evaluate(() => window.inkLines);
      await ink(1).evaluate(canvas => canvas.dispatchEvent(new PointerEvent("pointercancel",{bubbles:true,pointerId:77,pointerType:"touch"})));
      await page.mouse.up();
      assert.ok(await alpha(1,.3,.5)>0,"a palm cancellation must not cancel the active stroke");
      await page.locator(".editorViewport").evaluate(e => {e.scrollTop=350;});
      const before=await page.locator(".editorViewport").evaluate(e=>e.scrollTop);
      await page.keyboard.press("Control+z");
      await page.evaluate(() => new Promise(requestAnimationFrame));
      const after=await page.locator(".editorViewport").evaluate(e=>e.scrollTop);
      const selection=await sheet(1).evaluate(e=>getComputedStyle(e).userSelect);
      console.log(JSON.stringify({linesPerMove:lines,undoScrollBefore:before,undoScrollAfter:after,userSelect:selection}));
      assert.ok(lines<20 && before===after && selection==="none", "ink work must not grow with old strokes; undo must retain scroll; paper must prevent selection");
      if(process.env.DEVVAULT_SCREENSHOT_DIR) {
        fs.mkdirSync(process.env.DEVVAULT_SCREENSHOT_DIR,{recursive:true});
        await page.screenshot({path:path.join(process.env.DEVVAULT_SCREENSHOT_DIR,"pen-toolbar.png")});
      }
      return;
    }
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
    await menu(); assert.equal(await page.getByRole("button",{name:"저장",exact:true}).isDisabled(),true);
    await page.keyboard.press("Escape");
    await page.getByRole("dialog").waitFor({state:"hidden"});
    assert.equal(await page.getByRole("button",{name:"편집 메뉴 열기",exact:true}).evaluate(e=>e===document.activeElement),true);
    await choose("펜"); await stroke(1);
    await page.waitForFunction(() => { const c=document.querySelector('.editorPaper[data-page="1"] .editorInk'); return c.getContext('2d').getImageData(Math.floor(c.width*.35),Math.floor(c.height*.2),1,1).data[3]>0; }, null, {timeout:5000}).catch(async error => { console.error(await page.evaluate(() => ({scroll:document.querySelector('.editorViewport').scrollTop, marks:document.querySelector('.editorMarkList').textContent, errors:[...document.querySelectorAll('.editorError')].map(e=>e.textContent), first:document.querySelector('.editorPaper').getBoundingClientRect().toJSON()}))); throw error; });
    await page.locator(".editorQuickTools").getByRole("button", {name:"되돌리기",exact:true}).click();
    assert.equal(await alpha(1,.35,.2),0,"pen undo removes the stroke");
    assert.equal(await page.locator(".editorUnsavedDot").count(),0,"undo to baseline clears dirty state");
    await page.locator(".editorQuickTools").getByRole("button", {name:"다시 실행",exact:true}).click();
    assert.ok(await alpha(1,.35,.2)>0,"pen redo restores the stroke");
    await choose("지우개"); await stroke(1,.35,.15,.35,.25);
    assert.equal(await alpha(1,.35,.2),0,"eraser removes only the crossed ink");
    assert.ok(await alpha(1,.24,.2)>0,"uncrossed stroke remains");
    await page.locator(".editorQuickTools").getByRole("button", {name:"되돌리기",exact:true}).click();
    assert.ok(await alpha(1,.35,.2)>0,"undo restores erased ink");
    await page.keyboard.press("Control+Shift+Z");
    assert.equal(await alpha(1,.35,.2),0,"redo reapplies erasure");
    await page.keyboard.press("Control+z");
    await stroke(1,.35,.15,.35,.25);
    assert.equal(await page.locator(".editorQuickTools").getByRole("button",{name:"다시 실행",exact:true}).isDisabled(),true);
    await choose("읽기 / 스크롤");
    await scrollTo(10);
    await page.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"] canvas').width===1);
    assert.ok(await page.locator('.editorPaper[data-rendered="true"]').count()<6,"off-screen pages release their canvases");
    await scrollTo(1); assert.equal(await alpha(1,.35,.2),0); assert.ok(await alpha(1,.24,.2)>0);
    await scrollTo(2); await choose("형광펜"); await stroke(2);
    await choose("읽기 / 스크롤"); await scrollTo(1);
    await page.keyboard.press("Control+z");
    assert.ok(Math.abs((await sheet(1).boundingBox()).y)<2,"undo must not navigate to the edited page");
    await page.waitForFunction(() => { const canvas=document.querySelector('.editorPaper[data-page="2"] .editorInk'); return canvas.getContext("2d").getImageData(Math.floor(canvas.width*.35),Math.floor(canvas.height*.2),1,1).data[3]===0; });
    assert.equal(await alpha(2,.35,.2),0,"undo changes ink without changing pages");
    await page.keyboard.press("Control+y");
    await scrollTo(2);
    await page.waitForFunction(() => { const c=document.querySelector('.editorPaper[data-page="2"] .editorInk'); return c.getContext("2d").getImageData(Math.floor(c.width*.35),Math.floor(c.height*.2),1,1).data[3]>0; });
    assert.ok(await alpha(2,.35,.2)>0,"highlight redo restores the stroke");
    await scrollTo(2); await choose("텍스트"); await page.getByRole("textbox",{name:"추가할 텍스트"}).fill("한글 메모");
    await page.getByRole("button",{name:"문서에 텍스트 배치"}).click();
    await ink(2).click({position:{x:80,y:180}});
    await save(); assert.equal(files.size,2); assert.equal(files.get(1).version,2); assert.deepEqual(history.get(1)[0].bytes,original);
    await ready(1); assert.equal(await alpha(1,.35,.2),0); assert.ok(await alpha(1,.24,.2)>0);
    await scrollTo(2); await menu(); await page.locator(".editorMarkList summary").click();
    assert.equal(await page.locator(".editorMarkList > div").count(),2);
    await page.locator(".editorMarkList > div").first().getByRole("button",{name:"삭제",exact:true}).click();
    await page.getByRole("dialog").getByRole("button",{name:"되돌리기",exact:true}).click();
    assert.equal(await page.locator(".editorMarkList > div").count(),2,"undo restores deleted annotations");
    await page.getByRole("dialog").getByRole("button",{name:"다시 실행",exact:true}).click();
    assert.equal(await page.locator(".editorMarkList > div").count(),1);
    await save(); await ready(1); await scrollTo(2);
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
    await save(); assert.equal(files.get(2).type,"image/png"); assert.deepEqual(history.get(2)[0].bytes,image);
    await ready(2);
    await page.setViewportSize({width:390,height:844});
    await menu();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth<=window.innerWidth),true);
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"160");
    assert.equal(await page.getByRole("region",{name:"버전 이력"}).count(),0);
    assert.equal(history.get(2).length,1,"only original is retained");
    // Reset only the mocked fixture for the independent image-transform recovery scenario.
    files.set(2,{name:"sample.png",type:"image/png",bytes:image,version:3});
    // Image transforms are reversible, including the annotations flattened into them.
    await ready(2); await choose("펜"); await stroke(1);
    await menu(); await page.getByRole("button",{name:"오른쪽 90° 회전"}).click();
    await page.getByRole("dialog").waitFor({state:"hidden"});
    await menu();
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"300");
    await page.getByRole("dialog").getByRole("button",{name:"되돌리기",exact:true}).click();
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"400");
    await page.getByRole("dialog").getByRole("button",{name:"다시 실행",exact:true}).click();
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"300");
    await page.getByText("이 기기에 임시 저장됨 · 서버 저장은 별도입니다.",{exact:true}).waitFor();
    await page.reload();
    await page.getByRole("button",{name:"임시 편집 복구",exact:true}).click();
    await page.getByRole("button",{name:"임시 편집 복구",exact:true}).waitFor({state:"hidden"});
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"300","image draft restores transformed dimensions");
    await page.getByText("이 기기에 임시 저장됨 · 서버 저장은 별도입니다.",{exact:true}).waitFor();
    await page.reload();
    await page.getByRole("button",{name:"임시 편집 버리기",exact:true}).click();
    assert.equal(await page.getByLabel("가로(px)").inputValue(),"400","discard opens original");
    await page.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    await ready(1); await choose("펜"); await stroke(1);
    await menu(); await page.getByText("이 기기에 임시 저장됨 · 서버 저장은 별도입니다.",{exact:true}).waitFor();
    await page.reload();
    await page.getByRole("button",{name:"임시 편집 복구",exact:true}).click();
    await page.getByRole("button",{name:"임시 편집 복구",exact:true}).waitFor({state:"hidden"});
    await page.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    await scrollTo(1); assert.ok(await alpha(1,.35,.2)>0,"PDF draft restores ink");
    await menu(); await page.getByRole("checkbox",{name:"펜 전용 모드"}).check();
    await choose("펜");
    const beforeTouch = await alpha(1,.6,.4);
    await ink(1).evaluate(canvas => {
      const b=canvas.getBoundingClientRect();
      canvas.dispatchEvent(new PointerEvent("pointerdown",{bubbles:true,pointerId:77,pointerType:"touch",isPrimary:true,button:0,clientX:b.x+b.width*.6,clientY:b.y+b.height*.4}));
      canvas.dispatchEvent(new PointerEvent("pointerup",{bubbles:true,pointerId:77,pointerType:"touch",isPrimary:true,button:0}));
    });
    assert.equal(await alpha(1,.6,.4),beforeTouch,"finger does not draw in pen-only mode");
    // Real browser pen input must remain a stroke, not become a scrolling gesture.
    const penBox=await ink(1).boundingBox();
    await touch.send("Input.dispatchMouseEvent",{type:"mouseMoved",x:penBox.x+penBox.width*.2,y:penBox.y+penBox.height*.4,pointerType:"pen"});
    await touch.send("Input.dispatchMouseEvent",{type:"mousePressed",x:penBox.x+penBox.width*.2,y:penBox.y+penBox.height*.4,button:"left",clickCount:1,pointerType:"pen"});
    await touch.send("Input.dispatchMouseEvent",{type:"mouseMoved",x:penBox.x+penBox.width*.5,y:penBox.y+penBox.height*.4,button:"left",buttons:1,pointerType:"pen"});
    await touch.send("Input.dispatchMouseEvent",{type:"mouseReleased",x:penBox.x+penBox.width*.5,y:penBox.y+penBox.height*.4,button:"left",clickCount:1,pointerType:"pen"});
    assert.ok(await alpha(1,.35,.4)>0,"pen draws in pen-only mode");
    await touch.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[{x:180,y:650}]});
    for(let y=610;y>=250;y-=40) await touch.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:[{x:180,y}]});
    await touch.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    await page.waitForFunction(() => document.querySelector(".editorViewport").scrollTop>100);
    // A real desktop input profile defaults to fitting both page dimensions, including rotated pages.
    const desktopContext = await browser.newContext({viewport:{width:1440,height:900}});
    await desktopContext.route("**/api/**",routeApi);
    const desktop = await desktopContext.newPage();
    desktop.on("pageerror", error => errors.push(error.message));
    await desktop.goto("http://localhost:3107/files/1/edit");
    await desktop.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]')?.dataset.rendered === "true");
    assert.equal(await desktop.evaluate(() => matchMedia('(hover: hover) and (pointer: fine)').matches),true);
    const desktopFirst = desktop.locator('.editorPaper[data-page="1"]');
    let fit = await desktopFirst.boundingBox();
    assert.ok(fit.y>=0 && fit.y+fit.height<=900 && fit.width<1440,"desktop page fits top and bottom");
    await desktop.locator('.editorPaper[data-page="2"]').evaluate(element=>element.scrollIntoView({block:"start"}));
    const rotatedFit = await desktop.locator('.editorPaper[data-page="2"]').boundingBox();
    assert.ok(rotatedFit.y>=-1 && rotatedFit.y+rotatedFit.height<=900 && rotatedFit.width<=1440);
    await desktopFirst.evaluate(element=>element.scrollIntoView({block:"start"}));
    await desktop.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').dataset.rendered === "true");
    if(process.env.DEVVAULT_SCREENSHOT_DIR) await desktop.screenshot({path:path.join(process.env.DEVVAULT_SCREENSHOT_DIR,"pdf-desktop-page-fit.png")});
    await desktop.getByRole("button",{name:"편집 메뉴 열기",exact:true}).click();
    await desktop.getByLabel("확대",{exact:true}).selectOption("width");
    await desktop.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    fit=await desktopFirst.boundingBox();
    assert.ok(fit.width>=1420 && fit.height>900,"width fit remains available");
    await desktop.getByRole("button",{name:"편집 메뉴 열기",exact:true}).click();
    await desktop.getByLabel("확대",{exact:true}).selectOption("50");
    await desktop.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    fit=await desktopFirst.boundingBox();
    assert.ok(fit.width>=710 && fit.width<=720,"manual 50 percent scales from viewport width");
    await desktop.getByRole("button",{name:"편집 메뉴 열기",exact:true}).click();
    await desktop.getByLabel("확대",{exact:true}).selectOption("page");
    await desktop.getByRole("button",{name:"편집 메뉴 닫기"}).click();
    await desktop.setViewportSize({width:1280,height:720});
    await desktop.waitForFunction(() => document.querySelector('.editorPaper[data-page="1"]').getBoundingClientRect().height<=704.5);
    await desktopFirst.evaluate(element=>element.scrollIntoView({block:"start"}));
    fit=await desktopFirst.boundingBox();
    assert.ok(fit.y+fit.height<=720,"page fit responds to window resizing");
    // Another tab saves first: stale editing remains available and does not overwrite its version.
    const serverVersion = files.get(1);
    history.get(1).push(serverVersion);
    files.set(1,{...serverVersion,version:serverVersion.version+1});
    await desktop.getByRole("button",{name:"편집 메뉴 열기",exact:true}).click();
    await desktop.getByRole("dialog").getByRole("button",{name:"펜",exact:true}).click();
    const desktopInk=desktopFirst.locator(".editorInk"), box=await desktopInk.boundingBox();
    await desktop.mouse.move(box.x+box.width*.2,box.y+box.height*.3); await desktop.mouse.down();
    await desktop.mouse.move(box.x+box.width*.5,box.y+box.height*.3); await desktop.mouse.up();
    await desktop.getByRole("button",{name:"편집 메뉴 열기",exact:true}).click();
    await desktop.getByRole("button",{name:"저장",exact:true}).click();
    await desktop.getByText("저장 실패: 다른 탭에서 파일을 변경했습니다.",{exact:true}).waitFor();
    assert.equal(await desktop.locator(".editorUnsavedDot").count(),1);
    assert.equal(files.get(1).version,serverVersion.version+1);
    assert.equal(files.size,2);
    await desktopContext.close();
    assert.deepEqual(errors, []);
    console.log("PASS: tablet edge-to-edge continuous PDF, hidden drawer, native touch scroll, lazy pages, partial erasure/save/reopen, Korean text, image transforms, portrait/landscape/zoom, original preservation, undo/redo shortcuts and branching, desktop page fit, local draft recovery/discard, image transform history, pen-only drawing and finger scrolling, single-file content save without version UI");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
