"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Database, Files, MagnifyingGlass, Pulse, ShieldCheck, Users } from "@phosphor-icons/react";
import { FormEvent, useEffect, useState } from "react";
import { apiFetch, apiJson } from "@/lib/api";
import { tokenHasRole, tokenSubject } from "@/lib/auth";
import type { PageResponse } from "@/lib/types";

type Dashboard = { userCount: number; enabledUserCount: number; fileCount: number; usedBytes: number; serviceStatus: string };
type AdminUser = { id: number; email: string; username: string; enabled: boolean; roles: string[]; storageQuotaBytes: number };

function formatStorage(bytes: number) {
  if (bytes < 1_000_000_000) return `${(bytes / 1_000_000).toFixed(1)}MB`;
  if (bytes < 1_000_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)}GB`;
  return `${(bytes / 1_000_000_000_000).toFixed(1)}TB`;
}

export default function AdminPage() {
  const router = useRouter();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [quotaInputs, setQuotaInputs] = useState<Record<number, string>>({});
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (!tokenSubject()) return router.replace("/login");
    if (!tokenHasRole("ROLE_ADMIN")) return router.replace("/files");
    Promise.all([loadDashboard(), loadUsers("")])
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

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setNotice("");
    try {
      await loadUsers(query);
    } catch {
      setNotice("사용자를 검색하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function updateUser(user: AdminUser, body: object, success: string) {
    setPendingId(user.id);
    setNotice("");
    try {
      const updated = await apiJson<AdminUser>(`/api/admin/users/${user.id}`, {
        method: "PATCH",
        body: JSON.stringify(body)
      });
      setUsers(items => items.map(item => item.id === updated.id ? updated : item));
      await loadDashboard();
      setNotice(success);
    } catch {
      setNotice("변경하지 못했습니다. 마지막 활성 관리자는 비활성화하거나 권한을 제거할 수 없습니다.");
    } finally {
      setPendingId(null);
    }
  }

  async function updateQuota(user: AdminUser) {
    const quotaBytes = Math.round(Number(quotaInputs[user.id]) * 1_000_000_000);
    if (!Number.isFinite(quotaBytes) || quotaBytes <= user.storageQuotaBytes) {
      return setNotice("저장용량은 현재 값보다 크게 입력해 주세요.");
    }
    setPendingId(user.id);
    setNotice("");
    try {
      const updated = await apiJson<AdminUser>(`/api/admin/users/${user.id}/storage-quota`, {
        method: "PATCH",
        body: JSON.stringify({ quotaBytes })
      });
      setUsers(items => items.map(item => item.id === updated.id ? updated : item));
      setQuotaInputs(inputs => ({ ...inputs, [user.id]: String(updated.storageQuotaBytes / 1_000_000_000) }));
      setNotice("저장용량을 변경했습니다.");
    } catch {
      setNotice("저장용량을 변경하지 못했습니다.");
    } finally {
      setPendingId(null);
    }
  }

  return <main className="adminPage">
    <header className="profileHeader"><Link className="brand" href="/files"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><Link className="profileBack" href="/files"><ArrowLeft />파일로 돌아가기</Link></header>
    <section className="adminContent">
      <header><p className="eyebrow">ADMIN</p><h1>사용자 관리</h1><p>계정 상태, 관리자 권한과 저장용량을 관리합니다.</p></header>
      <div className="adminStats">
        <article><Users /><span>전체 사용자</span><strong>{dashboard?.userCount ?? "-"}</strong></article>
        <article><Pulse /><span>활성 사용자</span><strong>{dashboard?.enabledUserCount ?? "-"}</strong></article>
        <article><Files /><span>전체 파일</span><strong>{dashboard?.fileCount ?? "-"}</strong></article>
        <article><Database /><span>사용 용량</span><strong>{dashboard ? formatStorage(dashboard.usedBytes) : "-"}</strong><small>{dashboard?.serviceStatus ?? "확인 중"}</small></article>
      </div>
      <section className="adminUsers">
        <div className="adminUsersHeader"><div><h2>계정 목록</h2><span>최대 100명씩 표시합니다.</span></div><form onSubmit={search}><MagnifyingGlass /><input value={query} onChange={event => setQuery(event.target.value)} maxLength={100} placeholder="이메일 또는 이름 검색" /><button>검색</button></form></div>
        {notice && <p className="adminNotice" role="status">{notice}</p>}
        {loading ? <div className="stateMessage">사용자를 불러오는 중...</div> : <div className="adminTable">
          <div className="adminTableHead"><span>사용자</span><span>상태</span><span>저장용량</span><span>권한</span><span>작업</span></div>
          {users.map(user => {
            const isRowPending = pendingId === user.id;
            const isAdmin = user.roles.includes("ROLE_ADMIN");
            return <div className="adminUserRow" key={user.id}>
              <span className="adminIdentity"><strong>{user.username}</strong><small>{user.email}</small></span>
              <span className={`statusBadge ${user.enabled ? "enabled" : "disabled"}`}>{user.enabled ? "활성" : "비활성"}</span>
              <span className="quotaControl"><input type="number" min="1" step="1" value={quotaInputs[user.id] ?? ""} onChange={event => setQuotaInputs(inputs => ({ ...inputs, [user.id]: event.target.value }))} aria-label={`${user.email} 저장용량 GB`} /><small>GB</small><button disabled={isRowPending} onClick={() => updateQuota(user)}>적용</button><em>현재 {formatStorage(user.storageQuotaBytes)}</em></span>
              <span className={`roleBadge ${isAdmin ? "admin" : ""}`}>{isAdmin ? "관리자" : "사용자"}</span>
              <span className="adminRowActions"><button disabled={isRowPending} onClick={() => updateUser(user, { enabled: !user.enabled }, user.enabled ? "사용자를 비활성화했습니다." : "사용자를 활성화했습니다.")}>{user.enabled ? "비활성화" : "활성화"}</button><button disabled={isRowPending} onClick={() => updateUser(user, { roles: isAdmin ? ["ROLE_USER"] : ["ROLE_USER", "ROLE_ADMIN"] }, isAdmin ? "관리자 권한을 제거했습니다." : "관리자 권한을 부여했습니다.")}>{isAdmin ? "관리자 해제" : "관리자 지정"}</button></span>
            </div>;
          })}
          {!users.length && <div className="stateMessage">검색된 사용자가 없습니다.</div>}
        </div>}
      </section>
    </section>
  </main>;
}
