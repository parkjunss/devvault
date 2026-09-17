# DevVault 문서·미디어 편집 로드맵

> 2026-09-17: PDF 필기·이미지 편집은 파일 하나에 서버 버전을 쌓고 과거 버전으로 복원하는 방식으로 변경했다.
> 현재 범위와 검증은 [파일 편집 명세](file-editing.md)를 따른다. 아래 내용은 그 외 편집 기능의 장기 계획이다.

> 작성일: 2026-07-22
> 범위: 텍스트·Markdown, Word, Excel, 이미지, 동영상 편집
> 원칙: 원본 보호, 버전 복구, 사용자 용량 제한, ARM64 운영 가능성 우선

## 1. 목표와 제외 범위

DevVault 안에서 파일을 내려받아 외부 프로그램으로 열지 않고도 자주 쓰는 편집을 수행한다.

- 텍스트·Markdown: 내용 수정과 저장
- Word·Excel: 브라우저 기반 문서 편집과 저장
- 이미지: 자르기, 회전, 크기 조정, 밝기·대비, 포맷 변환
- 동영상: 구간 자르기, 회전, 음소거, 해상도 선택, 자막 추가
- 모든 편집 결과: 버전 생성, 이전 버전 복구, 체크섬과 용량 재계산

첫 단계에서 제외한다.

- Photoshop 수준의 레이어 편집
- Premiere 수준의 다중 트랙 타임라인
- Word·Excel 형식을 자체 구현하는 편집 엔진
- 실시간 공동 편집과 댓글

## 2. 핵심 설계

편집기마다 저장 로직을 만들지 않고 `FileRevision` 모듈을 공통 seam으로 둔다.

### FileRevision 인터페이스

```text
open(fileId, userId) -> EditableFile
save(fileId, expectedVersion, content, metadata) -> RevisionResult
restore(fileId, revisionId, userId) -> RevisionResult
list(fileId, userId) -> revisions
```

모듈 내부에서 다음을 처리한다.

- 파일 소유권과 권한 확인
- 낙관적 잠금과 편집 충돌 감지
- 임시 파일 기록 후 atomic move
- SHA-256 체크섬과 파일 크기 갱신
- 50GB 사용자 용량 제한 검사
- 현재 버전 교체와 이전 버전 보존
- 실패 시 임시 파일 정리

### 데이터 모델

`stored_files`에 현재 버전을 나타내는 `version`을 추가하고, 이력은 별도 테이블에 저장한다.

```text
file_revisions
- id
- file_id
- version
- stored_name
- content_type
- size
- checksum
- editor_type (TEXT, IMAGE, VIDEO, OFFICE)
- created_by
- created_at
```

동영상처럼 오래 걸리는 작업은 동기 요청에 넣지 않는다.

```text
edit_jobs
- id
- file_id
- job_type
- status (QUEUED, RUNNING, COMPLETED, FAILED)
- progress
- parameters_json
- output_file_id
- error_message
- created_at / completed_at
```

## 3. 공통 HTTP 인터페이스

```http
GET    /api/files/{id}/edit-capability
GET    /api/files/{id}/content
PUT    /api/files/{id}/content
GET    /api/files/{id}/revisions
POST   /api/files/{id}/revisions/{revisionId}/restore
POST   /api/files/{id}/edit-jobs
GET    /api/edit-jobs/{jobId}
```

`PUT /content`는 현재 버전을 `If-Match` 또는 `expectedVersion`으로 받아, 다른 저장이 먼저 발생했으면 `409 CONFLICT`를 반환한다.

편집 가능 여부는 확장자만 보지 않고 서버가 MIME, 파일 크기, 설치된 어댑터를 확인해 반환한다.

```json
{
  "editor": "TEXT",
  "editable": true,
  "maxEditableBytes": 5242880,
  "reason": null
}
```

## 4. 단계별 구현

### Phase 0 — 편집 저장 기반

먼저 모든 편집기가 재사용할 저장 기반을 만든다.

