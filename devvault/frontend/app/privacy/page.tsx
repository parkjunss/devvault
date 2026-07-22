import Link from "next/link";
import { ArrowLeft, ShieldCheck } from "@phosphor-icons/react/dist/ssr";

export default function PrivacyPage() {
  return <main className="legalPage">
    <header><Link className="brand" href="/"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><Link href="/login?signup=1"><ArrowLeft />회원가입으로 돌아가기</Link></header>
    <article>
      <p className="eyebrow">PRIVACY POLICY</p><h1>개인정보처리방침</h1><p className="legalDate">시행일: 2026년 7월 21일</p>
      <section><h2>1. 수집하는 정보</h2><p>회원가입 시 이메일, 이름, 암호화된 비밀번호를 수집합니다. Google 로그인을 사용하는 경우 Google이 제공하는 식별자, 이메일과 프로필 정보를 처리할 수 있습니다.</p></section>
      <section><h2>2. 파일 관련 정보</h2><p>서비스 제공을 위해 사용자가 업로드한 파일, 파일명, 크기, 형식, 체크섬, 폴더·태그 정보와 생성·수정·삭제 시각을 저장합니다.</p></section>
      <section><h2>3. 이용 목적</h2><p>계정 인증, 파일 저장과 검색, 중복 탐지, 미리보기·다운로드, 공유 링크 제공, 사용량 표시, 보안과 장애 대응에 정보를 사용합니다.</p></section>
      <section><h2>4. 보관과 삭제</h2><p>계정과 파일은 서비스 이용 기간 동안 보관합니다. 휴지통에서 영구 삭제된 파일은 복구할 수 없으며, 계정 삭제 요청 시 법적 보관 의무가 없는 정보를 삭제합니다.</p></section>
      <section><h2>5. 제3자 제공과 판매</h2><p>개인정보를 판매하지 않습니다. 법적 의무가 있거나 사용자가 명시적으로 동의한 경우를 제외하고 제3자에게 제공하지 않습니다.</p></section>
      <section><h2>6. 인증 정보</h2><p>웹 클라이언트는 로그인 상태 유지를 위해 접근 토큰과 갱신 토큰을 브라우저 저장소에 보관합니다. 공용 기기에서는 사용 후 반드시 로그아웃해야 합니다.</p></section>
      <section><h2>7. 보호 조치</h2><p>비밀번호 해시, 사용자별 접근 제어, 만료 토큰, HTTPS, 파일 소유권 확인과 관리자 감사 로그 등으로 정보를 보호합니다.</p></section>
      <section><h2>8. 사용자의 권리</h2><p>사용자는 자신의 파일을 조회·수정·삭제하고 개인정보 열람·정정·삭제를 요청할 수 있습니다.</p></section>
      <section><h2>9. 변경과 문의</h2><p>처리방침이 중요한 수준으로 변경되면 시행 전에 알립니다. 개인정보 관련 문의는 서비스 운영자에게 전달할 수 있습니다.</p></section>
    </article>
  </main>;
}
