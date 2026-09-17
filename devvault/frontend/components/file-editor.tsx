"use client";

import { useEffect, useRef, useState, type PointerEvent } from "react";
import Link from "next/link";
import type { PDFDocumentProxy } from "pdfjs-dist";
import { apiFetch, apiJson } from "@/lib/api";
import type { VaultFile } from "@/lib/types";
import { createPdfCopy, drawMarks, readMarks, MAX_EDIT_BYTES, SOURCE_ATTACHMENT, MARKS_ATTACHMENT, type Mark, type Point } from "@/lib/document-editing";
import "./file-editor.css";

type Tool = Mark["tool"] | "crop";
const message = (error: unknown) => error instanceof Error ? error.message : "작업에 실패했습니다.";
const canvasBlob = (canvas: HTMLCanvasElement) => new Promise<Blob>((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error("이미지를 만들 수 없습니다.")), "image/png"));
const loadImage = (url: string) => new Promise<HTMLImageElement>((resolve, reject) => {
  const image = new window.Image();
  image.onload = () => resolve(image);
  image.onerror = () => reject(new Error("이미지를 열 수 없습니다."));
  image.src = url;
});
function canvasOf(width: number, height: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width * height > 16000000 || width > 16000 || height > 16000) throw new Error("이미지는 최대 1,600만 화소, 한 변 16,000px까지 편집할 수 있습니다.");
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

