# DevVault 진행 현황

> 기준일: 2026-07-22  
> 브랜치: `main`  
> 원격 저장소: `https://github.com/parkjunss/devvault.git`

## 프로젝트 목표

Ubuntu `aarch64` 라즈베리파이에서 Docker Compose로 운영하는 개인 파일 아카이브다. 사용자별 파일 저장·검색·분류·공유, 관리자 기능, HTTPS 프록시와 모니터링을 목표로 한다.

## 한눈에 보기

| 영역 | 상태 | 현재 내용 |
|---|---|---|
| 백엔드 핵심 기능 | 완료 | 인증, 파일, 폴더, 태그, 검색, 미리보기, 휴지통, 즐겨찾기, 공유 링크 |
| 관리자 API | 완료 | 사용자·파일 관리, 감사 로그, 초기 관리자 지정 |
| 프런트엔드 | 기반 완료 | Next.js 로그인·파일 화면, 랜딩·약관 페이지와 프로덕션 빌드 검증 완료 |
| 컨테이너 | 진행 중 | MySQL·백엔드·프런트·Nginx Compose 구성, 전체 배포 검증 필요 |
| 모니터링 | 일부 완료 | Actuator와 Prometheus 엔드포인트 설정, 수집·대시보드 컨테이너 미구성 |
| CI/CD | 계획만 완료 | GitHub Actions와 GHCR 배포 흐름은 계획서에만 있고 워크플로 파일은 없음 |

## 완료된 작업

### 프로젝트 기반

- Java 17, Spring Boot 4.1, JPA, Spring Security 구성
- MySQL 8.4 Docker Compose와 영속 볼륨 구성
- `.env` 기반 로컬 설정 및 `.env.example` 제공
- JWT, OAuth, 파일 업로드, Prometheus 관련 환경변수 분리
- 최대 업로드 크기 200MB 설정

### 인증과 권한

- `ROLE_USER`, `ROLE_ADMIN` 시작 시 자동 등록
- 일반 회원가입과 로그인
- JWT 인증
- Refresh Token 저장과 회전
- Google OAuth 로그인
- `oauth_accounts`의 `(provider, provider_user_id)` 복합 unique 제약
- 초기 관리자 이메일 지정과 관리자 권한 부여

### 파일과 탐색

- 사용자 소유권 기반 파일 업로드·다운로드
- 계층형 폴더 생성·조회·이름 변경·이동
- 파일 이름 변경과 폴더 이동
- 태그 생성·연결·검색
- 파일명·확장자·태그 복합 검색
- 이미지·PDF·텍스트·코드 미리보기
- 휴지통 이동·복구·영구 삭제
- SHA-256 기반 중복 파일 탐지
- 즐겨찾기
- 파일 수와 사용 용량 대시보드

### 공유와 관리자 기능

- 만료 시간과 다운로드 제한이 있는 공유 링크
- 관리자 사용자·파일 관리 API
- 관리자 작업 감사 로그
- 마지막 관리자 권한 제거 방지

### 배포 기반

- 백엔드 멀티스테이지 Dockerfile
- MySQL·백엔드 Compose 실행 구성
- Nginx HTTPS 프록시 템플릿
- 프런트엔드 컨테이너와 Nginx 라우팅 구성

### 테스트 환경

- 통합 테스트는 운영 MySQL 대신 H2 메모리 DB 사용
- 테스트 임시 파일은 Gradle `build/tmp/test` 아래에 생성
- 로컬 `.env`, MySQL 컨테이너 상태와 Windows 시스템 임시 폴더 권한에서 분리

## 주요 커밋

| 커밋 | 내용 |
|---|---|
| `8a10061` | JWT 인증 |
| `8c24085` | Refresh Token 회전 |
| `fd67952` | Google OAuth 로그인 |
| `0737987` | 인증된 파일 전송 API |
| `6095c47` | 복합 파일 검색 |
| `d8e84db` | 폴더·태그 탐색 완성 |
| `a7efc80` | 미리보기와 휴지통 |
| `7ec1f99` | 즐겨찾기·중복 탐지·대시보드 |
| `99acbea` | 관리자 API |
| `a4c8a23` | 초기 관리자 권한 부여 |
| `142c110` | 만료 공유 링크 |
| `2e57948` | 애플리케이션 컨테이너화 |
| `6da4978` | Nginx HTTPS 프록시 |

## 2026-07-22 프런트엔드 기반 완료

- Next.js 16·React 19 프런트엔드
- 로그인, 파일 화면, 이용약관, 개인정보처리방침 페이지
- 회원가입 시 약관·개인정보 동의 검증 및 동의 시각 저장
- Compose에 프런트엔드 서비스와 헬스체크 추가
- Nginx에서 API는 백엔드, 일반 화면은 프런트엔드로 라우팅
- 프런트엔드용 포트와 URL 환경변수
- 개발 계획서의 프런트엔드·배포 내용 보완

## 2026-07-22 검증 결과

### 통과

- 백엔드 Java 컴파일
- 백엔드 전체 테스트 68개
- 회원가입 동의 검증 단위 테스트
- 프런트엔드 `npm run lint`
- 프런트엔드 `npm run build`
- `docker compose config --quiet`

### 미확인

- 라즈베리파이 ARM64 실제 배포와 재부팅 복구 테스트는 아직 수행하지 않음

## 알려진 미완료 항목

- Prometheus, Grafana, node-exporter, cAdvisor Compose 서비스
- 백업·복구 스크립트와 실제 복구 테스트
- GitHub Actions `ci.yml`, `deploy.yml`
- GHCR ARM64 이미지 빌드와 라즈베리파이 자동 배포
- GitHub·Naver OAuth 제공자 추가
- 외부 도메인, 실제 인증서, HTTPS 운영 검증

## 다음 우선순위

1. Prometheus·Grafana 모니터링 스택을 Compose에 추가한다.
2. CI/CD 워크플로와 ARM64 라즈베리파이 배포를 구현한다.

다음 단계는 운영 상태를 확인할 수 있도록 1번 모니터링 스택을 완성하는 것이다.
