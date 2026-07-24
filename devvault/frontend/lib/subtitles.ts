function decodeEntities(value: string) {
  return value
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, "\"")
    .replace(/&#39;/gi, "'");
}

function cueText(value: string) {
  return decodeEntities(value.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, ""))
    .replace(/\{\\[^}]+}/g, "")
    .split("\n")
    .map(line => line.trim())
    .join("\n")
    .trim();
}

const subtitleExtensions = new Set(["vtt", "srt", "smi", "sami"]);

function fileNameParts(name: string) {
  const match = name.toLowerCase().match(/^(.*)\.([^.]+)$/);
  return match ? { stem: match[1], extension: match[2] } : { stem: name.toLowerCase(), extension: "" };
}

export function findMatchingSubtitle<T extends { originalName: string; folderId: number | null }>(video: T, files: T[]) {
  const videoStem = fileNameParts(video.originalName).stem;
  return files.find(file => {
    const { stem, extension } = fileNameParts(file.originalName);
    return file.folderId === video.folderId && stem === videoStem && subtitleExtensions.has(extension);
  });
}

function timestamp(milliseconds: number) {
  const hours = Math.floor(milliseconds / 3_600_000);
  const minutes = Math.floor(milliseconds % 3_600_000 / 60_000);
  const seconds = Math.floor(milliseconds % 60_000 / 1_000);
  const millis = milliseconds % 1_000;
  return [hours, minutes, seconds].map(value => String(value).padStart(2, "0")).join(":")
    + `.${String(millis).padStart(3, "0")}`;
}

function srtTimestamp(value: string) {
  const match = value.trim().match(/^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})$/);
  if (!match) throw new Error("SRT 시간 형식이 올바르지 않습니다.");
  return `${match[1].padStart(2, "0")}:${match[2]}:${match[3]}.${match[4]}`;
}

function srtToVtt(source: string) {
  const cues = source.split(/\n{2,}/).flatMap(block => {
    const lines = block.split("\n").map(line => line.trimEnd());
    const timeIndex = lines.findIndex(line => line.includes("-->"));
    if (timeIndex < 0) return [];
    const times = lines[timeIndex].match(
      /(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})/
    );
    const text = cueText(lines.slice(timeIndex + 1).join("\n"));
    if (!times || !text) return [];
    return [`${srtTimestamp(times[1])} --> ${srtTimestamp(times[2])}\n${text}`];
  });
  if (!cues.length) throw new Error("SRT 자막 구간을 찾을 수 없습니다.");
  return `WEBVTT\n\n${cues.join("\n\n")}\n`;
}

function smiText(value: string) {
  const paragraphs = [...value.matchAll(/<p\b([^>]*)>([\s\S]*?)(?=<p\b|$)/gi)];
  if (!paragraphs.length) return cueText(value);
  const korean = paragraphs.find(match => /\bclass\s*=\s*["']?(?:krcc|kor)/i.test(match[1]));
  return cueText((korean ?? paragraphs[0])[2]);
}

function smiToVtt(source: string) {
  const syncs = [...source.matchAll(/<sync\b[^>]*\bstart\s*=\s*["']?(\d+)["']?[^>]*>([\s\S]*?)(?=<sync\b|$)/gi)]
    .map(match => ({ start: Number(match[1]), text: smiText(match[2]) }));
  const cues = syncs.flatMap((sync, index) => {
    if (!sync.text) return [];
    const nextStart = syncs[index + 1]?.start ?? sync.start + 3_000;
    return [`${timestamp(sync.start)} --> ${timestamp(Math.max(sync.start + 1, nextStart))}\n${sync.text}`];
  });
  if (!cues.length) throw new Error("SMI 자막 구간을 찾을 수 없습니다.");
  return `WEBVTT\n\n${cues.join("\n\n")}\n`;
}

export function subtitleToVtt(fileName: string, text: string) {
  const source = text.replace(/^\uFEFF/, "").replace(/\r/g, "").trim();
  if (source.startsWith("WEBVTT")) return `${source}\n`;
  const extension = fileName.toLowerCase().split(".").pop();
  if (extension === "smi" || extension === "sami" || /<sync\b/i.test(source)) {
    return smiToVtt(source);
  }
  return srtToVtt(source);
}

export async function decodeSubtitleFile(file: File) {
  const bytes = await file.arrayBuffer();
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    text = new TextDecoder("euc-kr").decode(bytes);
  }
  return subtitleToVtt(file.name, text);
}
