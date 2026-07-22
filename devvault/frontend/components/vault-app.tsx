"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import {
  Bell, CaretDown, Check, ClockCounterClockwise, DotsThree, DownloadSimple,
  File as FileIcon, FileCode, FileImage, FilePdf, FileText, Folder as FolderIcon,
  GridFour, List, MagnifyingGlass, Plus, ShareNetwork, ShieldCheck, SignOut,
  SlidersHorizontal, Star, Trash, UploadSimple, Users, X
} from "@phosphor-icons/react";
import { ChangeEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiFetch, apiJson } from "@/lib/api";
import { clearTokens, getAccessToken, getRefreshToken, tokenSubject } from "@/lib/auth";
import type { Folder, PageResponse, Tag, VaultFile } from "@/lib/types";

type Nav = "all" | "favorite" | "recent" | "shared" | "trash";
type View = "list" | "grid";
type Dashboard = { fileCount: number; usedBytes: number };

const DEMO = process.env.NEXT_PUBLIC_DEMO_MODE === "true";
const MOCK_FOLDERS: Folder[] = [
  { id: 1, name: "프로젝트", parentId: null, createdAt: "2026-07-20T07:31:00Z" },
  { id: 2, name: "문서", parentId: null, createdAt: "2026-07-19T05:18:00Z" },
  { id: 3, name: "아카이브", parentId: null, createdAt: "2026-07-10T02:07:00Z" },
  { id: 4, name: "개인", parentId: null, createdAt: "2026-07-08T06:40:00Z" },
  { id: 5, name: "자료", parentId: null, createdAt: "2026-07-03T03:12:00Z" }
];
const MOCK_FILES: VaultFile[] = [
  { id: 101, originalName: "개발 프로세스 가이드.pdf", folderId: 2, contentType: "application/pdf", size: 2_400_000, checksum: "demo-101", favorite: true, createdAt: "2026-07-18T06:22:00Z", tags: [{ id: 1, name: "가이드" }, { id: 2, name: "프로세스" }] },
  { id: 102, originalName: "데이터 흐름도_v1.png", folderId: 1, contentType: "image/png", size: 1_600_000, checksum: "demo-102", favorite: false, createdAt: "2026-07-17T01:15:00Z", tags: [{ id: 3, name: "설계" }] },
  { id: 103, originalName: "배포 체크리스트.md", folderId: 1, contentType: "text/markdown", size: 18_432, checksum: "demo-103", favorite: false, createdAt: "2026-07-16T08:09:00Z", tags: [] },
  { id: 104, originalName: "아이디어 메모.txt", folderId: 4, contentType: "text/plain", size: 4_096, checksum: "demo-104", favorite: false, createdAt: "2026-07-15T00:41:00Z", tags: [] },
  { id: 105, originalName: "utils.ts", folderId: 1, contentType: "text/plain", size: 3_072, checksum: "demo-105", favorite: false, createdAt: "2026-07-14T04:33:00Z", tags: [{ id: 4, name: "코드" }] }
];

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
  if (/\.(md|ts|tsx|js|jsx|java|py|sql|json|yml|yaml)$/.test(name)) return "code";
  if (file.contentType?.startsWith("text/") || name.endsWith(".txt")) return "text";
  return "file";
}

function FileGlyph({ file, size = 22 }: { file: VaultFile; size?: number }) {
  const type = fileType(file);
  if (type === "pdf") return <FilePdf size={size} weight="duotone" className="typePdf" />;
  if (type === "image") return <FileImage size={size} weight="duotone" className="typeImage" />;
  if (type === "code") return <FileCode size={size} weight="duotone" className="typeCode" />;
  if (type === "text") return <FileText size={size} weight="duotone" className="typeText" />;
  return <FileIcon size={size} weight="duotone" />;
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1_048_576) return `${Math.round(bytes / 1024)}KB`;
  return `${(bytes / 1_048_576).toFixed(1)}MB`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "numeric", minute: "2-digit" }).format(new Date(value));
}

function recentAsset(file: VaultFile, index: number) {
  if (fileType(file) === "pdf") return "/assets/process-guide-cover.png";
  if (fileType(file) === "image") return "/assets/office-building.png";
  if (fileType(file) === "code" || index === 2) return "/assets/architecture-code.png";
  return "/assets/process-guide-cover.png";
}

