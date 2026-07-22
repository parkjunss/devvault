import { LoginForm } from "@/components/login-form";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ signup?: string }> }) {
  const params = await searchParams;
  return <LoginForm initialSignup={params.signup === "1"} />;
}
