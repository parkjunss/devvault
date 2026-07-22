import Link from "next/link";
import { ArrowLeft, ShieldCheck } from "@phosphor-icons/react/dist/ssr";

export default function TermsPage() {
  return <main className="legalPage">
    <header><Link className="brand" href="/"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><Link href="/login?signup=1"><ArrowLeft />회원가입으로 돌아가기</Link></header>
    <article>
      <p className="eyebrow">TERMS OF SERVICE</p><h1>이용약관</h1><p className="legalDate">시행일: 2026년 7월 21일</p>
      <section><h2>1. 목적</h2><p>본 약관은 DevVault가 제공하는 파일 저장·관리·미리보기·공유 서비스의 이용 조건과 사용자 및 서비스 운영자의 권리와 의무를 정합니다.</p></section>
      <section><h2>2. 계정과 보안</h2><p>사용자는 정확한 정보로 계정을 만들고 비밀번호와 접근 토큰을 안전하게 관리해야 합니다. 계정에서 발생한 비정상적인 활동을 발견하면 즉시 운영자에게 알려야 합니다.</p></section>
      <section><h2>3. 사용자 콘텐츠</h2><p>업로드한 파일의 소유권은 사용자에게 있습니다. 사용자는 파일을 저장·미리보기·다운로드·공유하는 데 필요한 범위에서만 서비스가 콘텐츠를 처리하는 것에 동의합니다.</p></section>
      <section><h2>4. 금지 행위</h2><p>불법 콘텐츠 업로드, 타인의 권리 침해, 악성 코드 배포, 무단 접근, 서비스 안정성을 해치는 자동화 또는 과도한 요청은 금지됩니다.</p></section>
      <section><h2>5. 공유 링크</h2><p>공유 링크를 받은 사람은 만료 전까지 파일에 접근할 수 있습니다. 사용자는 링크 수신자와 유효기간을 신중히 관리해야 합니다.</p></section>
      <section><h2>6. 이용 제한과 종료</h2><p>약관 위반이나 보안 위험이 확인되면 사전 통지 후 계정 이용을 제한할 수 있습니다. 긴급한 보안 위험이 있는 경우 먼저 제한한 뒤 안내할 수 있습니다.</p></section>
      <section><h2>7. 서비스 변경</h2><p>기능이나 약관이 중요한 수준으로 변경되는 경우 시행 전에 서비스 화면 등을 통해 알립니다.</p></section>
      <section><h2>8. 문의</h2><p>약관과 서비스 이용에 관한 문의는 서비스 운영자에게 전달할 수 있습니다.</p></section>
    </article>
  </main>;
}
