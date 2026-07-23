"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Database, Files, MagnifyingGlass, Pulse, ShieldCheck, Trash, Users, X } from "@phosphor-icons/react";
import { FormEvent, useEffect, useState } from "react";
import { apiFetch, apiJson } from "@/lib/api";
import { tokenHasRole, tokenSubject } from "@/lib/auth";
import type { PageResponse } from "@/lib/types";

type Dashboard = { userCount: number; enabledUserCount: number; fileCount: number; usedBytes: number; serviceStatus: string };
type AdminUser = { id: number; email: string; username: string; enabled: boolean; roles: string[]; storageQuotaBytes: number; usedBytes: number; deletedAt: string | null };
type AdminFile = { id: number; ownerEmail: string; originalName: string; contentType: string | null; size: number; deletedAt: string | null };
type ConfirmTarget = { kind: "user"; user: AdminUser } | { kind: "files"; ids: number[]; permanent: boolean };

function formatStorage(bytes: number) {
  if (bytes < 1_000) return `${bytes}B`;
  if (bytes < 1_000_000) return `${(bytes / 1_000).toFixed(1)}KB`;
  if (bytes < 1_000_000_000) return `${(bytes / 1_000_000).toFixed(1)}MB`;
  if (bytes < 1_000_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)}GB`;
  return `${(bytes / 1_000_000_000_000).toFixed(1)}TB`;
}

export default function AdminPage() {
  const router = useRouter();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [files, setFiles] = useState<AdminFile[]>([]);
  const [quotaInputs, setQuotaInputs] = useState<Record<number, string>>({});
  const [userQuery, setUserQuery] = useState("");
  const [fileQuery, setFileQuery] = useState("");
  const [selectedFileIds, setSelectedFileIds] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState("");
  const selectedFiles = files.filter(file => selectedFileIds.has(file.id));
  const activeSelectedIds = selectedFiles.filter(file => !file.deletedAt).map(file => file.id);
  const deletedSelectedIds = selectedFiles.filter(file => file.deletedAt).map(file => file.id);

  useEffect(() => {
    if (!tokenSubject()) return router.replace("/login");
    if (!tokenHasRole("ROLE_ADMIN")) return router.replace("/files");
    Promise.all([loadDashboard(), loadUsers(""), loadFiles("")])
      .catch(() => setNotice("관리자 정보를 불러오지 못했습니다."))
      .finally(() => setLoading(false));
  }, [router]);

  async function loadDashboard() {
    setDashboard(await apiJson<Dashboard>("/api/admin/dashboard"));
  }

  async function loadUsers(search: string) {
    const params = new URLSearchParams({ size: "100" });
    if (search.trim()) params.set("query", search.trim());
    const result = await apiJson<PageResponse<AdminUser>>(`/api/admin/users?${params}`);
    setUsers(result.content);
    setQuotaInputs(Object.fromEntries(result.content.map(user => [user.id, String(user.storageQuotaBytes / 1_000_000_000)])));
  }

  async function loadFiles(search: string) {
    const params = new URLSearchParams({ size: "100" });
    if (search.trim()) params.set("query", search.trim());
    const result = await apiJson<PageResponse<AdminFile>>(`/api/admin/files?${params}`);
    setFiles(result.content);
    setSelectedFileIds(new Set());
  }

  async function searchUsers(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setNotice("");
    try { await loadUsers(userQuery); }
    catch { setNotice("사용자를 검색하지 못했습니다."); }
    finally { setLoading(false); }
  }

  async function searchFiles(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setNotice("");
    try { await loadFiles(fileQuery); }
    catch { setNotice("파일을 검색하지 못했습니다."); }
  }

  async function updateUser(user: AdminUser, body: object, success: string) {
    setPendingId(user.id);
    setNotice("");
    try {
      const updated = await apiJson<AdminUser>(`/api/admin/users/${user.id}`, { method: "PATCH", body: JSON.stringify(body) });
      setUsers(items => items.map(item => item.id === updated.id ? updated : item));
      await loadDashboard();
      setNotice(success);
    } catch {
      setNotice("변경하지 못했습니다. 마지막 활성 관리자의 상태나 권한은 변경할 수 없습니다.");
    } finally { setPendingId(null); }
  }

  async function updateQuota(user: AdminUser) {
    const quotaBytes = Math.round(Number(quotaInputs[user.id]) * 1_000_000_000);
    if (!Number.isFinite(quotaBytes) || quotaBytes < user.usedBytes) return setNotice("저장 용량은 현재 사용량보다 작게 지정할 수 없습니다.");
    setPendingId(user.id);
    setNotice("");
    try {
      const updated = await apiJson<AdminUser>(`/api/admin/users/${user.id}/storage-quota`, { method: "PATCH", body: JSON.stringify({ quotaBytes }) });
      setUsers(items => items.map(item => item.id === updated.id ? updated : item));
      setQuotaInputs(inputs => ({ ...inputs, [user.id]: String(updated.storageQuotaBytes / 1_000_000_000) }));
      setNotice("저장 용량을 변경했습니다.");
    } catch { setNotice("저장 용량을 변경하지 못했습니다."); }
    finally { setPendingId(null); }
  }

  function toggleFile(id: number) {
    setSelectedFileIds(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function toggleAllFiles() {
    setSelectedFileIds(current => current.size === files.length ? new Set() : new Set(files.map(file => file.id)));
  }

  async function confirmDelete() {
    if (!confirmTarget) return;
    setConfirming(true);
    setNotice("");
    try {
      if (confirmTarget.kind === "user") {
        const response = await apiFetch(`/api/admin/users/${confirmTarget.user.id}`, { method: "DELETE" });
        if (!response.ok) throw new Error(await response.text());
        await Promise.all([loadUsers(userQuery), loadDashboard()]);
        setNotice("사용자 계정을 삭제하고 로그인 정보를 폐기했습니다. 저장 파일은 유지됩니다.");
      } else {
        const endpoint = confirmTarget.permanent
          ? "/api/admin/files/bulk-permanent-delete"
          : "/api/admin/files/bulk-delete";
        const result = await apiJson<{ deletedCount: number }>(endpoint, {
          method: "POST", body: JSON.stringify({ fileIds: confirmTarget.ids })
        });
        await Promise.all([loadFiles(fileQuery), loadDashboard()]);
        setNotice(confirmTarget.permanent
          ? `${result.deletedCount}개 파일을 영구 삭제했습니다.`
          : `${result.deletedCount}개 파일을 휴지통으로 이동했습니다.`);
      }
      setConfirmTarget(null);
    } catch {
      setNotice(confirmTarget.kind === "user" ? "계정을 삭제하지 못했습니다. 관리자 권한을 먼저 해제해 주세요." : "선택한 파일을 삭제하지 못했습니다.");
    } finally { setConfirming(false); }
  }

  return <main className="adminPage">
    <header className="profileHeader"><Link className="brand" href="/files"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><Link className="profileBack" href="/files"><ArrowLeft />파일로 돌아가기</Link></header>
    <section className="adminContent">
      <header><p className="eyebrow">ADMIN</p><h1>서비스 관리</h1><p>계정, 권한, 저장 용량과 업로드 파일을 관리합니다.</p></header>
      <div className="adminStats">
        <article><Users /><span>전체 사용자</span><strong>{dashboard?.userCount ?? "-"}</strong></article>
        <article><Pulse /><span>활성 사용자</span><strong>{dashboard?.enabledUserCount ?? "-"}</strong></article>
        <article><Files /><span>전체 파일</span><strong>{dashboard?.fileCount ?? "-"}</strong></article>
        <article><Database /><span>사용 용량</span><strong>{dashboard ? formatStorage(dashboard.usedBytes) : "-"}</strong><small>{dashboard?.serviceStatus ?? "확인 중"}</small></article>
      </div>
      {notice && <p className="adminNotice adminGlobalNotice" role="status">{notice}</p>}

      <section className="adminUsers">
        <div className="adminUsersHeader"><div><h2>계정 목록</h2><span>최대 100명씩 표시합니다.</span></div><form onSubmit={searchUsers}><MagnifyingGlass /><input value={userQuery} onChange={event => setUserQuery(event.target.value)} maxLength={100} placeholder="이메일 또는 이름 검색" /><button>검색</button></form></div>
        {loading ? <div className="stateMessage">사용자를 불러오는 중...</div> : <div className="adminTable">
          <div className="adminTableHead"><span>사용자</span><span>상태</span><span>저장 용량</span><span>권한</span><span>작업</span></div>
          {users.map(user => {
            const isRowPending = pendingId === user.id;
            const isAdmin = user.roles.includes("ROLE_ADMIN");
            const isDeleted = !!user.deletedAt;
            return <div className="adminUserRow" key={user.id}>
              <span className="adminIdentity"><strong>{user.username}</strong><small>{user.email}</small></span>
              <span className={`statusBadge ${user.enabled && !isDeleted ? "enabled" : "disabled"}`}>{isDeleted ? "삭제됨" : user.enabled ? "활성" : "비활성"}</span>
              <span className="quotaControl"><input disabled={isDeleted} type="number" min={user.usedBytes / 1_000_000_000} step="0.1" value={quotaInputs[user.id] ?? ""} onChange={event => setQuotaInputs(inputs => ({ ...inputs, [user.id]: event.target.value }))} aria-label={`${user.email} 저장 용량 GB`} /><small>GB</small><button disabled={isRowPending || isDeleted} onClick={() => updateQuota(user)}>적용</button><em>사용 {formatStorage(user.usedBytes)} / 한도 {formatStorage(user.storageQuotaBytes)}</em></span>
              <span className={`roleBadge ${isAdmin ? "admin" : ""}`}>{isDeleted ? "삭제 계정" : isAdmin ? "관리자" : "사용자"}</span>
              <span className="adminRowActions"><button disabled={isRowPending || isDeleted} onClick={() => updateUser(user, { enabled: !user.enabled }, user.enabled ? "사용자를 비활성화했습니다." : "사용자를 활성화했습니다.")}>{user.enabled ? "비활성화" : "활성화"}</button><button disabled={isRowPending || isDeleted} onClick={() => updateUser(user, { roles: isAdmin ? ["ROLE_USER"] : ["ROLE_USER", "ROLE_ADMIN"] }, isAdmin ? "관리자 권한을 해제했습니다." : "관리자 권한을 부여했습니다.")}>{isAdmin ? "관리자 해제" : "관리자 지정"}</button><button className="dangerButton" disabled={isRowPending || isAdmin || isDeleted} title={isDeleted ? "이미 삭제된 계정입니다." : isAdmin ? "관리자 권한을 먼저 해제해 주세요." : "계정 삭제"} onClick={() => setConfirmTarget({ kind: "user", user })}><Trash />{isDeleted ? "삭제됨" : "삭제"}</button></span>
            </div>;
          })}
          {!users.length && <div className="stateMessage">검색된 사용자가 없습니다.</div>}
        </div>}
      </section>

      <section className="adminUsers adminFiles">
        <div className="adminUsersHeader"><div><h2>파일 관리</h2><span>활성·휴지통 파일을 최대 100개씩 표시합니다.</span></div><form onSubmit={searchFiles}><MagnifyingGlass /><input value={fileQuery} onChange={event => setFileQuery(event.target.value)} maxLength={100} placeholder="파일명 또는 소유자 검색" /><button>검색</button></form></div>
        <div className="adminBulkBar"><span>{selectedFileIds.size}개 선택</span><div><button className="dangerButton" disabled={!activeSelectedIds.length} onClick={() => setConfirmTarget({ kind: "files", ids: activeSelectedIds, permanent: false })}><Trash />{activeSelectedIds.length}개 휴지통 이동</button><button className="dangerButton permanent" disabled={!deletedSelectedIds.length} onClick={() => setConfirmTarget({ kind: "files", ids: deletedSelectedIds, permanent: true })}><Trash />{deletedSelectedIds.length}개 영구 삭제</button></div></div>
        <div className="adminFileTable">
          <div className="adminFileHead"><input type="checkbox" checked={files.length > 0 && selectedFileIds.size === files.length} onChange={toggleAllFiles} aria-label="현재 파일 전체 선택" /><span>파일명</span><span>소유자</span><span>상태</span><span>형식</span><span>용량</span></div>
          {files.map(file => <div className="adminFileRow" key={file.id}><input type="checkbox" checked={selectedFileIds.has(file.id)} onChange={() => toggleFile(file.id)} aria-label={`${file.originalName} 선택`} /><strong>{file.originalName}</strong><span>{file.ownerEmail}</span><span className={`statusBadge ${file.deletedAt ? "disabled" : "enabled"}`}>{file.deletedAt ? "휴지통" : "활성"}</span><span>{file.contentType || "-"}</span><span>{formatStorage(file.size)}</span></div>)}
          {!files.length && <div className="stateMessage">표시할 파일이 없습니다.</div>}
        </div>
      </section>
    </section>

    {confirmTarget && <div className="modalBackdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget && !confirming) setConfirmTarget(null); }}>
      <section className="actionDialog" role="dialog" aria-modal="true" aria-labelledby="delete-title">
        <header><h2 id="delete-title">{confirmTarget.kind === "user" ? "사용자 계정을 삭제할까요?" : confirmTarget.permanent ? `${confirmTarget.ids.length}개 파일을 영구 삭제할까요?` : `${confirmTarget.ids.length}개 파일을 삭제할까요?`}</h2><button className="iconButton" disabled={confirming} onClick={() => setConfirmTarget(null)} aria-label="닫기"><X /></button></header>
        {confirmTarget.kind === "user" ? <p><strong>{confirmTarget.user.email}</strong>의 로그인·OAuth·권한 정보가 폐기되고 계정이 익명화됩니다. 사용자가 보관한 파일과 감사 기록은 유지됩니다.</p> : confirmTarget.permanent ? <p>선택한 휴지통 파일을 저장소에서도 제거합니다. <strong>이 작업은 되돌릴 수 없습니다.</strong></p> : <p>선택한 파일은 소유자의 <strong>휴지통</strong>으로 이동합니다. 영구 삭제가 아니므로 복원할 수 있습니다.</p>}
        <footer><button className="secondaryButton" disabled={confirming} onClick={() => setConfirmTarget(null)}>취소</button><button className="dangerButton" disabled={confirming} onClick={confirmDelete}>{confirming ? "처리 중..." : confirmTarget.kind === "files" && confirmTarget.permanent ? "영구 삭제" : "삭제"}</button></footer>
      </section>
    </div>}
  </main>;
}