export function FileEditor({ fileId }: { fileId: number }) {
  const background = useRef<HTMLCanvasElement>(null);
  const foreground = useRef<HTMLCanvasElement>(null);
  const pdf = useRef<PDFDocumentProxy | null>(null);
  const source = useRef<Uint8Array | null>(null);
  const bitmap = useRef<HTMLImageElement | null>(null);
  const initialImage = useRef<HTMLImageElement | null>(null);
  const draft = useRef<Mark | null>(null);
  const cropStart = useRef<Point | null>(null);
  const [file, setFile] = useState<VaultFile | null>(null);
  const [kind, setKind] = useState<"pdf" | "image">("image");
  const [ready, setReady] = useState(false);
  const [rendering, setRendering] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState<VaultFile | null>(null);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(1);
  const [marks, setMarks] = useState<Mark[]>([]);
  const [tool, setTool] = useState<Tool>("pen");
  const [color, setColor] = useState("#2459cc");
  const [size, setSize] = useState(3);
  const [text, setText] = useState("");
  const [dimensions, setDimensions] = useState({ width: 1, height: 1 });
  const [resize, setResize] = useState({ width: 1, height: 1 });
  const [revision, setRevision] = useState(0);
  const [zoom, setZoom] = useState(100);
  const busy = saving || rendering || !ready;

  useEffect(() => {
    let cancelled = false;
    let opened: PDFDocumentProxy | null = null;
    let imageUrl: string | null = null;
    async function open() {
      if (!Number.isSafeInteger(fileId) || fileId <= 0) throw new Error("올바르지 않은 파일입니다.");
      const metadata = await apiJson<VaultFile>(`/api/files/${fileId}`);
      if (metadata.size > MAX_EDIT_BYTES) throw new Error("편집은 50MB 이하 파일을 지원합니다.");
      const isPdf = metadata.contentType === "application/pdf" || /\.pdf$/i.test(metadata.originalName);
      if (!isPdf && !/^image\/(png|jpeg|webp|gif|bmp)$/.test(metadata.contentType ?? "")) throw new Error("PDF 또는 PNG·JPEG·WebP·GIF·BMP 이미지를 선택해 주세요.");
      const response = await apiFetch(`/api/files/${fileId}/download`);
      if (!response.ok) throw new Error("파일을 열 수 없습니다. 로그인 상태를 확인해 주세요.");
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.byteLength > MAX_EDIT_BYTES) throw new Error("편집은 50MB 이하 파일을 지원합니다.");
      if (cancelled) return;
      if (isPdf) {
        const pdfjs = await import("pdfjs-dist");
        pdfjs.GlobalWorkerOptions.workerSrc = "/pdf.worker.min.mjs";
        const pdfOptions = { cMapUrl: "/pdf-assets/cmaps/", cMapPacked: true, standardFontDataUrl: "/pdf-assets/standard_fonts/", wasmUrl: "/pdf-assets/wasm/" };
        const task = pdfjs.getDocument({ ...pdfOptions, data: bytes.slice() });
        task.onPassword = () => { void task.destroy(); };
        opened = await task.promise;
        const attachments = await opened.getAttachments();
        const entries = [...(attachments?.entries() ?? [])];
        const originalId = entries.find(([, attachment]) => attachment.filename === SOURCE_ATTACHMENT)?.[0];
        const marksId = entries.find(([, attachment]) => attachment.filename === MARKS_ATTACHMENT)?.[0];
        const original = originalId ? await opened.getAttachmentContent(originalId) : null;
        const annotations = marksId ? await opened.getAttachmentContent(marksId) : null;
        let restored: Mark[] = [];
        let base: Uint8Array = bytes;
        if (original && annotations) {
          if (original.length > MAX_EDIT_BYTES || annotations.length > 10 * 1024 * 1024) throw new Error("편집 데이터가 너무 큽니다.");
          await opened.loadingTask.destroy();
          const baseTask = pdfjs.getDocument({ ...pdfOptions, data: original.slice() });
          baseTask.onPassword = () => { void baseTask.destroy(); };
          opened = await baseTask.promise;
          restored = readMarks(new TextDecoder().decode(annotations), opened.numPages);
          base = original;
        }
        if (cancelled) { await opened.loadingTask.destroy(); return; }
        pdf.current = opened;
        source.current = base;
        setMarks(restored);
        setPages(opened.numPages);
        setKind("pdf");
      } else {
        imageUrl = URL.createObjectURL(new Blob([bytes], { type: metadata.contentType ?? "image/png" }));
        const image = await loadImage(imageUrl);
        canvasOf(image.naturalWidth, image.naturalHeight);
        if (cancelled) return;
        bitmap.current = initialImage.current = image;
        setKind("image");
      }
      setFile(metadata);
      setReady(true);
    }
    open().catch(error => { if (!cancelled) setError(`열기 실패: ${message(error)}`); });
    return () => { cancelled = true; if (opened) void opened.loadingTask.destroy(); if (imageUrl) URL.revokeObjectURL(imageUrl); };
  }, [fileId]);

  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    let task: { cancel: () => void; promise: Promise<void> } | undefined;
    async function render() {
      setRendering(true);
      draft.current = null;
      cropStart.current = null;
      let canvas: HTMLCanvasElement;
      if (pdf.current) {
        const pdfPage = await pdf.current.getPage(page);
        if (cancelled) return;
        const natural = pdfPage.getViewport({ scale: 1 });
        const scale = Math.min(1.5, 2500 / Math.max(natural.width, natural.height));
        const viewport = pdfPage.getViewport({ scale });
        canvas = canvasOf(Math.ceil(viewport.width), Math.ceil(viewport.height));
        task = pdfPage.render({ canvas, viewport });
        await task.promise;
      } else {
        const image = bitmap.current!;
        canvas = canvasOf(image.naturalWidth, image.naturalHeight);
        canvas.getContext("2d")!.drawImage(image, 0, 0);
      }
      if (cancelled || !background.current) return;
      background.current.width = canvas.width;
      background.current.height = canvas.height;
      background.current.getContext("2d")!.drawImage(canvas, 0, 0);
      setDimensions({ width: canvas.width, height: canvas.height });
      setResize({ width: canvas.width, height: canvas.height });
    }
    render().catch(error => { if (!cancelled) setError(message(error)); }).finally(() => { if (!cancelled) setRendering(false); });
    return () => { cancelled = true; task?.cancel(); };
  }, [ready, page, revision]);

  useEffect(() => {
    const canvas = foreground.current;
    if (!canvas) return;
    canvas.width = dimensions.width;
    canvas.height = dimensions.height;
    drawMarks(canvas.getContext("2d")!, marks.filter(mark => mark.page === page), canvas.width, canvas.height);
  }, [marks, page, dimensions]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ""; } };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  function updateMarks(next: Mark[]) {
    try { readMarks(JSON.stringify(next), pages); }
    catch (error) { setError(message(error)); return; }
    setMarks(next);
    setDirty(true);
    setSaved(null);
  }
  function position(event: PointerEvent<HTMLCanvasElement>): Point {
    const box = event.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)), y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height)) };
  }
  function repaint() {
    const canvas = foreground.current!;
    const context = canvas.getContext("2d")!;
    context.clearRect(0, 0, canvas.width, canvas.height);
    drawMarks(context, [...marks.filter(mark => mark.page === page), ...(draft.current ? [draft.current] : [])], canvas.width, canvas.height);
  }
  function pointerDown(event: PointerEvent<HTMLCanvasElement>) {
    if (busy || !event.isPrimary || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = position(event);
    if (tool === "crop") { cropStart.current = point; return; }
    if (tool === "text" && !text.trim()) { setError("추가할 텍스트를 입력한 후 문서를 눌러 주세요."); return; }
    const mark: Mark = { page, tool, color, width: (tool === "text" ? size * 5 + 12 : tool === "highlight" ? size * 4 + 10 : size) / 1000, points: [point], ...(tool === "text" ? { text: text.trim() } : {}) };
    if (tool === "text") updateMarks([...marks, mark]);
    else { draft.current = mark; repaint(); }
  }
  function pointerMove(event: PointerEvent<HTMLCanvasElement>) {
    if (!event.isPrimary || busy) return;
    if (draft.current && draft.current.points.length < 10000) { draft.current.points.push(position(event)); repaint(); }
    if (cropStart.current) {
      repaint();
      const point = position(event), start = cropStart.current;
      const context = foreground.current!.getContext("2d")!;
      context.strokeStyle = "#2459cc";
      context.lineWidth = 3;
      context.strokeRect(start.x * dimensions.width, start.y * dimensions.height, (point.x - start.x) * dimensions.width, (point.y - start.y) * dimensions.height);
    }
  }
  function pointerUp(event: PointerEvent<HTMLCanvasElement>) {
    if (!event.isPrimary) return;
    if (draft.current) { const mark = draft.current; draft.current = null; updateMarks([...marks, mark]); }
    if (cropStart.current) {
      const start = cropStart.current, end = position(event);
      cropStart.current = null;
      const x = Math.floor(Math.min(start.x, end.x) * dimensions.width);
      const y = Math.floor(Math.min(start.y, end.y) * dimensions.height);
      const width = Math.round(Math.abs(start.x - end.x) * dimensions.width);
      const height = Math.round(Math.abs(start.y - end.y) * dimensions.height);
      if (width > 1 && height > 1) void transformImage("crop", { x, y, width, height });
      else repaint();
    }
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }

  async function transformImage(action: "rotate" | "resize" | "crop", crop?: { x: number; y: number; width: number; height: number }) {
    if (busy || kind !== "image") return;
    setRendering(true);
    setError("");
    try {
      const input = canvasOf(dimensions.width, dimensions.height);
      const context = input.getContext("2d")!;
      context.drawImage(background.current!, 0, 0);
      drawMarks(context, marks, input.width, input.height);
      const output = action === "rotate" ? canvasOf(input.height, input.width) : action === "crop" ? canvasOf(crop!.width, crop!.height) : canvasOf(resize.width, resize.height);
      const out = output.getContext("2d")!;
      if (action === "rotate") { out.translate(output.width, 0); out.rotate(Math.PI / 2); out.drawImage(input, 0, 0); }
      else if (crop) out.drawImage(input, crop.x, crop.y, crop.width, crop.height, 0, 0, output.width, output.height);
      else out.drawImage(input, 0, 0, output.width, output.height);
      bitmap.current = await loadImage(output.toDataURL("image/png"));
      setMarks([]);
      setRevision(value => value + 1);
      setDirty(true);
      setSaved(null);
    } catch (error) { setError(message(error)); }
    finally { setRendering(false); }
  }

  async function save() {
    if (busy || !file) return;
    setSaving(true);
    setError("");
    try {
      await document.fonts.ready;
      let blob: Blob;
      if (kind === "pdf") {
        const bytes = await createPdfCopy(source.current!, marks, async (pageMarks, width, height) => {
          const scale = Math.min(2, 3000 / Math.max(width, height));
          const canvas = canvasOf(Math.ceil(width * scale), Math.ceil(height * scale));
          drawMarks(canvas.getContext("2d")!, pageMarks, canvas.width, canvas.height);
          return new Uint8Array(await (await canvasBlob(canvas)).arrayBuffer());
        });
        blob = new Blob([new Uint8Array(bytes)], { type: "application/pdf" });
      } else {
        const canvas = canvasOf(dimensions.width, dimensions.height);
        canvas.getContext("2d")!.drawImage(background.current!, 0, 0);
        drawMarks(canvas.getContext("2d")!, marks, canvas.width, canvas.height);
        blob = await canvasBlob(canvas);
      }
      const extension = kind === "pdf" ? "pdf" : "png";
      if (blob.size > MAX_EDIT_BYTES) throw new Error("편집본이 50MB를 초과합니다. 필기 수나 이미지 크기를 줄여 주세요.");
      const stem = file.originalName.replace(/\.[^.]+$/, "").slice(0, 150);
      const name = `${stem}-편집본-${new Date().toISOString().replace(/[:.]/g, "-")}.${extension}`;
      const body = new FormData();
      body.append("file", blob, name);
      if (file.folderId) body.append("folderId", String(file.folderId));
      const copy = await apiJson<VaultFile>("/api/files", { method: "POST", body });
      setSaved(copy);
      setDirty(false);
    } catch (error) { setError(`저장 실패: ${message(error)}`); }
    finally { setSaving(false); }
  }

  return <main className="fileEditor">
    <header><div><Link href="/" onClick={event => { if (dirty && !window.confirm("저장하지 않은 변경 사항이 있습니다. 나갈까요?")) event.preventDefault(); }}>DevVault</Link><h1>{file?.originalName ?? "파일 편집"}</h1><p>원본을 보존하고 같은 폴더에 편집본을 새로 저장합니다.</p></div><button className="primaryButton" onClick={save} disabled={busy || !dirty}>{saving ? "저장 중..." : "편집본 저장"}</button></header>
    {error && <p role="alert" className="editorError">{error}</p>}
    {saved && <p role="status" className="editorSuccess">저장 완료: {saved.originalName} · <a href={`/files/${saved.id}/edit`}>편집본 열기</a> · 파일 목록을 새로고침하면 표시됩니다.</p>}
    {!ready && !error && <p role="status">파일을 여는 중...</p>}
    {ready && <>
      <fieldset className="editorToolbar" disabled={busy}>
        <legend>편집 도구</legend>
        <div className="editorTools">{(["pen", "highlight", "text"] as const).map(value => <button key={value} aria-pressed={tool === value} onClick={() => { setTool(value); if (value === "highlight") setColor("#ffe100"); }}>{value === "pen" ? "펜" : value === "highlight" ? "형광펜" : "텍스트"}</button>)}
          <label>색상<input aria-label="색상" type="color" value={color} onChange={event => setColor(event.target.value)} /></label>
          <label>굵기<input type="range" min="1" max="12" value={size} onChange={event => setSize(Number(event.target.value))} /></label>
          <button disabled={!marks.some(mark => mark.page === page)} onClick={() => { const last = marks.findLastIndex(mark => mark.page === page); updateMarks(marks.filter((_, index) => index !== last)); }}>마지막 필기 취소</button>
        </div>
        {tool === "text" && <label>추가할 텍스트<textarea maxLength={1000} value={text} onChange={event => setText(event.target.value)} placeholder="입력한 뒤 문서에서 위치를 눌러 주세요." /></label>}
        {kind === "image" && <div className="editorTools">
          <button onClick={() => void transformImage("rotate")}>오른쪽 90° 회전</button><button aria-pressed={tool === "crop"} onClick={() => setTool("crop")}>영역 드래그로 자르기</button>
          <label>가로(px)<input type="number" min="1" max="16000" value={resize.width} onChange={event => setResize({ ...resize, width: Number(event.target.value) })} /></label>
          <label>세로(px)<input type="number" min="1" max="16000" value={resize.height} onChange={event => setResize({ ...resize, height: Number(event.target.value) })} /></label>
          <button onClick={() => void transformImage("resize")}>크기 적용</button>
          <button onClick={() => { if (window.confirm("이미지 편집을 모두 초기화할까요?")) { bitmap.current = initialImage.current; updateMarks([]); setRevision(value => value + 1); } }}>원래 이미지로 초기화</button>
        </div>}
      </fieldset>
      <div className="editorNavigation">{kind === "pdf" && <><button disabled={busy || page === 1} onClick={() => setPage(page - 1)}>이전 페이지</button><span>{page} / {pages}</span><button disabled={busy || page === pages} onClick={() => setPage(page + 1)}>다음 페이지</button></>}
        <label>확대<select value={zoom} onChange={event => setZoom(Number(event.target.value))}>{[50, 75, 100, 125, 150, 200].map(value => <option key={value} value={value}>{value}%</option>)}</select></label>
        {rendering && <span role="status">그리는 중...</span>}
      </div>
      <p className="editorHint">{kind === "pdf" ? "필기는 아래 목록에서 삭제하거나 텍스트를 수정할 수 있습니다. 편집본을 다시 열어도 필기를 수정할 수 있습니다." : "이미지는 PNG로 저장되며 필기와 텍스트가 이미지에 합쳐집니다. 회전·자르기·크기 적용 시에도 합쳐집니다. GIF는 정지 이미지로 저장됩니다."}</p>
      <div className="editorViewport"><div className="editorPaper" style={{ width: `${zoom}%`, maxWidth: `${dimensions.width * zoom / 100}px`, aspectRatio: `${dimensions.width} / ${dimensions.height}` }}>
        <canvas ref={background} aria-label={kind === "pdf" ? `${page}페이지 원문` : "원본 이미지"} />
        <canvas ref={foreground} className="editorInk" aria-label="필기 영역" onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={() => { draft.current = null; cropStart.current = null; repaint(); }} />
      </div></div>
      <section className="editorMarkList" aria-label="현재 페이지 필기"><h2>현재 페이지 필기</h2>{!marks.some(mark => mark.page === page) && <p>펜으로 그리거나 텍스트를 추가해 보세요.</p>}{marks.map((mark, index) => mark.page === page && <div key={index}><span>{mark.tool === "text" ? mark.text : mark.tool === "highlight" ? "형광펜" : "펜"}</span>{mark.tool === "text" && <button disabled={busy} onClick={() => { const next = window.prompt("텍스트 수정", mark.text); if (next?.trim()) updateMarks(marks.map((item, i) => i === index ? { ...item, text: next.trim() } : item)); }}>텍스트 수정</button>}<button disabled={busy} onClick={() => updateMarks(marks.filter((_, i) => i !== index))}>삭제</button></div>)}</section>
    </>}
  </main>;
}
