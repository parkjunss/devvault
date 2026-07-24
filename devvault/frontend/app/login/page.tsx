import { LoginForm } from "@/components/login-form";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ signup?: string; oauthError?: string; oauthCode?: string }>;
}) {
  const params = await searchParams;
  return (
    <LoginForm
      initialSignup={params.signup === "1"}
      initialError={params.oauthError ? "Google 로그인에 실패했습니다." : ""}
      initialOAuthCode={params.oauthCode ?? ""}
    />
  );
}
