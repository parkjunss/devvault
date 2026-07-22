import "@fontsource-variable/noto-sans-kr";
import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "DevVault",
  description: "파일을 안전하고 단순하게 관리하세요."
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
