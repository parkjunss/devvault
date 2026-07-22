import Image from "next/image";
import Link from "next/link";
import { ArrowRight, Eye, FolderSimple, LinkSimple, ShieldCheck } from "@phosphor-icons/react/dist/ssr";

export default function Home() {
  return (
    <main className="landingPage">
      <header className="landingNav">
        <Link className="brand" href="/"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link>
        <nav aria-label="랜딩 페이지 메뉴"><a href="#features">기능</a><a href="#security">보안</a><Link href="/login">로그인</Link><Link className="navCta" href="/login?signup=1">시작하기</Link></nav>
      </header>

      <section className="landingHero">
        <div className="heroCopy">
          <p className="eyebrow">SECURE FILE WORKSPACE</p>
          <h1>파일 관리는<br />단순해야 합니다.</h1>
          <p>찾고, 미리 보고, 정리하고, 공유하는 모든 흐름을 한곳에서. 중요한 파일에만 집중하세요.</p>
          <div className="heroActions"><Link className="primaryButton" href="/login?signup=1">DevVault 시작하기<ArrowRight /></Link><Link className="textLink" href="/login">이미 계정이 있어요</Link></div>
          <small>간단한 회원가입 · 안전한 공유 링크 · 파일 형식별 미리보기</small>
        </div>
        <div className="productShot"><Image src="/assets/product-preview.png" alt="DevVault 파일 관리 화면" width={1488} height={1058} priority /></div>
      </section>

      <section className="landingFeatures" id="features">
        <header><p className="eyebrow">FOCUSED BY DESIGN</p><h2>필요한 기능만, 정확한 자리에.</h2></header>
        <div>
          <article><FolderSimple weight="duotone" /><h3>빠른 정리</h3><p>폴더와 태그로 파일을 정리하고 이름·확장자·태그를 한 번에 검색합니다.</p></article>
          <article><Eye weight="duotone" /><h3>즉시 미리보기</h3><p>이미지, PDF, 텍스트와 코드를 다운로드하지 않고 바로 확인합니다.</p></article>
          <article><LinkSimple weight="duotone" /><h3>안전한 공유</h3><p>만료되는 공유 링크로 필요한 시간 동안만 파일을 전달합니다.</p></article>
        </div>
      </section>

      <section className="securitySection" id="security">
        <ShieldCheck weight="duotone" />
        <div><p className="eyebrow">SECURITY FIRST</p><h2>내 파일은 내 공간에.</h2><p>사용자별 접근 제어, JWT 인증, 만료 링크와 감사 로그로 파일의 전체 수명 주기를 보호합니다.</p></div>
      </section>

      <section className="landingCta"><h2>파일을 더 단순하게 관리하세요.</h2><Link className="primaryButton" href="/login?signup=1">지금 시작하기<ArrowRight /></Link></section>
      <footer className="landingFooter"><Link className="brand" href="/"><ShieldCheck weight="fill" /><strong>DevVault</strong></Link><span>© 2026 DevVault</span><nav><Link href="/terms">이용약관</Link><Link href="/privacy">개인정보처리방침</Link></nav></footer>
    </main>
  );
}
