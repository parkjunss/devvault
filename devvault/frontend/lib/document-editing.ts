import { PDFDocument, degrees } from "pdf-lib";

export type Point = { x: number; y: number };
export type Mark = { page: number; tool: "pen" | "highlight" | "text" | "erase"; color: string; width: number; points: Point[]; text?: string };
export const SOURCE_ATTACHMENT = "devvault-original-v1.pdf";
export const MARKS_ATTACHMENT = "devvault-marks-v1.json";
export const MAX_EDIT_BYTES = 50 * 1024 * 1024;

export type MarkHistory<T = Mark[]> = { past: T[]; present: T; future: T[] };
export type MarkHistoryAction<T = Mark[]> = { type: "edit" | "reset"; marks: T } | { type: "undo" | "redo" } | { type: "update"; update: (current: T) => T };

export function changeMarkHistory<T>(state: MarkHistory<T>, action: MarkHistoryAction<T>): MarkHistory<T> {
  if (action.type === "reset") return { past: [], present: action.marks, future: [] };
  if (action.type === "edit" || action.type === "update") {
    const next = action.type === "update" ? action.update(state.present) : action.marks;
    if (next === state.present) return state;
    // ponytail: retain 100 session-local edits with shared immutable strokes; persist history only if cross-session undo is needed.
    return { past: [...state.past.slice(-99), state.present], present: next, future: [] };
  }
  if (action.type === "undo" && state.past.length) return {
    past: state.past.slice(0, -1), present: state.past[state.past.length - 1], future: [state.present, ...state.future]
  };
  if (action.type === "redo" && state.future.length) return {
    past: [...state.past, state.present], present: state.future[0], future: state.future.slice(1)
  };
  return state;
}

export function readMarks(raw: string, pageCount: number): Mark[] {
  const marks: unknown = JSON.parse(raw);
  let points = 0;
  if (!Array.isArray(marks) || marks.length > 10000) throw new Error("필기 데이터가 올바르지 않습니다.");
  for (const mark of marks) {
    if (!mark || !Number.isInteger(mark.page) || mark.page < 1 || mark.page > pageCount
      || !["pen", "highlight", "text", "erase"].includes(mark.tool) || !/^#[0-9a-f]{6}$/i.test(mark.color)
      || !Number.isFinite(mark.width) || mark.width < 0.0001 || mark.width > 0.2
      || !Array.isArray(mark.points) || !mark.points.length
      || (mark.tool === "text" && (typeof mark.text !== "string" || mark.text.length > 1000))
      || mark.points.some((p: Point) => !p || !Number.isFinite(p.x) || !Number.isFinite(p.y) || p.x < 0 || p.x > 1 || p.y < 0 || p.y > 1)) {
      throw new Error("필기 데이터가 올바르지 않습니다.");
    }
    points += mark.points.length;
    if (points > 200000) throw new Error("필기 데이터가 너무 큽니다.");
  }
  return marks as Mark[];
}

export function drawMarks(context: CanvasRenderingContext2D, marks: Mark[], width: number, height: number) {
  for (const mark of marks) {
    context.save();
    context.strokeStyle = context.fillStyle = mark.color;
    context.lineWidth = Math.max(1, mark.width * width);
    context.lineCap = context.lineJoin = "round";
    context.globalAlpha = mark.tool === "highlight" ? 0.3 : 1;
    if (mark.tool === "erase") context.globalCompositeOperation = "destination-out";
    const first = mark.points[0];
    if (mark.tool === "text") {
      context.font = `${mark.width * width}px "Noto Sans KR Variable", sans-serif`;
      context.textBaseline = "top";
      (mark.text ?? "").split("\n").forEach((line, index) => context.fillText(line, first.x * width, first.y * height + index * mark.width * width * 1.3));
    } else {
      context.beginPath();
      context.moveTo(first.x * width, first.y * height);
      for (const point of mark.points) context.lineTo(point.x * width, point.y * height);
      if (mark.points.length === 1) context.lineTo(first.x * width + 0.01, first.y * height);
      context.stroke();
    }
    context.restore();
  }
}

export async function createPdfCopy(source: Uint8Array, marks: Mark[], overlay: (marks: Mark[], width: number, height: number) => Promise<Uint8Array>) {
  const document = await PDFDocument.load(source);
  readMarks(JSON.stringify(marks), document.getPageCount());
  for (const [index, page] of document.getPages().entries()) {
    const pageMarks = marks.filter(mark => mark.page === index + 1);
    if (!pageMarks.length) continue;
    const crop = page.getCropBox();
    const angle = ((page.getRotation().angle % 360) + 360) % 360;
    const width = angle % 180 ? crop.height : crop.width;
    const height = angle % 180 ? crop.width : crop.height;
    const png = await document.embedPng(await overlay(pageMarks, width, height));
    page.drawImage(png, {
      x: crop.x + (angle === 90 || angle === 180 ? crop.width : 0),
      y: crop.y + (angle === 180 || angle === 270 ? crop.height : 0),
      width, height, rotate: degrees(angle)
    });
  }
  // Keep a single unmodified source, rather than nesting prior edited copies on each save.
  await document.attach(source, SOURCE_ATTACHMENT, { mimeType: "application/pdf" });
  await document.attach(new TextEncoder().encode(JSON.stringify(marks)), MARKS_ATTACHMENT, { mimeType: "application/json" });
  return document.save();
}