export function VaultApp() {
  const router = useRouter();
  const searchRef = useRef<HTMLInputElement>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const [nav, setNav] = useState<Nav>("all");
  const [view, setView] = useState<View>("list");
  const [query, setQuery] = useState("");
  const [files, setFiles] = useState<VaultFile[]>(DEMO ? MOCK_FILES : []);
  const [folders, setFolders] = useState<Folder[]>(DEMO ? MOCK_FOLDERS : []);
  const [tableFolders, setTableFolders] = useState<Folder[]>(DEMO ? MOCK_FOLDERS : []);
  const [currentFolder, setCurrentFolder] = useState<Folder | null>(null);
  const [dashboard, setDashboard] = useState<Dashboard>({ fileCount: MOCK_FILES.length, usedBytes: 42_700_000_000 });
  const [selected, setSelected] = useState<VaultFile | null>(DEMO ? MOCK_FILES[0] : null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(DEMO ? "/assets/process-guide-cover.png" : null);
  const [previewText, setPreviewText] = useState<string | null>(null);
  const [loading, setLoading] = useState(!DEMO);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [profileOpen, setProfileOpen] = useState(false);

  const load = useCallback(async () => {
    if (DEMO) {
      let next = MOCK_FILES.filter(file => file.originalName.toLowerCase().includes(query.toLowerCase()));
      if (nav === "favorite") next = next.filter(file => file.favorite);
      if (currentFolder) next = next.filter(file => file.folderId === currentFolder.id);
      setFiles(next);
      setTableFolders(nav === "all" && !currentFolder ? MOCK_FOLDERS : []);
      return;
    }
    setLoading(true);
    setError("");
    try {
      if (!getAccessToken()) return router.replace("/login");
      if (nav === "shared") {
        setFiles([]);
        return;
      }
      const params = new URLSearchParams({ size: "50" });
      if (query) params.set("name", query);
      if (nav === "favorite") params.set("favorite", "true");
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
        createdAt: file.createdAt || (file as VaultFile & { deletedAt?: string }).deletedAt || new Date().toISOString()
      }));
      setFiles(nextFiles);
      setDashboard(summary);
      setSelected(current => current ?? nextFiles[0] ?? null);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "파일을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, [currentFolder, nav, query, router]);

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

  const selectedId = selected?.id;

  useEffect(() => {
    if (!selectedId || DEMO) return;
    let objectUrl: string | null = null;
    let cancelled = false;
    Promise.all([
      apiJson<VaultFile>(`/api/files/${selectedId}`),
      apiFetch(`/api/files/${selectedId}/preview`)
    ]).then(async ([detail, response]) => {
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
    }).catch(() => setPreviewUrl("/assets/process-guide-cover.png"));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [selectedId]);

  const recent = useMemo(() => files.slice().sort((a, b) => +new Date(b.createdAt) - +new Date(a.createdAt)).slice(0, 3), [files]);
  const displayedPreviewUrl = DEMO && selected ? recentAsset(selected, 0) : previewUrl;
  const usedPercent = Math.min(100, Math.max(2, (dashboard.usedBytes / 200_000_000_000) * 100));
  const user = tokenSubject()?.split("@")[0] || "김도현";

  function notify(message: string) {
    setToast(message);
    window.setTimeout(() => setToast(""), 2600);
  }

  function openFile(file: VaultFile) {
    setPreviewText(null);
    setPreviewUrl(null);
    setSelected(file);
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    const body = new FormData();
    body.append("file", file);
    try {
      const response = await apiFetch("/api/files", { method: "POST", body });
      if (!response.ok) throw new Error();
      notify("업로드가 완료되었습니다.");
      await load();
    } catch {
      notify("업로드에 실패했습니다.");
    } finally {
      event.target.value = "";
    }
  }

  async function toggleFavorite(file: VaultFile) {
    if (!DEMO) await apiFetch(`/api/files/${file.id}/favorite`, { method: file.favorite ? "DELETE" : "POST" });
    setFiles(items => items.map(item => item.id === file.id ? { ...item, favorite: !item.favorite } : item));
    if (selected?.id === file.id) setSelected({ ...selected, favorite: !selected.favorite });
  }

  async function download() {
    if (!selected) return;
    if (DEMO) return notify("데모에서는 다운로드를 생략합니다.");
    const response = await apiFetch(`/api/files/${selected.id}/download`);
    if (!response.ok) return notify("다운로드에 실패했습니다.");
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = selected.originalName;
    link.click();
    URL.revokeObjectURL(url);
  }

  async function share() {
    if (!selected) return;
    if (DEMO) {
      await navigator.clipboard.writeText(`${location.origin}/share/demo-link`);
      return notify("공유 링크가 클립보드에 복사되었습니다.");
    }
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
    setSelected(null);
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
          <button className="iconButton" aria-label="알림"><Bell /></button>
          <button className="profileButton" onClick={() => setProfileOpen(!profileOpen)} aria-expanded={profileOpen}>
            <Image src="/assets/profile.png" alt="프로필" width={36} height={36} />
            <span>{user}</span><CaretDown />
          </button>
          {profileOpen && <div className="profileMenu"><button onClick={logout}><SignOut /> 로그아웃</button></div>}
        </div>
      </header>

      <aside className="sidebar">
        <nav aria-label="주 메뉴">
          {navItems.map(item => <button key={item.id} className={nav === item.id ? "active" : ""} onClick={() => changeNav(item.id)}><item.icon /><span>{item.label}</span></button>)}
        </nav>
        <div className="sidebarDivider" />
        <div className="folderHeading"><span>내 폴더</span><button aria-label="새 폴더"><Plus /></button></div>
        <div className="folderNav">
          {folders.filter(folder => folder.parentId == null).map(folder => (
            <button key={folder.id} className={currentFolder?.id === folder.id ? "active" : ""} onClick={() => { setCurrentFolder(folder); setNav("all"); }}><FolderIcon /><span>{folder.name}</span></button>
          ))}
        </div>
        <div className="storage">
          <span>저장소 사용량</span>
          <small>{(dashboard.usedBytes / 1_000_000_000).toFixed(1)}GB / 200GB 사용됨</small>
          <div className="usageTrack"><span style={{ width: `${usedPercent}%` }} /></div>
          <small>{Math.round(usedPercent)}%</small>
        </div>
      </aside>

      <section className="content">
        <div className="contentHeader">
          <div><h1>{currentFolder?.name || navItems.find(item => item.id === nav)?.label}</h1>{currentFolder && <button className="breadcrumb" onClick={() => setCurrentFolder(null)}>파일 / {currentFolder.name}</button>}</div>
          <div className="headerActions">
            <input ref={uploadRef} type="file" hidden onChange={upload} />
            <button className="primaryButton" onClick={() => uploadRef.current?.click()}><UploadSimple />업로드</button>
            <div className="viewToggle" aria-label="보기 방식">
              <button className={view === "list" ? "active" : ""} onClick={() => setView("list")}><List />목록</button>
              <button className={view === "grid" ? "active" : ""} onClick={() => setView("grid")}><GridFour />그리드</button>
            </div>
          </div>
        </div>

        {nav === "all" && !query && !currentFolder && <section className="recentSection" aria-labelledby="recent-title">
          <h2 id="recent-title">최근 항목</h2>
          <div className="recentGrid">
            {recent.map((file, index) => <button key={file.id} className="recentCard" onClick={() => openFile(file)}>
              <Image src={recentAsset(file, index)} alt="" width={520} height={300} />
              <span className="recentMeta"><FileGlyph file={file} /><span><strong>{file.originalName}</strong><small>{fileType(file).toUpperCase()} · {formatDate(file.createdAt)}</small></span><Star weight={file.favorite ? "fill" : "regular"} className={file.favorite ? "starred" : ""} /></span>
            </button>)}
          </div>
        </section>}

        <section className="filesSection" aria-labelledby="files-title">
          <div className="sectionTitle"><h2 id="files-title">{nav === "all" ? "모든 파일" : navItems.find(item => item.id === nav)?.label}</h2><button className="iconButton" aria-label="필터"><SlidersHorizontal /></button></div>
          {error && <div className="stateMessage errorState">{error}<button onClick={load}>다시 시도</button></div>}
          {nav === "shared" ? <div className="stateMessage"><ShareNetwork /><strong>파일에서 공유 링크를 만들어 보세요.</strong><span>생성한 링크는 7일 동안 안전하게 사용할 수 있습니다.</span></div> :
          loading ? <div className="stateMessage">파일을 불러오는 중...</div> :
          <div className={`fileTable ${view}`}>
            <div className="tableHeader"><span /><span>이름 ↑</span><span>수정일</span><span>크기</span><span>소유자</span><span /></div>
            {nav === "all" && tableFolders.map(folder => (
              <div className="fileRow" role="button" tabIndex={0} key={`folder-${folder.id}`} onClick={() => setCurrentFolder(folder)} onKeyDown={event => { if (event.key === "Enter") setCurrentFolder(folder); }}>
                <span className="rowCheck" /><span className="nameCell"><FolderIcon weight="fill" className="folderGlyph" /><strong>{folder.name}</strong></span><span>{formatDate(folder.createdAt)}</span><span>–</span><span>나</span><span><DotsThree /></span>
              </div>
            ))}
            {files.map(file => (
              <div className={`fileRow ${selected?.id === file.id ? "selected" : ""}`} role="button" tabIndex={0} key={file.id} onClick={() => openFile(file)} onKeyDown={event => { if (event.key === "Enter") openFile(file); }}>
                <span className="rowCheck">{selected?.id === file.id && <Check weight="bold" />}</span>
                <span className="nameCell"><FileGlyph file={file} /><strong>{file.originalName}</strong></span>
                <span>{formatDate(file.createdAt)}</span><span>{formatSize(file.size)}</span><span>나</span>
                <span className="rowActions"><button className="miniAction" aria-label="즐겨찾기" onClick={event => { event.stopPropagation(); toggleFavorite(file); }}><Star weight={file.favorite ? "fill" : "regular"} className={file.favorite ? "starred" : ""} /></button><DotsThree /></span>
              </div>
            ))}
            {!files.length && !folders.length && <div className="stateMessage"><FileIcon /><strong>표시할 파일이 없습니다.</strong><span>파일을 업로드하면 여기에 표시됩니다.</span></div>}
          </div>}
        </section>
      </section>

      {selected && <aside className="previewPanel" aria-label="파일 미리보기">
        <header><FileGlyph file={selected} /><strong>{selected.originalName}</strong><button className="iconButton" onClick={() => setSelected(null)} aria-label="미리보기 닫기"><X /></button></header>
        <div className={`previewFrame ${fileType(selected)}`}>
          {previewText !== null ? <pre>{previewText || "내용이 없습니다."}</pre> : displayedPreviewUrl && fileType(selected) === "pdf" && !DEMO ? <iframe src={displayedPreviewUrl} title={`${selected.originalName} 미리보기`} /> : displayedPreviewUrl ? <Image unoptimized src={displayedPreviewUrl} alt={`${selected.originalName} 미리보기`} width={640} height={820} /> : <FileGlyph file={selected} size={48} />}
        </div>
        <dl>
          <div><dt>유형</dt><dd>{selected.contentType || "알 수 없음"}</dd></div>
          <div><dt>크기</dt><dd>{formatSize(selected.size)}</dd></div>
          <div><dt>수정일</dt><dd>{formatDate(selected.createdAt)}</dd></div>
          <div><dt>위치</dt><dd>/{folders.find(folder => folder.id === selected.folderId)?.name || "내 파일"}/{selected.originalName}</dd></div>
        </dl>
        {!!selected.tags?.length && <div className="tags"><span>태그</span><div>{selected.tags.map((tag: Tag) => <span key={tag.id}>{tag.name}</span>)}</div></div>}
        <div className="previewActions"><button className="primaryButton" onClick={download}><DownloadSimple />다운로드</button><button className="secondaryButton" onClick={share}><ShareNetwork />공유</button></div>
      </aside>}
      {toast && <div className="toast" role="status"><Check weight="bold" />{toast}</div>}
    </main>
  );
}
