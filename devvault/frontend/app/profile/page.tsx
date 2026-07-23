"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Camera, ShieldCheck, Trash, UserCircle } from "@phosphor-icons/react";
import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import { apiFetch, apiJson } from "@/lib/api";
import { tokenSubject } from "@/lib/auth";

type UserProfile = { email: string; username: string; hasProfileImage: boolean; passwordLoginEnabled: boolean };

export default function ProfilePage() {
  const router = useRouter();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [username, setUsername] = useState("");
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  const [changingPassword, setChangingPassword] = useState(false);
  const [passwordNotice, setPasswordNotice] = useState("");
  const passwordLoginEnabled = profile?.passwordLoginEnabled !== false;

  useEffect(() => {
    const email = tokenSubject();
    if (!email) return router.replace("/login");
    let objectUrl: string | null = null;
    let cancelled = false;
    apiJson<UserProfile>("/api/users/profile").then(async result => {
      if (cancelled) return;
      setProfile(result);
      setUsername(result.username);
      if (!result.hasProfileImage) return;
      const response = await apiFetch("/api/users/profile/image");
      if (!response.ok || cancelled) return;
      objectUrl = URL.createObjectURL(await response.blob());
      setImageUrl(objectUrl);
    }).catch(() => {
      if (cancelled) return;
      setProfile({ email, username: "", hasProfileImage: false, passwordLoginEnabled: true });
      setNotice("백엔드 프로필 API 연결이 필요합니다.");
    });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [refreshKey, router]);

  async function updateProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    const response = await apiFetch("/api/users/profile", { method: "PATCH", body: JSON.stringify({ username: username.trim() }) }).catch(() => null);
    setSaving(false);
    if (!response?.ok) return setNotice("프로필을 저장하지 못했습니다.");
    setNotice("프로필을 저장했습니다.");
    setRefreshKey(key => key + 1);
  }

  async function uploadImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    const body = new FormData();
    body.append("file", file);
    const response = await apiFetch("/api/users/profile/image", { method: "POST", body }).catch(() => null);
    event.target.value = "";
    if (!response?.ok) return setNotice("프로필 사진을 저장하지 못했습니다.");
    setNotice("프로필 사진을 저장했습니다.");
    setRefreshKey(key => key + 1);
  }

  async function deleteImage() {
    const response = await apiFetch("/api/users/profile/image", { method: "DELETE" }).catch(() => null);
    if (!response?.ok) return setNotice("프로필 사진을 삭제하지 못했습니다.");
    setImageUrl(null);
    setNotice("프로필 사진을 삭제했습니다.");
    setRefreshKey(key => key + 1);
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const currentPassword = String(data.get("currentPassword") || "");
    const newPassword = String(data.get("newPassword") || "");
    const passwordConfirm = String(data.get("passwordConfirm") || "");

    if (newPassword !== passwordConfirm) {
      setPasswordNotice("새 비밀번호가 일치하지 않습니다.");
      return;
    }

    setChangingPassword(true);
    setPasswordNotice("");
    const response = await apiFetch("/api/users/profile/password", {
      method: "PATCH",
      body: JSON.stringify({ currentPassword, newPassword })
    }).catch(() => null);
    setChangingPassword(false);

    if (!response?.ok) {
      setPasswordNotice(passwordLoginEnabled
        ? "비밀번호를 변경하지 못했습니다. 현재 비밀번호를 확인해 주세요."
        : "비밀번호를 설정하지 못했습니다.");
      return;
    }

    form.reset();
    setPasswordNotice(passwordLoginEnabled
      ? "비밀번호를 변경했습니다."
      : "비밀번호를 설정했습니다. 이제 이메일로도 로그인할 수 있습니다.");
    setProfile(current => current ? { ...current, passwordLoginEnabled: true } : current);
  }

  return <main className="profilePage">
    <header className="profileHeader"><Link className="brand" href="/files"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><Link className="profileBack" href="/files"><ArrowLeft />파일로 돌아가기</Link></header>
    <section className="profileContent">
      <header><h1>프로필</h1><p>계정 정보와 프로필 사진을 관리합니다.</p></header>
      <div className="profileCard">
        <div className="profileIdentity">
          <div className="profilePhoto">{imageUrl ? <Image unoptimized src={imageUrl} alt="프로필 사진" width={88} height={88} /> : <UserCircle size={64} weight="duotone" />}</div>
          <div><h2>{profile?.username || "이름 미등록"}</h2><p>{profile?.email || "계정 정보를 불러오는 중..."}</p><div className="profilePhotoActions"><label><Camera />사진 변경<input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={uploadImage} /></label>{imageUrl && <button type="button" onClick={deleteImage}><Trash />삭제</button>}</div></div>
        </div>
        <form className="profileForm" onSubmit={updateProfile}>
          <label>이름<input value={username} onChange={event => setUsername(event.target.value)} maxLength={50} placeholder="표시할 이름" /></label>
          <label>이메일<input value={profile?.email || ""} disabled /></label>
          {notice && <p className="profileNotice" role="status">{notice}</p>}
          <button className="primaryButton" disabled={saving}>{saving ? "저장 중..." : "변경 사항 저장"}</button>
        </form>
      </div>
      <div className="profileCard passwordCard">
        <header>
          <h2>{passwordLoginEnabled ? "비밀번호 변경" : "비밀번호 설정"}</h2>
          <p>{passwordLoginEnabled
            ? "현재 비밀번호를 확인한 후 새 비밀번호를 설정합니다."
            : "OAuth 계정에 비밀번호를 설정하면 이메일로도 로그인할 수 있습니다."}</p>
        </header>
        <form className="profileForm passwordForm" onSubmit={changePassword}>
          {passwordLoginEnabled && <label>현재 비밀번호<input name="currentPassword" type="password" required autoComplete="current-password" /></label>}
          <label>새 비밀번호<input name="newPassword" type="password" required minLength={8} maxLength={72} autoComplete="new-password" /></label>
          <label>새 비밀번호 확인<input name="passwordConfirm" type="password" required minLength={8} maxLength={72} autoComplete="new-password" /></label>
          {passwordNotice && <p className="profileNotice" role="status">{passwordNotice}</p>}
          <button className="primaryButton" disabled={changingPassword}>{changingPassword ? "처리 중..." : passwordLoginEnabled ? "비밀번호 변경" : "비밀번호 설정"}</button>
        </form>
      </div>
    </section>
  </main>;
}
