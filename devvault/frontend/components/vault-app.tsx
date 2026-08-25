"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowsIn, ArrowsOut, Bell, CaretDown, Check, ClockCounterClockwise, DotsThree, DownloadSimple, FilmStrip,
  File as FileIcon, FileCode, FileImage, FilePdf, FileText, Folder as FolderIcon,
  GridFour, List, MagnifyingGlass, MusicNotes, PencilSimple, Plus, ShareNetwork, ShieldCheck, SignOut,
  SlidersHorizontal, Star, Trash, UploadSimple, UserCircle, Users, X
} from "@phosphor-icons/react";
import { ChangeEvent, DragEvent, FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { VideoPlayer } from "@/components/video-player";
import { apiFetch, apiJson, apiUpload } from "@/lib/api";
import { clearTokens, getAccessToken, getRefreshToken, tokenHasRole } from "@/lib/auth";
import { decodeSubtitleFile, findMatchingSubtitle } from "@/lib/subtitles";
import type { Folder, PageResponse, Tag, VaultFile } from "@/lib/types";

type Nav = "all" | "favorite" | "recent" | "shared" | "trash";
type View = "list" | "grid";
type Dashboard = { fileCount: number; usedBytes: number; quotaBytes: number };
type UserProfile = { username: string; hasProfileImage: boolean };
type PlaybackUrlResponse = { url: string };
type DownloadTicketResponse = { url: string };
type ActionTarget = { type: "file"; item: VaultFile } | { type: "folder"; item: Folder };
type ActionDialog = { action: "rename" | "delete"; target: ActionTarget; value: string; permanent: boolean };

const navItems: { id: Nav; label: string; icon: typeof FolderIcon }[] = [
  { id: "all", label: "파일", icon: FolderIcon },
  { id: "favorite", label: "즐겨찾기", icon: Star },
  { id: "recent", label: "최근 항목", icon: ClockCounterClockwise },
  { id: "shared", label: "공유", icon: Users },
  { id: "trash", label: "휴지통", icon: Trash }
];

function fileType(file: VaultFile) {
  const name = file.originalName.toLowerCase();
  if (file.contentType === "application/pdf" || name.endsWith(".pdf")) return "pdf";
  if (file.contentType?.startsWith("image/")) return "image";
  if (file.contentType?.startsWith("audio/")) return "audio";
  if (file.contentType?.startsWith("video/")) return "video";
  if (/\.(md|ts|tsx|js|jsx|java|py|sql|json|yml|yaml)$/.test(name)) return "code";
  if (file.contentType?.startsWith("text/") || name.endsWith(".txt")) return "text";
  return "file";
}

function FileGlyph({ file, size = 22 }: { file: VaultFile; size?: number }) {
  const type = fileType(file);
  if (type === "pdf") return <FilePdf size={size} weight="duotone" className="typePdf" />;
  if (type === "image") return <FileImage size={size} weight="duotone" className="typeImage" />;
  if (type === "audio") return <MusicNotes size={size} weight="duotone" className="typeAudio" />;
  if (type === "video") return <FilmStrip size={size} weight="duotone" className="typeVideo" />;
  if (type === "code") return <FileCode size={size} weight="duotone" className="typeCode" />;
  if (type === "text") return <FileText size={size} weight="duotone" className="typeText" />;
  return <FileIcon size={size} weight="duotone" />;
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1_048_576) return `${Math.round(bytes / 1024)}KB`;
  if (bytes < 1_073_741_824) return `${(bytes / 1_048_576).toFixed(1)}MB`;
  if (bytes < 1_099_511_627_776) return `${(bytes / 1_073_741_824).toFixed(1)}GB`;
  return `${(bytes / 1_099_511_627_776).toFixed(1)}TB`;
}

function formatStorage(bytes: number) {
  if (bytes < 1_000) return `${bytes}B`;
  if (bytes < 1_000_000) return `${(bytes / 1_000).toFixed(bytes < 10_000 ? 1 : 0)}KB`;
  if (bytes < 1_000_000_000) return `${(bytes / 1_000_000).toFixed(1)}MB`;
  return `${(bytes / 1_000_000_000).toFixed(1)}GB`;
}

function formatDate(value: string) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "numeric", minute: "2-digit" }).format(new Date(value));
}

function RecentThumbnail({ file }: { file: VaultFile }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (fileType(file) !== "image") return;
    let objectUrl: string | null = null;
    let cancelled = false;
    apiFetch(`/api/files/${file.id}/preview`)
      .then(response => response.ok ? response.blob() : Promise.reject())
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [file]);

  return url
    ? <Image unoptimized src={url} alt="" width={520} height={300} />
    : <span className="recentPlaceholder"><FileGlyph file={file} size={48} /></span>;
}

