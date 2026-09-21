"use client";

import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from "react";
import type { PDFDocumentProxy, PDFPageProxy } from "pdfjs-dist";
import { drawMarks, type Mark, type Point } from "@/lib/document-editing";

export type EditorTool = Mark["tool"] | "read" | "crop";
export type Crop = { x: number; y: number; width: number; height: number };

type Props = {
  document: PDFDocumentProxy | null;
  image: HTMLImageElement | null;
  page: number;
  marks: Mark[];
  tool: EditorTool;
  penOnly: boolean;
  color: string;
  size: number;
  text: string;
  disabled: boolean;
  onChange: (marks: Mark[]) => void;
  onActive: (page: number) => void;
  onError: (message: string) => void;
  onCrop: (crop: Crop) => void;
  onDrawingChange: (drawing: boolean) => void;
};

export function EditorPage({ document: pdf, image, page, marks, tool, penOnly, color, size, text, disabled, onChange, onActive, onError, onCrop, onDrawingChange }: Props) {
  const paper = useRef<HTMLElement>(null);
  const background = useRef<HTMLCanvasElement>(null);
  const foreground = useRef<HTMLCanvasElement>(null);
  const draft = useRef<Mark | null>(null);
  const strokeCanvas = useRef<HTMLCanvasElement | null>(null);
  const baseCanvas = useRef<HTMLCanvasElement | null>(null);
  const paintedMarks = useRef<Mark[] | null>(null);
  const frame = useRef<number | null>(null);
  const cropStart = useRef<Point | null>(null);
  const pointer = useRef<number | null>(null);
  const [pdfPage, setPdfPage] = useState<PDFPageProxy | null>(null);
  const [dimensions, setDimensions] = useState({ width: image?.naturalWidth ?? 595, height: image?.naturalHeight ?? 842 });
  const [nearby, setNearby] = useState(false);
  const [displayWidth, setDisplayWidth] = useState(0);
  const [rendered, setRendered] = useState(false);

  useEffect(() => () => { if (pointer.current !== null) onDrawingChange(false); if (frame.current !== null) cancelAnimationFrame(frame.current); }, [onDrawingChange]);

  useEffect(() => {
    if (!pdf) return;
    let cancelled = false;
    pdf.getPage(page).then(value => {
      if (cancelled) return;
      const viewport = value.getViewport({ scale: 1 });
      setDimensions({ width: viewport.width, height: viewport.height });
      setPdfPage(value);
    }).catch(error => { if (!cancelled) onError(`페이지를 열 수 없습니다: ${error.message}`); });
    return () => { cancelled = true; };
  }, [pdf, page, onError]);

  useEffect(() => {
    const element = paper.current!;
    const resize = new ResizeObserver(entries => setDisplayWidth(Math.round(entries[0].contentRect.width)));
    const visibility = new IntersectionObserver(entries => setNearby(entries[0].isIntersecting), { rootMargin: "900px 0px" });
    const active = new IntersectionObserver(entries => { if (entries[0].isIntersecting) onActive(page); }, { rootMargin: "-20% 0px -70% 0px" });
    resize.observe(element);
    visibility.observe(element);
    active.observe(element);
    return () => { resize.disconnect(); visibility.disconnect(); active.disconnect(); };
  }, [onActive, page]);

  useEffect(() => {
    const canvas = background.current!, ink = foreground.current!;
    let cancelled = false;
    let task: { cancel: () => void; promise: Promise<void> } | undefined;
    async function render() {
      setRendered(false);
      if (!nearby || !displayWidth || (!pdfPage && !image)) return;
      const width = image?.naturalWidth ?? dimensions.width;
      const height = image?.naturalHeight ?? dimensions.height;
      const scale = Math.min(displayWidth * Math.min(window.devicePixelRatio, 2) / width, 2400 / Math.max(width, height));
      const buffer = window.document.createElement("canvas");
      buffer.width = Math.max(1, Math.ceil(width * scale));
      buffer.height = Math.max(1, Math.ceil(height * scale));
      if (pdfPage) {
        task = pdfPage.render({ canvas: buffer, viewport: pdfPage.getViewport({ scale }) });
        await task.promise;
      } else buffer.getContext("2d")!.drawImage(image!, 0, 0, buffer.width, buffer.height);
      if (cancelled) return;
      paintedMarks.current = null;
      canvas.width = ink.width = buffer.width;
      canvas.height = ink.height = buffer.height;
      canvas.getContext("2d")!.drawImage(buffer, 0, 0);
      setRendered(true);
    }
    render().catch(error => { if (!cancelled) onError(`페이지를 그릴 수 없습니다: ${error.message}`); });
    return () => {
      cancelled = true;
      task?.cancel();
      // Release off-screen pixel buffers; page dimensions keep the continuous scroll position stable.
      canvas.width = canvas.height = ink.width = ink.height = 1;
      baseCanvas.current = strokeCanvas.current = null;
      if (task) void task.promise.catch(() => undefined).then(() => pdfPage?.cleanup());
    };
  }, [nearby, displayWidth, pdfPage, image, dimensions, onError]);

  useEffect(() => {
    if (!rendered) return;
    if (paintedMarks.current?.length === marks.length && marks.every((mark, i) => mark === paintedMarks.current![i])) return;
    paintedMarks.current = marks;
    const canvas = foreground.current!, context = canvas.getContext("2d")!;
    context.clearRect(0, 0, canvas.width, canvas.height);
    drawMarks(context, marks, canvas.width, canvas.height);
  }, [marks, rendered]);

  function position(event: { clientX: number; clientY: number; currentTarget?: EventTarget | null }): Point {
    const box = foreground.current!.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)), y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height)) };
  }
  function repaint() {
    const canvas = foreground.current!, context = canvas.getContext("2d")!;
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (baseCanvas.current) context.drawImage(baseCanvas.current, 0, 0);
    if (draft.current && strokeCanvas.current) {
      context.save();
      context.globalAlpha = draft.current.tool === "highlight" ? .3 : 1;
      if (draft.current.tool === "erase") context.globalCompositeOperation = "destination-out";
      context.drawImage(strokeCanvas.current, 0, 0);
      context.restore();
    }
  }
  function schedulePaint() {
    if (frame.current !== null) return;
    frame.current = requestAnimationFrame(() => { frame.current = null; repaint(); });
  }
  function appendPoint(point: Point) {
    const mark = draft.current;
    if (!mark || mark.points.length >= 10000) return;
    const last = mark.points[mark.points.length - 1];
    if (last.x === point.x && last.y === point.y) return;
    mark.points.push(point);
    const canvas = strokeCanvas.current!;
    drawMarks(canvas.getContext("2d")!, [{ ...mark, tool: "pen", points: [last, point] }], canvas.width, canvas.height);
  }
  function cancel(event: PointerEvent<HTMLCanvasElement>) {
    if (event.pointerId !== pointer.current) return;
    if (frame.current !== null) { cancelAnimationFrame(frame.current); frame.current = null; }
    pointer.current = null;
    onDrawingChange(false);
    draft.current = null;
    cropStart.current = null;
    repaint();
  }
  function pointerDown(event: PointerEvent<HTMLCanvasElement>) {
    if (disabled || !rendered || tool === "read" || event.button !== 0) return;
    event.preventDefault();
    if (penOnly && event.pointerType !== "pen") return;
    if (pointer.current !== null || (!event.isPrimary && event.pointerType !== "pen")) return;
    onActive(page);
    if (tool === "text" && !text.trim()) { onError("메뉴에서 추가할 텍스트를 입력해 주세요."); return; }
    pointer.current = event.pointerId;
    onDrawingChange(true);
    event.currentTarget.setPointerCapture(event.pointerId);
    const canvas = foreground.current!;
    const base = baseCanvas.current ?? window.document.createElement("canvas");
    if (base.width !== canvas.width || base.height !== canvas.height) { base.setAttribute("width", String(canvas.width)); base.setAttribute("height", String(canvas.height)); }
    base.getContext("2d")!.clearRect(0, 0, base.width, base.height);
    base.getContext("2d")!.drawImage(canvas, 0, 0);
    baseCanvas.current = base;
    const point = position(event);
    if (tool === "crop") { cropStart.current = point; return; }
    const mark: Mark = { page, tool, color, width: (tool === "text" ? size * 5 + 12 : tool === "highlight" || tool === "erase" ? size * 4 + 10 : size) / 1000, points: [point], ...(tool === "text" ? { text: text.trim() } : {}) };
    if (tool === "text") onChange([...marks, mark]);
    else {
      draft.current = mark;
      const stroke = strokeCanvas.current ?? window.document.createElement("canvas");
      if (stroke.width !== canvas.width || stroke.height !== canvas.height) { stroke.setAttribute("width", String(canvas.width)); stroke.setAttribute("height", String(canvas.height)); }
      stroke.getContext("2d")!.clearRect(0, 0, stroke.width, stroke.height);
      strokeCanvas.current = stroke;
      drawMarks(stroke.getContext("2d")!, [{ ...mark, tool: "pen" }], stroke.width, stroke.height);
      repaint();
    }
  }
  function pointerMove(event: PointerEvent<HTMLCanvasElement>) {
    if (event.pointerId !== pointer.current) return;
    event.preventDefault();
    if (draft.current) {
      const samples = event.nativeEvent.getCoalescedEvents?.() ?? [];
      for (const sample of samples.length ? samples : [event.nativeEvent]) appendPoint(position(sample));
      schedulePaint();
    }
    if (cropStart.current) {
      repaint();
      const point = position(event), start = cropStart.current, canvas = foreground.current!;
      const context = canvas.getContext("2d")!;
      context.strokeStyle = "#2459cc";
      context.lineWidth = 3;
      context.strokeRect(start.x * canvas.width, start.y * canvas.height, (point.x - start.x) * canvas.width, (point.y - start.y) * canvas.height);
    }
  }
  function pointerUp(event: PointerEvent<HTMLCanvasElement>) {
    if (event.pointerId !== pointer.current) return;
    event.preventDefault();
    if (frame.current !== null) { cancelAnimationFrame(frame.current); frame.current = null; }
    if (draft.current) {
      appendPoint(position(event));
      repaint();
      const next = [...marks, draft.current];
      paintedMarks.current = next;
      draft.current = null;
      onChange(next);
    }
    if (cropStart.current) {
      const start = cropStart.current, end = position(event);
      cropStart.current = null;
      const width = image!.naturalWidth, height = image!.naturalHeight;
      const crop = { x: Math.floor(Math.min(start.x, end.x) * width), y: Math.floor(Math.min(start.y, end.y) * height), width: Math.round(Math.abs(start.x - end.x) * width), height: Math.round(Math.abs(start.y - end.y) * height) };
      if (crop.width > 1 && crop.height > 1) onCrop(crop);
      repaint();
    }
    pointer.current = null;
    onDrawingChange(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }

  return <article ref={paper} className="editorPaper" data-page={page} data-rendered={rendered} aria-label={`${page}페이지`} style={{ aspectRatio: `${image?.naturalWidth ?? dimensions.width} / ${image?.naturalHeight ?? dimensions.height}`, "--page-ratio": (image?.naturalWidth ?? dimensions.width) / (image?.naturalHeight ?? dimensions.height) } as CSSProperties}>
    {!rendered && <span className="editorPageLoading">{page}페이지</span>}
    <canvas ref={background} className="editorOriginal" aria-label={pdf ? `${page}페이지 원문` : "원본 이미지"} />
    <canvas ref={foreground} className="editorInk" data-tool={tool} data-pen-only={penOnly} aria-label={`${page}페이지 필기 영역`} onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={cancel} onLostPointerCapture={cancel} onContextMenu={event => event.preventDefault()} onDragStart={event => event.preventDefault()} />
  </article>;
}
