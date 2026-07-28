# DevVault

DevVault는 개인 파일을 업로드하고, 폴더로 정리하고, 미리보고, 공유할 수 있는 파일 보관 서비스입니다.

이 저장소의 실제 애플리케이션 코드는 `devvault/` 아래에 있습니다. 루트에는 개발 계획과 산출물 문서가 함께 보관됩니다.

## 주요 기능

- 이메일/비밀번호 회원가입, 로그인, JWT access/refresh token 인증
- Google OAuth 로그인 후 일회용 교환 코드로 토큰 발급
- 파일 업로드, 검색, 이름 변경, 폴더 이동, 다운로드
- 폴더 계층, 태그, 즐겨찾기, 휴지통 복원/영구 삭제
- 이미지/PDF/비디오 등 파일 미리보기와 재생용 임시 URL
- 만료 시간이 있는 공유 링크 다운로드
- 관리자 대시보드, 사용자 관리, 감사 로그
- Swagger/OpenAPI, Actuator health, Prometheus metrics
- Docker Compose 기반 MySQL, Spring Boot backend, Next.js frontend 실행

## 기술 스택

- Backend: Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, MySQL
- Frontend: Next.js 16, React 19, TypeScript
- Infra: Docker Compose, Nginx, GitHub Actions self-hosted ARM64 deploy
- Docs: `devvault/docs/project-guide/`

## 프로젝트 구조

```text
.
├─ devvault/
│  ├─ src/main/java/org/eardream/devvault/   # Spring Boot backend
│  ├─ src/test/java/org/eardream/devvault/   # backend tests
│  ├─ frontend/                              # Next.js frontend
│  ├─ docs/project-guide/                    # 재사용 가이드
│  ├─ compose.yaml                           # local compose
│  ├─ compose.production.yaml                # production network override
│  └─ build.gradle
├─ .github/workflows/deploy.yml              # Raspberry Pi self-hosted deploy
├─ devvault_development_plan.html
└─ progress.md
```

## 환경 변수

```powershell
cd devvault
Copy-Item .env.example .env
```

최소로 확인할 값:

- `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`
- `JWT_SECRET`: 32바이트 이상 문자열
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- `FRONTEND_URL`: 기본값 `http://localhost:3000`

## Docker Compose 실행

```powershell
cd devvault
docker compose --env-file .env up -d --build
```

기본 포트:

- Frontend: `http://localhost:3000`
- Backend health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/api/swagger-ui.html`
- MySQL: `127.0.0.1:3306`

Nginx edge까지 같이 띄울 때:

```powershell
docker compose --env-file .env --profile edge up -d --build
```

## 로컬 개발

Backend:

```powershell
cd devvault
.\gradlew.bat bootRun
```

Frontend:

```powershell
cd devvault\frontend
npm install
npm run dev
```

프론트엔드는 `next.config.ts`의 rewrite로 `/api`, `/oauth2`, `/login/oauth2`, `/share` 요청을 backend로 프록시합니다. 기본 backend 주소는 `http://localhost:8080`이고 `BACKEND_URL`로 바꿀 수 있습니다.

## 검증

Backend:

```powershell
cd devvault
.\gradlew.bat test
```

Frontend:

```powershell
cd devvault\frontend
npm run lint
npm run build
npm run test:file-tools
npm run test:subtitles
npm run test:refresh
npm run test:oauth
npm run test:upload
```

## 배포

`.github/workflows/deploy.yml`은 `main` push 또는 수동 실행 시 self-hosted Linux ARM64 runner에서 아래 조합으로 배포합니다.

```bash
docker compose \
  --project-name devvault \
  --env-file /home/jun/devvault/devvault/.env \
  -f compose.yaml \
  -f compose.production.yaml \
  up -d --build --remove-orphans
```

프로덕션 override는 `marketboard_marketboard` 외부 네트워크에 `devvault-app`, `devvault-frontend` alias를 붙입니다.

## 참고 문서

- `devvault/docs/project-guide/README.md`: DevVault 구현을 다른 Spring Boot 프로젝트에 재사용하기 위한 가이드
- `devvault/docs/project-guide/reference/feature-map.html`: 기능 지도
- `devvault_development_plan.html`: 개발 계획 아카이브
- `progress.md`: 진행 기록
