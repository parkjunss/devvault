"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import Link from "next/link";
import { ArrowUUpLeft, ArrowUUpRight, List, X, Pen, Highlighter, Eraser, Hand } from "@phosphor-icons/react";
import { EditorPage, type EditorTool, type Crop } from "./editor-page";
import type { PDFDocumentProxy } from "pdfjs-dist";
import { apiFetch, apiJson } from "@/lib/api";
import type { VaultFile } from "@/lib/types";
import { changeMarkHistory, createPdfCopy, drawMarks, readMarks, MAX_EDIT_BYTES, SOURCE_ATTACHMENT, MARKS_ATTACHMENT, type Mark } from "@/lib/document-editing";
import { editorDraft, type EditorDraft } from "@/lib/editor-draft";
import "./file-editor.css";

type EditorSnapshot = { marks: Mark[]; bitmap: HTMLImageElement | null };

type EditorScale = "auto" | "page" | "width" | number;
const labels: Record<EditorTool, string> = { read: "읽기 / 스크롤", pen: "펜", highlight: "형광펜", text: "텍스트", erase: "지우개", crop: "자르기" };
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
  const shell = useRef<HTMLElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null);
  const source = useRef<Uint8Array | null>(null);

  const initialImage = useRef<HTMLImageElement | null>(null);
  const [file, setFile] = useState<VaultFile | null>(null);
  const [kind, setKind] = useState<"pdf" | "image">("image");
  const [ready, setReady] = useState(false);
  const [transforming, setTransforming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedMarks, setSavedMarks] = useState<Mark[]>([]);
  const [savedBitmap, setSavedBitmap] = useState<HTMLImageElement | null>(null);
  const drawing = useRef(false);
  const onDrawingChange = useCallback((value: boolean) => { drawing.current = value; }, []);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState<VaultFile | null>(null);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(1);
  const [history, dispatchHistory] = useReducer(changeMarkHistory<EditorSnapshot>, { past: [], present: { marks: [], bitmap: null }, future: [] });
  const { marks, bitmap } = history.present;
  const [penOnly, setPenOnly] = useState(false);
  const [pendingDraft, setPendingDraft] = useState<EditorDraft | null>(null);
  const [draftLoaded, setDraftLoaded] = useState(false);
  const [draftStatus, setDraftStatus] = useState("");
  const draftQueue = useRef(Promise.resolve());
  const draftKey = file ? `${file.id}:${file.checksum}:v${file.version ?? 1}` : "";
  const [tool, setTool] = useState<EditorTool>("read");
  const [color, setColor] = useState("#2459cc");
  const [size, setSize] = useState(3);
  const [text, setText] = useState("");
  const [resize, setResize] = useState({ width: 1, height: 1 });
  const [revision, setRevision] = useState(0);
  const [zoom, setZoom] = useState<EditorScale>("auto");
  const [menuOpen, setMenuOpen] = useState(false);
  const busy = saving || transforming || !ready || pendingDraft !== null;
  const dirty = ready && (marks !== savedMarks || bitmap !== savedBitmap);

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
        setPdf(opened);
        source.current = base;
        dispatchHistory({ type: "reset", marks: { marks: restored, bitmap: null } });
        setSavedMarks(restored);
        setPages(opened.numPages);
        setKind("pdf");
      } else {
        imageUrl = URL.createObjectURL(new Blob([bytes], { type: metadata.contentType ?? "image/png" }));
        const image = await loadImage(imageUrl);
        canvasOf(image.naturalWidth, image.naturalHeight);
        if (cancelled) return;
        initialImage.current = image;
        setSavedBitmap(image);
        const initialMarks: Mark[] = [];
        dispatchHistory({ type: "reset", marks: { marks: initialMarks, bitmap: image } });
        setSavedMarks(initialMarks);
        setResize({ width: image.naturalWidth, height: image.naturalHeight });
        setKind("image");
      }
      try {
        const draft = await editorDraft(`${metadata.id}:${metadata.checksum}:v${metadata.version ?? 1}`, "read")
          ?? ((metadata.version ?? 1) === 1 ? await editorDraft(`${metadata.id}:${metadata.checksum}`, "read") : undefined);
        if (draft) {
          readMarks(JSON.stringify(draft.marks), opened?.numPages ?? 1);
          if (draft.image !== null && (typeof draft.image !== "string" || !draft.image.startsWith("data:image/png;base64,"))) throw new Error("잘못된 이미지 임시 저장 데이터입니다.");
          if (!cancelled) { setPendingDraft(draft); setMenuOpen(true); }
        }
      } catch { if (!cancelled) setDraftStatus("임시 저장 데이터를 읽지 못했습니다."); }
      if (cancelled) return;
      setDraftLoaded(true);
      setFile(metadata);
      setReady(true);
    }
    open().catch(error => { if (!cancelled) setError(`열기 실패: ${message(error)}`); });
    return () => { cancelled = true; if (opened) void opened.loadingTask.destroy(); if (imageUrl) URL.revokeObjectURL(imageUrl); };
  }, [fileId]);

  useEffect(() => {
    if (menuOpen) dialog.current?.showModal();
    else dialog.current?.close();
  }, [menuOpen]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ""; } };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  useEffect(() => {
    if (!ready || !draftLoaded || pendingDraft || !draftKey) return;
    let current = true;
    const value: EditorDraft = { marks, image: bitmap && bitmap !== initialImage.current ? bitmap.src : null, updatedAt: Date.now() };
    let timer: number;
    const persist = () => {
      if (drawing.current) { timer = window.setTimeout(persist, 500); return; }
      draftQueue.current = draftQueue.current.catch(() => undefined).then(async () => {
        if (current) setDraftStatus(dirty ? "기기에 임시 저장 중..." : "");
        await editorDraft(draftKey, dirty ? "write" : "delete", value);
        if (file?.version === 1 || file?.version === undefined) await editorDraft(`${file!.id}:${file!.checksum}`, "delete");
        if (current && dirty) setDraftStatus("이 기기에 임시 저장됨 · 서버 저장은 별도입니다.");
      }).catch(() => { if (current) setDraftStatus("임시 저장 실패: 저장 공간을 확인하고 편집본을 저장해 주세요."); });
    };
    timer = window.setTimeout(persist, 500);
    return () => { current = false; window.clearTimeout(timer); };
  }, [ready, draftLoaded, pendingDraft, draftKey, dirty, marks, bitmap, file]);

  async function restoreDraft() {
    if (!pendingDraft) return;
    setTransforming(true);
    try {
      const image = pendingDraft.image ? await loadImage(pendingDraft.image) : initialImage.current;
      if (image) canvasOf(image.naturalWidth, image.naturalHeight);
      dispatchHistory({ type: "edit", marks: { marks: pendingDraft.marks, bitmap: image } });
      if (image) setResize({ width: image.naturalWidth, height: image.naturalHeight });
      setRevision(value => value + 1);
      setPendingDraft(null);
    } catch (error) { setError(`복구 실패: ${message(error)}`); }
    finally { setTransforming(false); }
  }

  const moveHistory = useCallback((direction: "undo" | "redo") => {
    if (busy || drawing.current) return;
    const next = changeMarkHistory(history, { type: direction });
    if (next === history) return;
    dispatchHistory({ type: direction });
    if (next.present.bitmap !== bitmap) {
      const image = next.present.bitmap;
      if (image) setResize({ width: image.naturalWidth, height: image.naturalHeight });
      setRevision(value => value + 1);
    }
    setSaved(null);
  }, [busy, history, bitmap]);

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
      if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select, [contenteditable="true"]')) return;
      const key = event.key.toLowerCase();
      if (key === "z" || key === "y") {
        event.preventDefault();
        moveHistory(key === "y" || event.shiftKey ? "redo" : "undo");
      }
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, [moveHistory]);

  async function clearDraft() {
    await draftQueue.current;
    try { await editorDraft(draftKey, "delete"); }
    catch { setDraftStatus("서버 저장은 완료됐지만 기기 임시 저장을 지우지 못했습니다."); }
  }

  function changeScale(value: string) {
    setZoom(["auto", "page", "width"].includes(value) ? value as EditorScale : Number(value));
    requestAnimationFrame(() => shell.current?.querySelector(`[data-page="${page}"]`)?.scrollIntoView({ block: "start" }));
  }

  function updateMarks(next: Mark[]) {
    // Pointer input is normalized at its source; validate untrusted imported data separately.
    if (next.length > 10000 || next.reduce((sum, mark) => sum + mark.points.length, 0) > 200000 || next.some(mark => (mark.text?.length ?? 0) > 1000)) {
      setError("필기 데이터가 너무 큽니다."); return;
    }
    dispatchHistory({ type: "edit", marks: { marks: next, bitmap } });
    setSaved(null);
  }
  function chooseTool(value: EditorTool) {
    setTool(value);
    if (value === "highlight") setColor("#ffe100");
    else if (value === "pen" || value === "text") setColor("#2459cc");
    if (value !== "text") setMenuOpen(false);
  }

  async function transformImage(action: "rotate" | "resize" | "crop", crop?: Crop) {
    if (busy || !bitmap) return;
    setTransforming(true);
    setError("");
    try {
      const input = canvasOf(bitmap!.naturalWidth, bitmap!.naturalHeight);
      const context = input.getContext("2d")!;
      context.drawImage(bitmap, 0, 0);
      // Erasure affects only ink, never the original image pixels.
      const ink = canvasOf(input.width, input.height);
      drawMarks(ink.getContext("2d")!, marks, ink.width, ink.height);
      context.drawImage(ink, 0, 0);
      const output = action === "rotate" ? canvasOf(input.height, input.width) : action === "crop" ? canvasOf(crop!.width, crop!.height) : canvasOf(resize.width, resize.height);
      const out = output.getContext("2d")!;
      if (action === "rotate") { out.translate(output.width, 0); out.rotate(Math.PI / 2); out.drawImage(input, 0, 0); }
      else if (crop) out.drawImage(input, crop.x, crop.y, crop.width, crop.height, 0, 0, output.width, output.height);
      else out.drawImage(input, 0, 0, output.width, output.height);
      const nextBitmap = await loadImage(output.toDataURL("image/png"));
      setResize({ width: output.width, height: output.height });
      setTool("read");
      setMenuOpen(false);
      dispatchHistory({ type: "edit", marks: { marks: [], bitmap: nextBitmap } });
      setRevision(value => value + 1);
      setSaved(null);
    } catch (error) { setError(message(error)); }
    finally { setTransforming(false); }
  }

  async function save() {
    if (busy || drawing.current || !file) return;
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
        const canvas = canvasOf(bitmap!.naturalWidth, bitmap!.naturalHeight);
        canvas.getContext("2d")!.drawImage(bitmap!, 0, 0);
        const ink = canvasOf(canvas.width, canvas.height);
        drawMarks(ink.getContext("2d")!, marks, ink.width, ink.height);
        canvas.getContext("2d")!.drawImage(ink, 0, 0);
        blob = await canvasBlob(canvas);
      }
      const extension = kind === "pdf" ? "pdf" : "png";
      if (blob.size > MAX_EDIT_BYTES) throw new Error("편집본이 50MB를 초과합니다. 필기 수나 이미지 크기를 줄여 주세요.");
      const stem = file.originalName.replace(/\.[^.]+$/, "").slice(0, 150);
      const name = `${stem}.${extension}`;
      const body = new FormData();
      body.append("file", blob, name);
      body.append("expectedVersion", String(file.version ?? 1));
      const copy = await apiJson<VaultFile>(`/api/files/${file.id}/content`, { method: "POST", body });
      await clearDraft();
      setFile(copy);
      setSaved(copy);
      setSavedMarks(marks);
      setSavedBitmap(bitmap);
    } catch (error) { setError(`저장 실패: ${message(error)}`); }
    finally { setSaving(false); }
  }

  const historyButtons = <>
    <button aria-label="되돌리기" title="되돌리기 (Ctrl/Cmd+Z)" disabled={busy || !history.past.length} onClick={() => moveHistory("undo")}><ArrowUUpLeft size={22} /></button>
    <button aria-label="다시 실행" title="다시 실행 (Ctrl/Cmd+Shift+Z 또는 Ctrl+Y)" disabled={busy || !history.future.length} onClick={() => moveHistory("redo")}><ArrowUUpRight size={22} /></button>
  </>;
  const feedback = <>{error && <p role="alert" className="editorError">{error}<button aria-label="오류 안내 닫기" onClick={() => setError("")}><X /></button></p>}{saved && <p role="status" className="editorSuccess">저장 완료: {saved.originalName}<button aria-label="저장 안내 닫기" onClick={() => setSaved(null)}><X /></button></p>}</>;

  return <main ref={shell} className="fileEditor" aria-label={file?.originalName ?? "파일 읽기 및 편집"}>
    <button className="editorMenuButton" aria-label="편집 메뉴 열기" aria-haspopup="dialog" aria-controls="editor-menu" aria-expanded={menuOpen} onClick={() => setMenuOpen(true)}><List size={24} />{dirty && <span className="editorUnsavedDot" aria-label="저장하지 않은 변경 사항" />}</button>
    <div className="editorQuickTools" role="group" aria-label="빠른 편집 도구">
      {([ ["pen", Pen], ["highlight", Highlighter], ["erase", Eraser], ["read", Hand] ] as const).map(([value, Icon]) => <button key={value} aria-label={labels[value]} title={labels[value]} aria-pressed={tool === value} disabled={busy} onClick={() => chooseTool(value)}><Icon size={22} /></button>)}
      {historyButtons}
    </div>
    <div className="editorViewport" data-tool={tool} role="region" aria-label="문서 연속 보기" tabIndex={0} onContextMenu={event => event.preventDefault()} onDragStart={event => event.preventDefault()}>
      {!ready && <p className="editorLoading" role="status">{error ? "문서를 열지 못했습니다. 메뉴에서 오류를 확인해 주세요." : "파일을 여는 중..."}</p>}
      {ready && <div className="editorPages" data-scale={typeof zoom === "number" ? "custom" : zoom} style={{ width: typeof zoom === "number" ? `${zoom}%` : "100%" }}>
        {Array.from({ length: pages }, (_, index) => <EditorPage key={`${index + 1}-${revision}`} document={pdf} image={bitmap} page={index + 1} marks={marks.filter(mark => mark.page === index + 1)} tool={tool} penOnly={penOnly} color={color} size={size} text={text} disabled={busy} onChange={next => updateMarks([...marks.filter(mark => mark.page !== index + 1), ...next])} onActive={setPage} onError={setError} onDrawingChange={onDrawingChange} onCrop={crop => void transformImage("crop", crop)} />)}
      </div>}
    </div>
    {!menuOpen && (error || saved) && <div className="editorNotifications">{feedback}</div>}
    <dialog ref={dialog} id="editor-menu" className="editorDrawer" aria-labelledby="editor-menu-title" onCancel={() => setMenuOpen(false)} onClose={() => setMenuOpen(false)} onClick={event => { if (event.target === event.currentTarget) { const box = event.currentTarget.getBoundingClientRect(); if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) setMenuOpen(false); } }}>
      <header><h1 id="editor-menu-title">문서 메뉴</h1><button autoFocus aria-label="편집 메뉴 닫기" onClick={() => setMenuOpen(false)}><X size={22} /></button></header>
      {pendingDraft && <section role="status"><p>이 기기에 저장하지 않은 편집 내용이 있습니다. 복구할까요?</p><button disabled={transforming} onClick={() => void restoreDraft()}>임시 편집 복구</button><button disabled={transforming} onClick={() => setPendingDraft(null)}>임시 편집 버리기</button></section>}
      <p role="status" className="editorHint">{draftStatus}</p>
      <p className="editorFilename">{file?.originalName}</p>
      {feedback}
      <button className="primaryButton" onClick={save} disabled={busy || !dirty}>{saving ? "저장 중..." : "저장"}</button>
      <p className="editorHint">같은 파일에 현재 편집을 저장합니다. PDF 원문은 보존되고 필기만 수정됩니다.</p>
      <fieldset className="editorToolbar" disabled={busy}>
        <legend>읽기·편집 도구</legend>
        <label><input type="checkbox" checked={penOnly} onChange={event => setPenOnly(event.target.checked)} />펜 전용 모드 (손 터치 차단)</label>
        <div className="editorTools">{(["read", "pen", "highlight", "erase", "text"] as const).map(value => <button key={value} aria-pressed={tool === value} onClick={() => chooseTool(value)}>{labels[value]}</button>)}</div>
        <label>색상<input aria-label="색상" type="color" value={color} onChange={event => setColor(event.target.value)} /></label>
        <label>{tool === "erase" ? "지우개 크기" : "굵기"}<input type="range" min="1" max="12" value={size} onChange={event => setSize(Number(event.target.value))} /></label>
        {tool === "text" && <><label>추가할 텍스트<textarea maxLength={1000} value={text} onChange={event => setText(event.target.value)} placeholder="입력 후 문서에서 위치를 눌러 주세요." /></label><button disabled={!text.trim()} onClick={() => setMenuOpen(false)}>문서에 텍스트 배치</button></>}
        <p className="editorHint">읽기 모드에서 손가락으로 스크롤·확대할 수 있습니다. 도구를 선택하면 문서에 필기합니다. 지우개는 추가한 필기만 부분적으로 지웁니다.</p>
        <div className="editorHistoryButtons" role="group" aria-label="편집 기록">{historyButtons}<span>되돌리기 / 다시 실행</span></div>
        {kind === "image" && <div className="editorImageTools">
          <button onClick={() => void transformImage("rotate")}>오른쪽 90° 회전</button><button aria-pressed={tool === "crop"} onClick={() => chooseTool("crop")}>영역 드래그로 자르기</button>
          <label>가로(px)<input type="number" min="1" max="16000" value={resize.width} onChange={event => setResize({ ...resize, width: Number(event.target.value) })} /></label>
          <label>세로(px)<input type="number" min="1" max="16000" value={resize.height} onChange={event => setResize({ ...resize, height: Number(event.target.value) })} /></label>
          <button onClick={() => void transformImage("resize")}>크기 적용</button>
          <button onClick={() => { if (window.confirm("이미지 편집을 모두 초기화할까요?")) {  setResize({ width: initialImage.current!.naturalWidth, height: initialImage.current!.naturalHeight }); dispatchHistory({ type: "edit", marks: { marks: [], bitmap: initialImage.current } }); setSaved(null); setRevision(value => value + 1); setTool("read"); setMenuOpen(false); } }}>원래 이미지로 초기화</button>
          <p className="editorHint">PNG로 저장합니다. 저장·변환 후 필기는 이미지에 합쳐지며, GIF는 정지 이미지가 됩니다.</p>
        </div>}
      </fieldset>
      <section className="editorNavigation" aria-label="보기 설정">
        <label>화면 맞춤·배율<select aria-label="확대" value={zoom} onChange={event => changeScale(event.target.value)}>
          <option value="auto">자동 맞춤</option><option value="page">페이지 맞춤 (위아래 전체)</option><option value="width">폭 맞춤</option>
          {[25, 50, 75, 100, 125, 150, 200].map(value => <option key={value} value={value}>{value}%</option>)}
        </select></label>
        <p className="editorHint">자동 맞춤은 PC에서 페이지 전체, 터치 기기에서 화면 폭에 맞춥니다. 배율은 화면 폭을 기준으로 합니다.</p>
        {kind === "pdf" && <form onSubmit={event => { event.preventDefault(); const target = Number(new FormData(event.currentTarget).get("page")); if (Number.isInteger(target) && target >= 1 && target <= pages) { shell.current?.querySelector(`[data-page="${target}"]`)?.scrollIntoView({ block: "start" }); setPage(target); setMenuOpen(false); } }}><label>페이지<input aria-label="페이지 번호" key={page} name="page" type="number" min="1" max={pages} defaultValue={page} required /> / {pages}</label><button>이동</button></form>}
        <button onClick={async () => { try { if (!shell.current?.requestFullscreen) throw new Error("이 브라우저에서는 탭 화면 크기로 표시됩니다."); if (document.fullscreenElement) await document.exitFullscreen(); else await shell.current.requestFullscreen(); setMenuOpen(false); } catch (error) { setError(message(error)); } }}>전체 화면 전환</button>
      </section>
      <details className="editorMarkList"><summary>{page}페이지 필기 관리 ({marks.filter(mark => mark.page === page).length})</summary>{marks.map((mark, index) => mark.page === page && <div key={index}><span>{mark.tool === "text" ? mark.text : labels[mark.tool]}</span>{mark.tool === "text" && <button disabled={busy} onClick={() => { const next = window.prompt("텍스트 수정", mark.text); if (next?.trim()) updateMarks(marks.map((item, i) => i === index ? { ...item, text: next.trim() } : item)); }}>텍스트 수정</button>}<button disabled={busy} onClick={() => updateMarks(marks.filter((_, i) => i !== index))}>삭제</button></div>)}</details>
      <Link className="editorHome" href="/" onClick={event => { if (dirty && !window.confirm("저장하지 않은 변경 사항이 있습니다. 나갈까요?")) event.preventDefault(); }}>파일 목록으로</Link>
    </dialog>
  </main>;
}
