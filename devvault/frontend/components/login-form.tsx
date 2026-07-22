"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ArrowRight, ShieldCheck } from "@phosphor-icons/react";
import { saveTokens } from "@/lib/auth";
import type { AuthToken } from "@/lib/types";

export function LoginForm({ initialSignup = false, initialError = "" }: { initialSignup?: boolean; initialError?: string }) {
  const router = useRouter();
  const [signup, setSignup] = useState(initialSignup);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(initialError);

  useEffect(() => {
    const fragment = new URLSearchParams(window.location.hash.slice(1));
    const accessToken = fragment.get("accessToken");
    const refreshToken = fragment.get("refreshToken");
    if (accessToken && refreshToken) {
      saveTokens({ accessToken, refreshToken, tokenType: "Bearer", expiresIn: 0 });
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      router.replace("/files");
    }
  }, [router]);

  async function submit(formData: FormData) {
    setLoading(true);
    setError("");
    const body = Object.fromEntries(formData);
    try {
      const response = await fetch(`/api/auth/${signup ? "signup" : "login"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
      if (!response.ok) throw new Error("이메일 또는 비밀번호를 확인해 주세요.");
      saveTokens((await response.json()) as AuthToken);
      router.replace("/files");
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "로그인에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="loginPage">
      <section className="loginPanel" aria-labelledby="login-title">
        <div className="loginBrand"><ShieldCheck weight="fill" /> DevVault</div>
        <div>
          <p className="eyebrow">SECURE FILE WORKSPACE</p>
          <h1 id="login-title">파일은 단순하게,<br />관리는 안전하게.</h1>
          <p className="loginLead">중요한 파일을 한곳에서 찾고, 미리 보고, 공유하세요.</p>
        </div>
        <p className="loginFoot">© 2026 DevVault</p>
      </section>
      <section className="loginFormWrap">
        <form action={submit} className="loginForm">
          <header>
            <h2>{signup ? "계정 만들기" : "다시 오신 것을 환영합니다"}</h2>
            <p>{signup ? "DevVault를 시작하는 데 1분이면 충분합니다." : "계속하려면 계정에 로그인하세요."}</p>
          </header>
          <a className="googleLoginButton" href="/oauth2/authorization/google" onClick={() => setLoading(true)}>
            <span aria-hidden="true">G</span>Google로 계속하기
          </a>
          <p className="oauthConsent">계속하면 <Link href="/terms" target="_blank">이용약관</Link>과 <Link href="/privacy" target="_blank">개인정보처리방침</Link>에 동의하게 됩니다.</p>
          <div className="loginDivider"><span>또는 이메일로 계속</span></div>
          {signup && <label>이름<input name="username" required maxLength={50} autoComplete="name" placeholder="이름" /></label>}
          <label>이메일<input name="email" type="email" required maxLength={50} autoComplete="email" placeholder="you@example.com" /></label>
          <label>비밀번호<input name="password" type="password" required minLength={signup ? 8 : undefined} maxLength={72} autoComplete={signup ? "new-password" : "current-password"} placeholder="8자 이상 입력" /></label>
          {signup && <fieldset className="legalConsent">
            <label><input type="checkbox" name="termsAccepted" value="true" required /><span><Link href="/terms" target="_blank">이용약관</Link>에 동의합니다. <em>(필수)</em></span></label>
            <label><input type="checkbox" name="privacyAccepted" value="true" required /><span><Link href="/privacy" target="_blank">개인정보처리방침</Link>에 동의합니다. <em>(필수)</em></span></label>
          </fieldset>}
          {error && <p className="formError" role="alert">{error}</p>}
          <button className="primaryButton loginSubmit" disabled={loading}>
            {loading ? "처리 중..." : signup ? "계정 만들기" : "로그인"}<ArrowRight />
          </button>
          <p className="formSwitch">
            {signup ? "이미 계정이 있나요?" : "DevVault가 처음인가요?"}
            <button type="button" onClick={() => { setSignup(!signup); setError(""); }}>
              {signup ? "로그인" : "계정 만들기"}
            </button>
          </p>
        </form>
      </section>
    </main>
  );
}