export function VaultApp() {
  const router = useRouter();
  const searchRef = useRef<HTMLInputElement>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const [nav, setNav] = useState<Nav>("all");
  const [view, setView] = useState<View>("list");
  const [query, setQuery] = useState("");
  const [files, setFiles] = useState<VaultFile[]>([]);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [tableFolders, setTableFolders] = useState<Folder[]>([]);
  const [currentFolder, setCurrentFolder] = useState<Folder | null>(null);
  const [folderTrail, setFolderTrail] = useState<Folder[]>([]);
  const [dashboard, setDashboard] = useState<Dashboard>({ fileCount: 0, usedBytes: 0, quotaBytes: 50_000_000_000 });
  const [selected, setSelected] = useState<VaultFile | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewText, setPreviewText] = useState<string | null>(null);
  const [previewExpanded, setPreviewExpanded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [uploadProgress, setUploadProgress] = useState<{ name: string; loaded: number; total: number; current: number; count: number } | null>(null);
  // ponytail: upload failures are session-local; persist server-side if notification history becomes necessary.
  const [uploadFailure, setUploadFailure] = useState<string | null>(null);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [isAdmin] = useState(() => tokenHasRole("ROLE_ADMIN"));
  const [openFileMenuId, setOpenFileMenuId] = useState<number | null>(null);
  const [openFolderMenuId, setOpenFolderMenuId] = useState<number | null>(null);
  const [filterOpen, setFilterOpen] = useState(false);
  const [filterExtension, setFilterExtension] = useState("");
  const [filterTag, setFilterTag] = useState("");
  const [availableTags, setAvailableTags] = useState<Tag[]>([]);
  const [tagName, setTagName] = useState("");
  const [tagPending, setTagPending] = useState(false);
  const [downloadPending, setDownloadPending] = useState(false);
  const [profileName, setProfileName] = useState("");
  const [profileImageUrl, setProfileImageUrl] = useState<string | null>(null);
  const [subtitle, setSubtitle] = useState<{ fileId: number; url: string; name: string } | null>(null);
  const [actionDialog, setActionDialog] = useState<ActionDialog | null>(null);
  const [actionPending, setActionPending] = useState(false);
  const [draggedFileId, setDraggedFileId] = useState<number | null>(null);
  const [dropFolderId, setDropFolderId] = useState<number | null>(null);

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    apiJson<UserProfile>("/api/users/profile").then(async profile => {
      if (cancelled) return;
      setProfileName(profile.username);
      if (!profile.hasProfileImage) return;
      const response = await apiFetch("/api/users/profile/image");
      if (!response.ok || cancelled) return;
      objectUrl = URL.createObjectURL(await response.blob());
      setProfileImageUrl(objectUrl);
    }).catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, []);

  useEffect(() => {
    apiJson<Tag[]>("/api/tags").then(setAvailableTags).catch(() => undefined);
  }, []);

  useEffect(() => () => {
    if (subtitle) URL.revokeObjectURL(subtitle.url);
  }, [subtitle]);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    setSelectedIds(new Set());
    try {
      if (!getAccessToken()) return router.replace("/login");
      if (nav === "shared") {
        setFiles([]);
        return;
      }
      const params = new URLSearchParams({ size: "50" });
      if (query) params.set("name", query);
      if (filterExtension.trim()) params.set("extension", filterExtension.trim().replace(/^\./, ""));
      if (filterTag.trim()) params.set("tag", filterTag.trim());
      if (nav === "favorite") params.set("favorite", "true");
      if (nav === "all" && !currentFolder && !query) params.set("rootOnly", "true");
      const filePath = nav === "trash" ? `/api/files/trash?${params}` : `/api/files?${params}`;
      const [rootFolders, result, summary] = await Promise.all([
        apiJson<Folder[]>("/api/folders"),
        currentFolder && nav === "all"
          ? apiJson<{ folders: Folder[]; files: PageResponse<VaultFile> }>(`/api/folders/${currentFolder.id}/children?${params}`)
          : apiJson<PageResponse<VaultFile>>(filePath),
        apiJson<Dashboard>("/api/dashboard")
      ]);
      setFolders(rootFolders);
      setTableFolders(nav === "all" ? ("folders" in result ? result.folders : rootFolders) : []);
      const rawFiles = "files" in result ? result.files.content : result.content;
      const nextFiles = rawFiles.map(file => ({
        ...file,
        contentType: file.contentType ?? null,
        checksum: file.checksum ?? "",
        favorite: file.favorite ?? false,
        createdAt: file.createdAt || (file as VaultFile & { deletedAt?: string }).deletedAt || ""
      }));
      setFiles(nextFiles);
      setDashboard(summary);
      setSelected(current => current ?? nextFiles[0] ?? null);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "파일을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, [currentFolder, filterExtension, filterTag, nav, query, router]);

  useEffect(() => {
    const timer = window.setTimeout(load, 220);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, []);

  useEffect(() => {
    const closePopovers = () => {
      setOpenFileMenuId(null);
      setOpenFolderMenuId(null);
      setNotificationOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      closePopovers();
      setFilterOpen(false);
      setPreviewExpanded(false);
      setActionDialog(null);
    };
    window.addEventListener("click", closePopovers);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("click", closePopovers);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, []);

  const selectedId = selected?.id;

  useEffect(() => {
    if (!selectedId) return;
    let objectUrl: string | null = null;
    let cancelled = false;
    apiJson<VaultFile>(`/api/files/${selectedId}`).then(async detail => {
      if (fileType(detail) === "video" || fileType(detail) === "pdf") {
        const playback = await apiJson<PlaybackUrlResponse>(`/api/files/${selectedId}/playback-url`, {
          method: "POST"
        });
        if (cancelled) return;
        setSelected(detail);
        setPreviewUrl(playback.url);
        return;
      }
      const response = await apiFetch(`/api/files/${selectedId}/preview`);
      if (!response.ok) throw new Error("미리보기를 열 수 없습니다.");
      const blob = await response.blob();
      if (cancelled) return;
      setSelected(detail);
      if (fileType(detail) === "text" || fileType(detail) === "code") {
        setPreviewText(await blob.text());
        setPreviewUrl(null);
      } else {
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
      }
    }).catch(() => setPreviewUrl(null));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) return;
    const video = files.find(file => file.id === selectedId);
    if (!video || fileType(video) !== "video") return;
    const matchingSubtitle = findMatchingSubtitle(video, files);
    if (!matchingSubtitle) return;

    let cancelled = false;
    apiFetch(`/api/files/${matchingSubtitle.id}/download`)
      .then(response => response.ok ? response.blob() : Promise.reject())
      .then(async blob => {
        const vtt = await decodeSubtitleFile(new File([blob], matchingSubtitle.originalName));
        if (cancelled) return;
        setSubtitle({
          fileId: selectedId,
          url: URL.createObjectURL(new Blob([vtt], { type: "text/vtt;charset=utf-8" })),
          name: matchingSubtitle.originalName
        });
      })
      .catch(() => undefined);

    return () => {
      cancelled = true;
    };
  }, [files, selectedId]);

  const recent = useMemo(() => files.slice().sort((a, b) => +new Date(b.createdAt) - +new Date(a.createdAt)).slice(0, 3), [files]);
  const usedPercent = Math.min(100, Math.max(0, (dashboard.usedBytes / dashboard.quotaBytes) * 100));
  const storageAlert = usedPercent >= 80
    ? {
        title: usedPercent >= 100 ? "저장소가 가득 찼습니다." : usedPercent >= 95 ? "저장소가 거의 가득 찼습니다." : "저장소 사용량이 높습니다.",
        detail: `${formatStorage(dashboard.usedBytes)} / ${formatStorage(dashboard.quotaBytes)} (${usedPercent.toFixed(1)}%)`
      }
    : null;
  const notificationCount = Number(!!storageAlert) + Number(!!uploadFailure);
  const uploadPercent = uploadProgress
    ? Math.min(100, Math.round((uploadProgress.loaded / Math.max(uploadProgress.total, 1)) * 100))
    : 0;
  const user = profileName || "사용자";
  const subtitleUrl = subtitle && subtitle.fileId === selectedId ? subtitle.url : null;
  const canSelectFiles = nav !== "trash" && nav !== "shared";
  const selectableFiles = canSelectFiles ? files.slice(0, 100) : [];
  const selectedVisibleCount = selectableFiles.filter(file => selectedIds.has(file.id)).length;
  const allVisibleSelected = selectableFiles.length > 0 && selectedVisibleCount === selectableFiles.length;

  function notify(message: string, duration = 2600) {
    setToast(message);
    window.setTimeout(() => setToast(""), duration);
  }

  function openFile(file: VaultFile) {
    setPreviewText(null);
    setPreviewUrl(null);
    setSelected(file);
  }

  function toggleFileSelection(fileId: number) {
    setSelectedIds(current => {
      const next = new Set(current);
      if (next.has(fileId)) next.delete(fileId);
      else if (next.size < 100) next.add(fileId);
      return next;
    });
  }

  function toggleVisibleFiles() {
    setSelectedIds(allVisibleSelected ? new Set() : new Set(selectableFiles.map(file => file.id)));
  }

  function startFileDrag(event: DragEvent<HTMLElement>, file: VaultFile) {
    setDraggedFileId(file.id);
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", String(file.id));
  }

  async function dropFile(event: DragEvent<HTMLElement>, folderId: number) {
    event.preventDefault();
    event.stopPropagation();
    const fileId = draggedFileId ?? Number(event.dataTransfer.getData("text/plain"));
    const file = files.find(candidate => candidate.id === fileId);
    setDraggedFileId(null);
    setDropFolderId(null);
    if (!file || file.folderId === folderId) return;

    try {
      const response = await apiFetch(`/api/files/${file.id}`, {
        method: "PATCH",
        body: JSON.stringify({ folderId })
      });
      if (!response.ok) throw new Error();
      setSelected(null);
      await load();
      notify(`${file.originalName} 파일을 이동했습니다.`);
    } catch {
      notify("파일을 이동하지 못했습니다.");
    }
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    if (!files.length) return;
    const totalBytes = files.reduce((total, file) => total + file.size, 0);
    const failedFiles: string[] = [];
    let completedBytes = 0;
    let uploadedCount = 0;
    // ponytail: sequential uploads reuse the existing endpoint and bound server load; add limited concurrency only if measured.
    try {
      for (const [index, file] of files.entries()) {
        const body = new FormData();
        body.append("file", file);
        if (currentFolder) body.append("folderId", String(currentFolder.id));
        setUploadProgress({ name: file.name, loaded: completedBytes, total: totalBytes, current: index + 1, count: files.length });
        try {
          const response = await apiUpload("/api/files", body, (loaded, total) => {
            const fileLoaded = total > 0 ? file.size * Math.min(1, loaded / total) : Math.min(file.size, loaded);
            setUploadProgress({ name: file.name, loaded: completedBytes + fileLoaded, total: totalBytes, current: index + 1, count: files.length });
          });
          if (!response.ok) throw new Error();
          uploadedCount++;
        } catch {
          failedFiles.push(file.name);
        }
        completedBytes += file.size;
      }
      if (failedFiles.length) {
        setUploadFailure(`${failedFiles.length}개 실패: ${failedFiles.slice(0, 3).join(", ")}${failedFiles.length > 3 ? " 외" : ""}`);
        notify(`${uploadedCount}개 업로드, ${failedFiles.length}개 실패`);
      } else {
        setUploadFailure(null);
        notify(`${uploadedCount}개 파일을 업로드했습니다.`);
      }
      await load();
    } finally {
      setUploadProgress(null);
      event.target.value = "";
    }
  }

  async function toggleFavorite(file: VaultFile) {
    await apiFetch(`/api/files/${file.id}/favorite`, { method: file.favorite ? "DELETE" : "POST" });
    setFiles(items => items.map(item => item.id === file.id ? { ...item, favorite: !item.favorite } : item));
    if (selected?.id === file.id) setSelected({ ...selected, favorite: !selected.favorite });
  }

  function renameFile(file: VaultFile) {
    setOpenFileMenuId(null);
    setActionDialog({ action: "rename", target: { type: "file", item: file }, value: file.originalName, permanent: false });
  }

  function deleteFile(file: VaultFile) {
    setOpenFileMenuId(null);
    setActionDialog({ action: "delete", target: { type: "file", item: file }, value: "", permanent: nav === "trash" });
  }

  async function restoreFile(file: VaultFile) {
    try {
      const response = await apiFetch(`/api/files/${file.id}/restore`, { method: "POST" });
      if (!response.ok) throw new Error();
      setOpenFileMenuId(null);
      if (selected?.id === file.id) setSelected(null);
      await load();
      notify("파일을 복원했습니다.");
    } catch {
      notify("파일을 복원하지 못했습니다.");
    }
  }

  async function createFolder() {
    const name = window.prompt("새 폴더 이름을 입력하세요.")?.trim();
    if (!name) return;
    try {
      const response = await apiFetch("/api/folders", {
        method: "POST",
        body: JSON.stringify({ name, parentId: currentFolder?.id ?? null })
      });
      if (!response.ok) throw new Error();
      await load();
      notify("폴더를 만들었습니다.");
    } catch {
      notify("폴더를 만들지 못했습니다.");
    }
  }

  function openRootFolder(folder: Folder) {
    setNav("all");
    setCurrentFolder(folder);
    setFolderTrail([folder]);
    setSelected(null);
    setSelectedIds(new Set());
  }

  function openChildFolder(folder: Folder) {
    setCurrentFolder(folder);
    setFolderTrail(trail => [...trail, folder]);
    setSelected(null);
    setSelectedIds(new Set());
  }

  function openFolderTrail(index: number) {
    if (index < 0) {
      setCurrentFolder(null);
      setFolderTrail([]);
    } else {
      setCurrentFolder(folderTrail[index]);
      setFolderTrail(folderTrail.slice(0, index + 1));
    }
    setSelected(null);
    setSelectedIds(new Set());
  }

  function renameFolder(folder: Folder) {
    setOpenFolderMenuId(null);
    setActionDialog({ action: "rename", target: { type: "folder", item: folder }, value: folder.name, permanent: false });
  }

  function deleteFolder(folder: Folder) {
    setOpenFolderMenuId(null);
    setActionDialog({ action: "delete", target: { type: "folder", item: folder }, value: "", permanent: false });
  }

  async function submitAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!actionDialog) return;
    const { action, target, permanent } = actionDialog;
    const oldName = target.type === "file" ? target.item.originalName : target.item.name;
    const name = actionDialog.value.trim();
    if (action === "rename" && (!name || name === oldName)) return;
    const path = target.type === "file"
      ? permanent ? `/api/files/${target.item.id}/permanent` : `/api/files/${target.item.id}`
      : `/api/folders/${target.item.id}`;
    setActionPending(true);
    try {
      const response = await apiFetch(path, action === "rename"
        ? { method: "PATCH", body: JSON.stringify({ name }) }
        : { method: "DELETE" });
      if (!response.ok) throw new Error();
      if (target.type === "file" && selected?.id === target.item.id) {
        setSelected(action === "rename" ? { ...selected, originalName: name } : null);
      }
      if (target.type === "folder" && action === "rename") {
        if (currentFolder?.id === target.item.id) setCurrentFolder({ ...currentFolder, name });
        setFolderTrail(trail => trail.map(folder => folder.id === target.item.id ? { ...folder, name } : folder));
      }
      setOpenFileMenuId(null);
      setOpenFolderMenuId(null);
      setActionDialog(null);
      await load();
      notify(action === "rename"
        ? `${target.type === "file" ? "파일" : "폴더"} 이름을 변경했습니다.`
        : target.type === "folder" ? "폴더를 삭제했습니다."
        : permanent ? "파일을 영구 삭제했습니다." : "파일을 휴지통으로 이동했습니다.");
    } catch {
      notify(action === "rename"
        ? `${target.type === "file" ? "파일" : "폴더"} 이름을 변경하지 못했습니다.`
        : target.type === "folder" ? "폴더를 삭제하지 못했습니다. 폴더가 비어 있는지 확인해 주세요."
        : "파일을 삭제하지 못했습니다.");
    } finally {
      setActionPending(false);
    }
  }

  async function startDownload(fileIds: number[]) {
    if (!fileIds.length || downloadPending) return;
    setDownloadPending(true);
    try {
      const ticket = await apiJson<DownloadTicketResponse>("/api/files/download-tickets", {
        method: "POST",
        body: JSON.stringify({ fileIds })
      });
      const link = document.createElement("a");
      link.href = ticket.url;
      link.setAttribute("aria-hidden", "true");
      document.body.appendChild(link);
      link.click();
      link.remove();
      setSelectedIds(new Set());
    } catch (exception) {
      const reason = exception instanceof Error ? exception.message : "알 수 없는 오류입니다.";
      notify(`다운로드 실패: ${reason}`, 7000);
    } finally {
      setDownloadPending(false);
    }
  }

  async function loadSubtitle(file: File) {
    if (!selectedId) return;
    try {
      const vtt = await decodeSubtitleFile(file);
      const url = URL.createObjectURL(new Blob([vtt], { type: "text/vtt;charset=utf-8" }));
      setSubtitle({ fileId: selectedId, url, name: file.name });
      notify(`${file.name} 자막을 적용했습니다.`);
    } catch (exception) {
      notify(exception instanceof Error ? exception.message : "자막을 읽지 못했습니다.");
    }
  }

  async function attachTag(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    const name = tagName.trim();
    if (!name) return;
    setTagPending(true);
    try {
      let tag = availableTags.find(item => item.name.toLocaleLowerCase() === name.toLocaleLowerCase());
      if (!tag) {
        tag = await apiJson<Tag>("/api/tags", { method: "POST", body: JSON.stringify({ name }) });
        setAvailableTags(items => [...items, tag!].sort((a, b) => a.name.localeCompare(b.name, "ko")));
      }
      if (selected.tags?.some(item => item.id === tag.id)) {
        notify("이미 붙어 있는 태그입니다.");
        return;
      }
      const response = await apiFetch(`/api/files/${selected.id}/tags`, {
        method: "POST",
        body: JSON.stringify({ tagId: tag.id })
      });
      if (!response.ok) throw new Error();
      setSelected({ ...selected, tags: [...(selected.tags ?? []), tag].sort((a, b) => a.name.localeCompare(b.name, "ko")) });
      setTagName("");
      notify("태그를 붙였습니다.");
    } catch {
      notify("태그를 붙이지 못했습니다.");
    } finally {
      setTagPending(false);
    }
  }

  async function detachTag(tag: Tag) {
    if (!selected) return;
    setTagPending(true);
    try {
      const response = await apiFetch(`/api/files/${selected.id}/tags/${tag.id}`, { method: "DELETE" });
      if (!response.ok) throw new Error();
      setSelected({ ...selected, tags: selected.tags?.filter(item => item.id !== tag.id) });
      notify("태그를 제거했습니다.");
    } catch {
      notify("태그를 제거하지 못했습니다.");
    } finally {
      setTagPending(false);
    }
  }

  async function share() {
    if (!selected) return;
    const expiresAt = new Date(Date.now() + 7 * 86_400_000).toISOString();
    try {
      const result = await apiJson<{ path: string }>("/api/share-links", {
        method: "POST",
        body: JSON.stringify({ fileId: selected.id, expiresAt })
      });
      await navigator.clipboard.writeText(`${location.origin}${result.path}`);
      notify("7일 동안 유효한 링크를 복사했습니다.");
    } catch {
      notify("공유 링크를 만들지 못했습니다.");
    }
  }

  async function logout() {
    const refreshToken = getRefreshToken();
    if (refreshToken) await apiFetch("/api/auth/logout", { method: "POST", body: JSON.stringify({ refreshToken }) }).catch(() => undefined);
    clearTokens();
    router.replace("/login");
  }

  function changeNav(next: Nav) {
    setNav(next);
    setCurrentFolder(null);
    setFolderTrail([]);
    setSelected(null);
    setSelectedIds(new Set());
  }

  return (
    <main className={`vaultShell ${selected ? "hasPreview" : ""}`}>
      <header className="topbar">
        <a className="brand" href="#" aria-label="DevVault 홈"><ShieldCheck weight="fill" /><strong>DevVault</strong></a>
        <div className="globalSearch">
          <MagnifyingGlass aria-hidden="true" />
          <input ref={searchRef} value={query} onChange={event => setQuery(event.target.value)} aria-label="파일 검색" placeholder="파일, 폴더, 내용 검색" />
          <kbd>⌘ K</kbd>
        </div>
        <div className="profileArea">
          <div className="notificationWrap" onClick={event => event.stopPropagation()}>
            <button className={`iconButton ${notificationOpen ? "active" : ""}`} aria-label="알림 목록" aria-expanded={notificationOpen} onClick={() => { setProfileOpen(false); setNotificationOpen(open => !open); }}><Bell />{notificationCount > 0 && <span className="notificationBadge">{notificationCount}</span>}</button>
            {notificationOpen && <section className="notificationPanel" aria-label="중요 알림">
              <header><strong>알림</strong><span>{notificationCount}</span></header>
              {storageAlert && <article className="notificationItem warning"><strong>{storageAlert.title}</strong><span>{storageAlert.detail}</span></article>}
              {uploadFailure && <article className="notificationItem error"><strong>업로드에 실패했습니다.</strong><span>{uploadFailure}</span><button onClick={() => setUploadFailure(null)}>지우기</button></article>}
              {!notificationCount && <p>새로운 중요 알림이 없습니다.</p>}
            </section>}
          </div>
          <button className="profileButton" onClick={() => { setNotificationOpen(false); setProfileOpen(!profileOpen); }} aria-expanded={profileOpen}>
            {profileImageUrl ? <Image unoptimized src={profileImageUrl} alt="프로필 사진" width={36} height={36} /> : <UserCircle className="profileAvatar" size={36} weight="duotone" aria-hidden="true" />}
            <span>{user}</span><CaretDown />
          </button>
          {profileOpen && <div className="profileMenu">{isAdmin && <Link href="/admin" onClick={() => setProfileOpen(false)}><Users /> 사용자 관리</Link>}<Link href="/profile" onClick={() => setProfileOpen(false)}><UserCircle /> 프로필</Link><button onClick={logout}><SignOut /> 로그아웃</button></div>}
        </div>
      </header>

      <aside className="sidebar">
        <nav aria-label="주 메뉴">
          {navItems.map(item => <button key={item.id} className={nav === item.id ? "active" : ""} onClick={() => changeNav(item.id)}><item.icon /><span>{item.label}</span></button>)}
        </nav>
        <div className="sidebarDivider" />
        <div className="folderHeading"><span>내 폴더</span><button aria-label="새 폴더" onClick={createFolder}><Plus /></button></div>
        <div className="folderNav">
            {folders.filter(folder => folder.parentId == null).map(folder => (
              <button
                key={folder.id}
                className={`${currentFolder?.id === folder.id ? "active" : ""} ${dropFolderId === folder.id ? "dropTarget" : ""}`}
                onClick={() => openRootFolder(folder)}
                onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = "move"; setDropFolderId(folder.id); }}
                onDragLeave={() => setDropFolderId(null)}
                onDrop={event => dropFile(event, folder.id)}
              ><FolderIcon /><span>{folder.name}</span></button>
            ))}
        </div>
        <div className="storage">
          <span>저장소 사용량</span>
          <small>{formatStorage(dashboard.usedBytes)} / {formatStorage(dashboard.quotaBytes)} 사용됨</small>
          <div className="usageTrack" aria-label={`저장소 사용량 ${usedPercent.toFixed(1)}%`}><span style={{ width: dashboard.usedBytes > 0 ? `${Math.max(usedPercent, 0.6)}%` : "0%" }} /></div>
          <small>{usedPercent > 0 && usedPercent < 0.1 ? "<0.1%" : `${usedPercent.toFixed(1)}%`}</small>
          <a className="upgradeStorage" href={process.env.NEXT_PUBLIC_UPGRADE_URL || "mailto:?subject=DevVault%20저장소%20업그레이드%20요청"}>용량 업그레이드</a>
        </div>
      </aside>

      <section className="content">
        <div className="contentHeader">
          <div>
            <h1>{currentFolder?.name || navItems.find(item => item.id === nav)?.label}</h1>
            {currentFolder && <nav className="breadcrumb" aria-label="폴더 경로">
              <button onClick={() => openFolderTrail(-1)}>파일</button>
              {folderTrail.map((folder, index) => (
                <span key={folder.id}> / <button onClick={() => openFolderTrail(index)} aria-current={index === folderTrail.length - 1 ? "page" : undefined}>{folder.name}</button></span>
              ))}
            </nav>}
          </div>
          <div className="headerActions">
            {nav === "all" && currentFolder && <button className="secondaryButton" onClick={createFolder}><Plus />새 폴더</button>}
            <input ref={uploadRef} type="file" multiple hidden onChange={upload} />
            <button className="primaryButton" disabled={Boolean(uploadProgress)} onClick={() => uploadRef.current?.click()}><UploadSimple />{uploadProgress ? `${uploadProgress.current}/${uploadProgress.count}` : "업로드"}</button>
            <div className="viewToggle" aria-label="보기 방식">
              <button className={view === "list" ? "active" : ""} onClick={() => setView("list")}><List />목록</button>
              <button className={view === "grid" ? "active" : ""} onClick={() => setView("grid")}><GridFour />그리드</button>
            </div>
          </div>
        </div>

        {uploadProgress && <div className="uploadProgress" role="status" aria-live="polite">
          <div><strong>{uploadProgress.current}/{uploadProgress.count} · {uploadProgress.name}</strong><span>{formatStorage(uploadProgress.loaded)} / {formatStorage(uploadProgress.total)} · {uploadPercent}%</span></div>
          <div className="uploadProgressTrack" aria-label={`업로드 진행률 ${uploadPercent}%`}><span style={{ width: `${uploadPercent}%` }} /></div>
        </div>}

        {nav === "all" && !query && !currentFolder && <section className="recentSection" aria-labelledby="recent-title">
          <h2 id="recent-title">최근 항목</h2>
          <div className="recentGrid">
            {recent.map(file => <button key={file.id} className="recentCard" onClick={() => openFile(file)}>
              <RecentThumbnail file={file} />
              <span className="recentMeta"><FileGlyph file={file} /><span><strong>{file.originalName}</strong><small>{fileType(file).toUpperCase()} · {formatDate(file.createdAt)}</small></span><Star weight={file.favorite ? "fill" : "regular"} className={file.favorite ? "starred" : ""} /></span>
            </button>)}
            {!recent.length && <div className="stateMessage"><ClockCounterClockwise /><strong>최근 파일이 없습니다.</strong><span>파일을 업로드하면 여기에 표시됩니다.</span></div>}
          </div>
        </section>}

        <section className="filesSection" aria-labelledby="files-title">
          <div className="sectionTitle">
            <h2 id="files-title">{nav === "all" ? query ? "검색 결과" : currentFolder ? "폴더 내용" : "파일 및 폴더" : navItems.find(item => item.id === nav)?.label}</h2>
            <div className="filterWrap" onClick={event => event.stopPropagation()}>
              <button className={`iconButton ${filterOpen || filterExtension || filterTag ? "active" : ""}`} aria-label="필터" aria-expanded={filterOpen} onClick={() => setFilterOpen(open => !open)}><SlidersHorizontal /></button>
              {filterOpen && <div className="filterPopover">
                <strong>파일 필터</strong>
                <label>확장자<input value={filterExtension} onChange={event => setFilterExtension(event.target.value)} placeholder="예: pdf" /></label>
                <label>태그<input value={filterTag} onChange={event => setFilterTag(event.target.value)} placeholder="태그 이름" /></label>
                <div>
                  <button className="filterClear" onClick={() => { setFilterExtension(""); setFilterTag(""); }}>초기화</button>
                  <button className="filterApply" onClick={() => setFilterOpen(false)}>적용</button>
                </div>
              </div>}
            </div>
          </div>
        {selectedIds.size > 0 && <div className="bulkSelectionBar">
          <strong role="status" aria-live="polite">{selectedIds.size}개 선택</strong>
          <div>
            <button type="button" disabled={downloadPending} onClick={() => startDownload([...selectedIds])}><DownloadSimple />{downloadPending ? "준비 중..." : "다운로드"}</button>
            <button type="button" onClick={() => setSelectedIds(new Set())}><X />선택 해제</button>
          </div>
        </div>}
        {error && <div className="stateMessage errorState">{error}<button onClick={load}>다시 시도</button></div>}
          {nav === "shared" ? <div className="stateMessage"><ShareNetwork /><strong>파일에서 공유 링크를 만들어 보세요.</strong><span>생성한 링크는 7일 동안 안전하게 사용할 수 있습니다.</span></div> :
          loading ? <div className="stateMessage">파일을 불러오는 중...</div> :
          <div className={`fileTable ${view}`}>
            <div className="tableHeader">
              {canSelectFiles
                ? <input
                    ref={input => { if (input) input.indeterminate = selectedVisibleCount > 0 && !allVisibleSelected; }}
                    className="rowCheck"
                    type="checkbox"
                    checked={allVisibleSelected}
                    disabled={!selectableFiles.length}
                    onChange={toggleVisibleFiles}
                    aria-label="현재 파일 전체 선택"
                  />
                : <span />}
              <span>이름 ↑</span><span>수정일</span><span>크기</span><span>소유자</span><span />
            </div>
            {nav === "all" && tableFolders.map(folder => (
              <div
                className={`fileRow ${dropFolderId === folder.id ? "dropTarget" : ""}`}
                role="button"
                tabIndex={0}
                key={`folder-${folder.id}`}
                onClick={() => openChildFolder(folder)}
                onKeyDown={event => { if (event.key === "Enter") openChildFolder(folder); }}
                onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = "move"; setDropFolderId(folder.id); }}
                onDragLeave={() => setDropFolderId(null)}
                onDrop={event => dropFile(event, folder.id)}
              >
                <span /><span className="nameCell"><FolderIcon weight="fill" className="folderGlyph" /><strong>{folder.name}</strong></span><span>{formatDate(folder.createdAt)}</span><span>–</span><span>나</span>
                <span className="rowActions">
                  <button className="miniAction" aria-label={`${folder.name} 메뉴`} aria-expanded={openFolderMenuId === folder.id} onClick={event => { event.stopPropagation(); setOpenFileMenuId(null); setOpenFolderMenuId(openFolderMenuId === folder.id ? null : folder.id); }}><DotsThree /></button>
                  {openFolderMenuId === folder.id && <span className="actionMenu" onClick={event => event.stopPropagation()}><button onClick={() => renameFolder(folder)}><PencilSimple />이름 바꾸기</button><button className="dangerAction" onClick={() => deleteFolder(folder)}><Trash />삭제</button></span>}
                </span>
              </div>
            ))}
            {files.map(file => (
            <div
              className={`fileRow ${selected?.id === file.id ? "selected" : ""} ${draggedFileId === file.id ? "dragging" : ""}`}
              role="button"
              tabIndex={0}
              key={file.id}
              draggable={nav !== "trash"}
              onDragStart={event => startFileDrag(event, file)}
              onDragEnd={() => { setDraggedFileId(null); setDropFolderId(null); }}
              onClick={() => openFile(file)}
              onKeyDown={event => { if (event.key === "Enter") openFile(file); }}
            >
                {canSelectFiles
                  ? <input
                      className="rowCheck"
                      type="checkbox"
                      checked={selectedIds.has(file.id)}
                      onClick={event => event.stopPropagation()}
                      onChange={() => toggleFileSelection(file.id)}
                      aria-label={`${file.originalName} 선택`}
                    />
                  : <span />}
                <span className="nameCell"><FileGlyph file={file} /><strong>{file.originalName}</strong></span>
                <span>{formatDate(file.createdAt)}</span><span>{formatSize(file.size)}</span><span>나</span>
                <span className="rowActions">
                  {nav !== "trash" && <button className="miniAction" aria-label="즐겨찾기" onClick={event => { event.stopPropagation(); toggleFavorite(file); }}><Star weight={file.favorite ? "fill" : "regular"} className={file.favorite ? "starred" : ""} /></button>}
                  <button className="miniAction dangerIcon" aria-label={nav === "trash" ? "영구 삭제" : "삭제"} onClick={event => { event.stopPropagation(); deleteFile(file); }}><Trash /></button>
                  <button className="miniAction" aria-label={`${file.originalName} 메뉴`} aria-expanded={openFileMenuId === file.id} onClick={event => { event.stopPropagation(); setOpenFolderMenuId(null); setOpenFileMenuId(openFileMenuId === file.id ? null : file.id); }}><DotsThree /></button>
                  {openFileMenuId === file.id && <span className="actionMenu" onClick={event => event.stopPropagation()}>
                    {nav === "trash" ? <button onClick={() => restoreFile(file)}><ClockCounterClockwise />복원</button> : <button onClick={() => renameFile(file)}><PencilSimple />이름 바꾸기</button>}
                    <button className="dangerAction" onClick={() => deleteFile(file)}><Trash />{nav === "trash" ? "영구 삭제" : "삭제"}</button>
                  </span>}
                </span>
              </div>
            ))}
            {!files.length && !tableFolders.length && <div className="stateMessage"><FileIcon /><strong>표시할 파일이 없습니다.</strong><span>파일을 업로드하면 여기에 표시됩니다.</span></div>}
          </div>}
        </section>
      </section>

      {selected && <aside className={`previewPanel ${previewExpanded ? "expanded" : ""}`} aria-label="파일 미리보기">
        <header><FileGlyph file={selected} /><strong>{selected.originalName}</strong><button className="iconButton" onClick={() => setPreviewExpanded(value => !value)} aria-label={previewExpanded ? "작게 보기" : "크게 보기"}>{previewExpanded ? <ArrowsIn /> : <ArrowsOut />}</button><button className="iconButton" onClick={() => { setPreviewExpanded(false); setSelected(null); }} aria-label="미리보기 닫기"><X /></button></header>
        <div className={`previewFrame ${fileType(selected)}`}>
          {previewText !== null ? <pre>{previewText || "내용이 없습니다."}</pre> : previewUrl && fileType(selected) === "pdf" ? <div className="pdfDocument"><a href={previewUrl} target="_blank" rel="noopener noreferrer"><ArrowsOut />새 탭에서 전체 문서 보기</a><iframe src={previewUrl} title={`${selected.originalName} 미리보기`} /></div> : previewUrl && fileType(selected) === "audio" ? <audio controls src={previewUrl} /> : previewUrl && fileType(selected) === "video" ? <VideoPlayer src={previewUrl} subtitleUrl={subtitleUrl} subtitleName={subtitle?.name} onSubtitleFile={loadSubtitle} onRemoveSubtitle={() => setSubtitle(null)} /> : previewUrl ? <Image unoptimized src={previewUrl} alt={`${selected.originalName} 미리보기`} width={640} height={820} /> : <FileGlyph file={selected} size={48} />}
        </div>
        <dl>
          <div><dt>유형</dt><dd>{selected.contentType || "알 수 없음"}</dd></div>
          <div><dt>크기</dt><dd>{formatSize(selected.size)}</dd></div>
          <div><dt>수정일</dt><dd>{formatDate(selected.createdAt)}</dd></div>
          <div><dt>위치</dt><dd>/{folderTrail.length ? folderTrail.map(folder => folder.name).join("/") : "내 파일"}/{selected.originalName}</dd></div>
        </dl>
        <div className="tags"><span>태그</span><form className="tagForm" onSubmit={attachTag}><input list="available-tags" maxLength={50} value={tagName} onChange={event => setTagName(event.target.value)} placeholder="태그 입력 또는 선택" aria-label="붙일 태그" /><datalist id="available-tags">{availableTags.map(tag => <option key={tag.id} value={tag.name} />)}</datalist><button type="submit" disabled={tagPending || !tagName.trim()} aria-label="태그 붙이기"><Plus />추가</button></form>{!!selected.tags?.length && <div>{selected.tags.map((tag: Tag) => <button type="button" key={tag.id} disabled={tagPending} onClick={() => detachTag(tag)} aria-label={`${tag.name} 태그 제거`}>{tag.name}<X /></button>)}</div>}</div>
        <div className="previewActions"><button className="primaryButton" disabled={downloadPending} onClick={() => selected && startDownload([selected.id])}><DownloadSimple />{downloadPending ? "준비 중..." : "다운로드"}</button><button className="secondaryButton" onClick={share}><ShareNetwork />공유</button></div>
      </aside>}
      {actionDialog && <div className="modalBackdrop" onMouseDown={() => !actionPending && setActionDialog(null)}>
        <form className="actionDialog" role="dialog" aria-modal="true" aria-labelledby="action-dialog-title" aria-describedby="action-dialog-description" onSubmit={submitAction} onMouseDown={event => event.stopPropagation()}>
          <header><h2 id="action-dialog-title">{actionDialog.action === "rename" ? "이름 바꾸기" : actionDialog.permanent ? "영구 삭제" : "삭제"}</h2><button type="button" className="iconButton" aria-label="닫기" disabled={actionPending} onClick={() => setActionDialog(null)}><X /></button></header>
          {actionDialog.action === "rename" ? <label id="action-dialog-description">새 이름<input autoFocus required maxLength={actionDialog.target.type === "folder" ? 100 : 255} value={actionDialog.value} onChange={event => setActionDialog({ ...actionDialog, value: event.target.value })} /></label> : <p id="action-dialog-description"><strong>{actionDialog.target.type === "file" ? actionDialog.target.item.originalName : actionDialog.target.item.name}</strong>{actionDialog.target.type === "folder" ? " 폴더를 삭제할까요? 빈 폴더만 삭제할 수 있습니다." : actionDialog.permanent ? " 파일을 영구 삭제할까요? 이 작업은 되돌릴 수 없습니다." : " 파일을 휴지통으로 이동할까요?"}</p>}
          <footer><button type="button" className="secondaryButton" disabled={actionPending} onClick={() => setActionDialog(null)}>취소</button><button type="submit" className={actionDialog.action === "delete" ? "dangerButton" : "primaryButton"} disabled={actionPending || (actionDialog.action === "rename" && !actionDialog.value.trim())}>{actionPending ? "처리 중..." : actionDialog.action === "rename" ? "변경" : "삭제"}</button></footer>
        </form>
      </div>}
      {toast && <div className="toast" role="status"><Check weight="bold" />{toast}</div>}
    </main>
  );
}