- `file_revisions`와 `stored_files.version` 추가
- `FileRevision` 모듈 구현
- 저장·복구·충돌·용량 제한 테스트
- 이전 버전도 사용자 저장 용량에 포함
- 현재 버전 보존 정책: 원본 포함 전부 보존하고 용량 제한 적용. 자동 정리는 별도 결정 후 도입
- 편집 전 원본 자동 버전 생성

완료 조건:

- 저장 도중 서버가 중단돼도 현재 파일이 손상되지 않는다.
- 두 탭이 동시에 저장하면 뒤늦은 저장이 `409`로 차단된다.
- 이전 버전 복구 후 체크섬과 사용량이 정확하다.

### Phase 1 — 텍스트·Markdown 편집

가장 작은 실제 편집 기능으로 공통 기반을 검증한다.

- 대상: `txt`, `md`, `json`, `yaml`, 코드 파일
- 1차 UI: 브라우저 기본 `textarea`, 저장·취소·변경 여부 표시
- UTF-8 텍스트만 허용하고 기본 최대 5MB
- Markdown은 편집/미리보기 분할 화면
- JSON 저장 전 문법 오류 표시
- 자동 저장은 제외하고 명시적 저장부터 구현

완료 조건:

- 큰 화면 미리보기에서 편집 모드로 전환할 수 있다.
- 저장, 충돌 안내, 버전 복구가 동작한다.
- HTML 파일은 실행하지 않고 항상 일반 텍스트로 편집한다.

### Phase 2 — 이미지 간단 편집

서버 부하를 줄이기 위해 브라우저 Canvas를 우선 사용한다.

- 자르기, 90도 회전, 좌우 반전
- 가로·세로 크기 조정
- 밝기·대비
- JPEG·PNG·WebP 출력과 품질 선택
- 원본 덮어쓰기 또는 새 파일로 저장 선택
- EXIF 위치 정보는 기본 제거

완료 조건:

- 편집 결과가 `FileRevision`을 통해 저장된다.
- 저장 전 예상 파일 크기와 출력 형식을 확인할 수 있다.
- 브라우저 메모리 한도를 넘는 이미지는 서버 편집 또는 다운로드 편집을 안내한다.

### Phase 3 — 동영상 간단 편집

동영상은 브라우저 Blob 전체 로딩 대신 백엔드 FFmpeg 작업으로 처리한다.

선행 작업:

- 인증된 HTTP Range 스트리밍
- FFmpeg가 포함된 ARM64 백엔드 이미지
- 작업 큐와 진행률 조회
- 임시 작업 공간과 실패 파일 정리

1차 기능:

- 시작·종료 구간 자르기
- 회전과 음소거
- 1080p·720p·480p 출력 선택
- WebVTT 자막 유지 또는 영상에 입히기
- MP4(H.264/AAC) 표준 출력

2차 기능:

- 화면 자르기
- 썸네일 선택
- 여러 구간 이어 붙이기
- HLS 변환과 화질별 스트리밍

완료 조건:

- FFmpeg 인자를 문자열로 직접 받지 않고 서버가 허용된 옵션만 조합한다.
- 작업 진행률, 취소, 실패 원인이 UI에 표시된다.
- 작업 완료 전 원본은 변경되지 않는다.

### Phase 4 — Word·Excel 편집

DOCX/XLSX 형식을 직접 구현하지 않고 Office 편집 서버 어댑터를 붙인다.

후보:

1. ONLYOFFICE Document Server: Word·Excel 호환성과 통합 문서 편집이 강점
2. Collabora Online: LibreOffice 기반, 자체 호스팅과 ODF 지원이 강점

선행 게이트:

- 공식 ARM64 이미지 또는 라즈베리파이에서 유지 가능한 빌드 존재 여부
- 최소 메모리·CPU와 실제 문서 열기 시간
- 라이선스 조건
- 한국어 폰트와 DOCX/XLSX 왕복 저장 품질

ARM64 운영이 어렵다면 Office 편집 서버만 x86 서버나 외부 클라우드에 분리하고, DevVault 파일 저장은 기존 라즈베리파이에 유지한다.

통합 흐름:

1. 프론트가 `/api/files/{id}/office-session` 요청
2. 백엔드가 짧은 수명의 편집 토큰과 문서 URL 생성
3. Office 어댑터가 문서를 열고 편집
4. 저장 callback을 백엔드가 검증
5. 결과를 `FileRevision`으로 저장

보안 요구:

- Office 서버가 물리 저장 경로에 직접 접근하지 않음
- 세션 토큰에 파일 ID, 사용자 ID, 만료 시각 포함
- callback 서명과 송신지 검증
- 외부 URL fetch 차단으로 SSRF 방지
- 문서 다운로드 URL은 짧은 만료와 1회성 토큰 사용

완료 조건:

- DOCX 문단·표·이미지와 XLSX 수식·서식이 왕복 저장 후 유지된다.
- 다른 사용자의 편집 세션과 callback을 사용할 수 없다.
- Office 저장 결과도 버전 복구와 사용자 용량 제한을 따른다.

### Phase 5 — 공동 편집과 운영

기본 편집이 안정된 뒤 추가한다.

- 편집 중 사용자 표시와 잠금
- Office 제공자의 공동 편집 기능
- 텍스트 공동 편집이 필요하면 Yjs/WebSocket 검토
- 관리자 작업 큐, 실패율, 처리 시간, 임시 디스크 모니터링
- 버전 자동 정리와 관리자 보존 정책

## 5. 프론트엔드 구조

현재 큰 화면 미리보기에 편집 진입점만 추가하고 별도 파일 화면을 복제하지 않는다.

```text
LargePreview
  -> EditLauncher
      -> TextEditor
      -> ImageEditor
      -> VideoJobEditor
      -> OfficeEditorFrame
  -> RevisionPanel
```

`EditLauncher`는 `/edit-capability` 결과에 따라 하나의 편집기만 선택한다. 각 편집기는 저장을 직접 구현하지 않고 공통 저장 인터페이스를 호출한다.

## 6. 보안·운영 체크리스트

- 파일명이나 MIME만 신뢰하지 않고 magic bytes 검사
- HTML·SVG는 실행 가능한 문서로 렌더링하지 않음
- 편집 결과도 업로드와 동일한 소유권·용량·중복 검증 적용
- FFmpeg와 Office callback에 사용자 입력 명령 전달 금지
- 임시 파일은 작업별 격리 디렉터리와 만료 정리 사용
- 편집·복구·Office callback을 감사 로그에 기록
- 버전 파일과 임시 파일을 백업·복구 범위에 포함
- 편집 서비스의 CPU·메모리·디스크 사용량 제한

## 7. 권장 실행 순서

| 순서 | 작업 | 난이도 | 의존성 |
|---|---|---:|---|
| 1 | FileRevision·버전 복구 기반 | 높음 | 없음 |
| 2 | 텍스트·Markdown 편집 | 낮음 | 1 |
| 3 | 이미지 Canvas 편집 | 중간 | 1 |
| 4 | Range 스트리밍·FFmpeg 작업 큐 | 높음 | 1 |
| 5 | 동영상 자르기·출력 설정 | 높음 | 4 |
| 6 | Office ARM64 기술 검증 | 중간 | 없음 |
| 7 | Word·Excel 어댑터 | 매우 높음 | 1, 6 |
| 8 | 공동 편집·운영 자동화 | 매우 높음 | 2~7 |

첫 구현 범위는 **Phase 0 + Phase 1**로 고정한다. 여기서 저장 안정성과 버전 복구를 검증한 뒤 이미지와 동영상으로 확장한다.

## 8. 결정이 필요한 항목

- 버전 보존 개수와 과금 용량 포함 정책
- 편집 저장 시 원본 덮어쓰기 기본값 또는 새 파일 생성 기본값
- Office 서버를 라즈베리파이에 둘지 별도 x86 서버에 둘지
- ONLYOFFICE와 Collabora 중 ARM64·라이선스·호환성 검증 결과
- 동영상 최대 입력 크기와 최대 처리 시간
- 실제 결제 후 용량 증설 자동화 범위
