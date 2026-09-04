# Agent Studio — Python 백엔드 (Agent Appstore 배포판)

Java·Spring Boot 판(`../src`)과 **같은 API, 같은 PostgreSQL 스키마, 같은 프론트엔드**를
쓰는 FastAPI 판입니다. 차이는 하나뿐입니다: **생성·실행되는 코드가 Java가 아니라 Python**
입니다. 에이전트를 내보내면 JBang이 아니라 `uv run`으로 도는 파일이 나옵니다.

한 프로세스가 두 몫을 합니다 — `/api` 아래의 JSON API와, 나머지 전부를 받는 정적 프론트엔드.

---

## 경로 규약 — 이 문서에서 가장 중요한 부분

배포된 앱이 죽는 자리는 거의 항상 여기입니다. 로컬에서도 테스트에서도 멀쩡하고,
`/apps/<앱ID>/` 뒤에 놓았을 때만 화면이 빈 껍데기로 뜹니다.

| 무엇 | 규칙 | 왜 |
|---|---|---|
| 서버의 라우트 정의 | **절대경로** (`@router.get("/api/agents")`) | 서버 자신의 경로. `root_path`가 서브패스를 붙여준다 |
| 브라우저가 부르는 주소 | **상대경로**, 또는 `BASE_PATH`가 박힌 절대경로 | `/`로 시작하면 앱이 아니라 **플랫폼 루트**로 간다 |
| 이 백엔드가 직접 내는 HTML | `<base href="{ROOT_PATH}/">` + 상대 링크 | 위와 같은 이유 (`main.py`의 `_placeholder()`) |

Next.js 정적 export는 asset 주소를 **빌드 시점에** 확정합니다. 런타임에 자기 마운트 위치를
알아낼 방법이 없으므로, 빌드할 때 `BASE_PATH`로 서브패스를 박아 넣습니다.

> **그래서 앱 ID를 바꾸면 프론트엔드를 다시 빌드해야 합니다.** 이것이 이 방식의 유일한
> 대가입니다. 아래 빌드 명령의 `agent-studio` 부분을 새 ID로 바꾸고 다시 빌드·업로드하세요.

`main.py`의 `strip_root_path` 미들웨어는 보험입니다. 프록시가 마운트 접두사를 벗겨 주는 것이
표준(ASGI `root_path`)이지만, 벗기지 않고 그대로 넘기는 프록시를 만나도 앱이 뜨도록
요청 경로를 한 번 정규화합니다. 이게 없으면 페이지가 부르는 스크립트마다 404가 나고,
화면은 뼈대만 뜬 채 앱 로그에는 아무 흔적도 남지 않습니다.

---

## 빌드와 실행

### 1. 프론트엔드를 `backend/web`에 빌드

PowerShell:

```powershell
cd ..\frontend
$env:BUILD_STATIC = "1"
$env:BASE_PATH = "/apps/langgraph-studio"            # 배포될 앱 ID로 맞출 것
$env:NEXT_PUBLIC_API_BASE_URL = "/apps/langgraph-studio"
npx next build
Remove-Item -Recurse -Force ..\backend\web -ErrorAction SilentlyContinue
Copy-Item -Recurse out ..\backend\web
```

로컬 루트(서브패스 없이)에서 돌릴 때는 `BASE_PATH`를 비우고
`NEXT_PUBLIC_API_BASE_URL`을 `/`로 주세요 — PowerShell은 빈 환경변수를 지워버리기 때문에
`"/"`가 루트를 뜻하는 약속입니다(`frontend/src/api/client.ts` 참고).

**빌드 직후에는 번들을 한 번 보정하고 검사하세요** (`package.py`가 자동으로 합니다):

```powershell
cd ..\backend
uv run python patch_bundle.py   # Next 내장 에러 화면의 루트 이동을 앱 마운트로 교정
uv run python check_paths.py    # 플랫폼 업로드 검사와 동일한 검사
```

`check_paths.py`는 브라우저가 부르는 절대 경로 중 마운트 경로 밖으로 나가는 것을 찾습니다 —
플랫폼이 업로드할 때 하는 검사와 같은 것이라, 거절당하고 다시 올리는 왕복을 없앱니다.

가장 흔한 원인은 **생 `<a href="/...">`** 입니다. `basePath`는 `next/link`에만 적용되고
평범한 앵커에는 붙지 않으므로, 자산 URL은 멀쩡한데 그 링크 하나만 플랫폼 루트로 나갑니다.
`<Link>`를 쓰세요.

### 2. 백엔드 실행

```powershell
cd ..\backend
$env:PORT = "8099"
$env:ROOT_PATH = "/apps/langgraph-studio"
$env:APP_DATA_DIR = "$PWD\.appdata"
$env:DB_URL = 'postgresql://postgres:mobigen12#$@192.168.105.3:5432/postgres'
$env:LLM_BASE_URL = "http://192.168.109.254:32609/v1"
$env:LLM_MODEL = "google/gemma-4-31B-it"
uv run python main.py
```

**`DB_URL`은 반드시 따옴표로 감싸세요.** libpq는 URI 안의 `#`·`$`를 그대로 잘 읽으므로
퍼센트 인코딩은 필요 없습니다 — 문제는 셸입니다. 따옴표 없이 쓰면 PowerShell이 `#`부터를
주석으로 잘라내고, 값은 `postgresql://postgres:mobigen12`에서 끝납니다. 그러면 서버는
`password authentication failed`라고 답하고, 이건 **비밀번호가 틀린 것과 구별되지 않습니다.**

그래서 기동 로그가 파싱된 결과를 먼저 찍습니다:

```
connecting to PostgreSQL as postgres@192.168.105.3:5432/postgres password=set, 11 chars
```

`password=MISSING`이거나 글자 수가 안 맞으면 따옴표 문제이지 비밀번호 문제가 아닙니다.
플랫폼에는 변수를 되짚어볼 셸이 없으니, 이 한 줄이 그걸 가르는 유일한 단서입니다.

브라우저에서 `http://127.0.0.1:8099/apps/langgraph-studio/` 를 여세요. 미들웨어 덕분에
이 주소가 배포 형태와 똑같이 동작하므로, **서브패스 문제를 로컬에서 그대로 재현**할 수
있습니다. 루트(`/`)로도 열립니다.

---

## 업로드용 zip 만들기

```powershell
uv run python package.py
```

`dist\<pyproject의 name>.zip`이 나옵니다. zip 안의 경로가 `main.py`이지 `backend/main.py`가
아니어야 하는데, 스크립트가 그것을 보장합니다 — 폴더째 압축하면 조각 업로드가 기존 파일을
덮어쓰지 못하고 엉뚱한 곳에 새로 생깁니다.

`web/`이 비어 있으면 스크립트가 거부합니다. 화면 없는 앱이 조용히 배포되는 것보다 낫습니다.

---

## 플랫폼 설정 (`pyproject.toml`의 `[tool.appstore]`)

| 항목 | 값 | 비고 |
|---|---|---|
| `env` | `["DB_URL"]` | 배포 화면에서 입력. 없으면 기동에서 실패하고 그 이유를 로그에 남긴다 |
| `llm` | `true` | `LLM_BASE_URL`·`LLM_MODEL`이 주입된다. **API 키는 필요 없다** — 게이트웨이가 처리 |
| `type` | `web` | 화면이 있다 |

`APP_DATA_DIR`은 이 앱이 쓸 수 있는 **유일한** 디렉토리입니다. 업로드 스풀 파일이
`APP_DATA_DIR/uploads`로 가는 이유가 그것입니다 — `tempfile`의 기본 경로는 이 앱 계정이
쓸 수 없습니다.

---

## API

Java 판과 동일합니다. 프론트엔드는 두 백엔드 어느 쪽에도 그대로 붙습니다.

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| GET/POST | `/api/agents` | 목록 / 생성 |
| GET/PUT/DELETE | `/api/agents/{id}` | 조회 / 수정 / 삭제 |
| GET | `/api/tools` | 내장 툴 목록 |
| GET/POST | `/api/agents/{id}/documents` | 첨부 PDF 목록 / 업로드 |
| DELETE | `/api/agents/{id}/documents/{docId}` | 첨부 삭제 |
| GET/PUT | `/api/agents/{id}/code` | 소스 조회 / 편집 저장 (`text/plain`) |
| GET | `/api/agents/{id}/code/status` | 편집 여부만 |
| POST | `/api/agents/{id}/code/regenerate` | 편집을 버리고 정의에서 다시 생성 |
| GET | `/api/agents/{id}/graph` | 실제로 실행될 그래프 |
| GET | `/api/agents/{id}/source-spec` | 소스에서 되읽은 정의 |
| POST | `/api/agents/{id}/run` | 실행 (SSE) |

코드 엔드포인트는 본문이 평문이고 메타데이터가 헤더(`X-Code-Edited`,
`X-Suggested-Filename`)에 있습니다. 브라우저는 노출 목록에 없는 응답 헤더를 숨기므로,
`main.py`의 CORS 설정에서 이 둘을 이름으로 지정합니다.

SSE 이벤트: `token` · `tool_call` · `tool_result` · `done` · `error`.

---

## 생성되는 코드

`app/templates/`에 있습니다. 나오는 파일은 PEP 723 인라인 메타데이터를 달고 있어
아무 준비 없이 단독 실행됩니다:

```bash
uv run my_agent.py "(17 * 23) + 5 는 얼마야?"
```

`uv`가 헤더를 읽고 필요한 패키지를 스스로 설치합니다. Java 판의 JBang `//DEPS`와 같은 역할.

> **PEP 723 블록에 빈 줄을 넣지 마세요.** 블록은 `#`로 시작하지 않는 첫 줄에서 끝납니다.
> 빈 줄 하나가 닫는 `# ]`와 `# ///`를 블록 밖으로 밀어내고, `uv run`은 의존성을 못 읽은 채
> 첫 import에서 죽습니다. 스튜디오는 헤더를 읽지 않으므로 **내보낸 파일에서만** 드러납니다.
> `codegen._tool_deps`가 그래서 자기 줄바꿈까지 책임집니다.

**서버가 실행하는 것도 이 파일입니다.** 정의에서 그래프를 따로 조립하는 두 번째 경로는
없습니다 — Java 판에서 그 두 경로가 두 번 어긋났고, 그래서 하나로 합쳤습니다.
`runner.py`는 소스를 `exec`해 모듈로 만들고 `build_graph(model)`을 이름으로 찾습니다.
상속할 기반 클래스를 두지 않은 이유는, 그것이 있으면 내보낸 파일이 단독으로 못 돌기
때문입니다.

### 툴 추가하기

`app/tools.py`에 항목 하나, `app/templates/tools/<이름>.py.template`에 구현 하나.
등록할 곳은 없습니다. 템플릿을 빠뜨리면 `verify_templates()`가 **기동을 실패**시킵니다 —
누군가 그 툴을 처음 골랐을 때 400으로 알게 되는 것보다 낫습니다.

---

## Java 판과 다른 점

| | Java | Python |
|---|---|---|
| 실행 방식 | javac로 런타임 컴파일 + 리플렉션 | `compile()` + `exec()` + 속성 조회 |
| 내보낸 파일 | JBang `//DEPS` | PEP 723 + `uv run` |
| PDF 파싱 | PDFBox | pypdf |
| 서버 요구사항 | **JDK** (JRE 불가), fat jar 불가 | 없음 |

문서 검색은 두 판이 완전히 같습니다 — 같은 두 단계(전문검색 → ILIKE 폴백), 같은 이유.
`to_tsvector('simple', ...)`는 한국어를 공백으로만 끊어서 조사가 붙은 낱말을 놓칩니다.
말뭉치에 `스프링을`이 있는데 질의가 `스프링`이면 다른 어휘소입니다. 그래서 두 번째 패스는
질의를 낱말로 쪼개 OR로 묶고, 몇 개가 맞았는지로 순위를 매깁니다. 질의 전체를 한 덩어리로
매칭하면 자연어 질문은 하나도 찾지 못합니다.

---

## 주의할 점

- **에이전트 코드 편집은 서버 프로세스 안에서의 임의 코드 실행입니다.** MVP가 인증 없이
  열려 있다는 전제와 같은 선택입니다. 공개 배포 전에 인증을 붙이세요.
- 대화 이력은 `MemorySaver`라 프로세스 메모리에만 있습니다. 재배포하면 사라집니다.
- `web_search`는 아직 스텁입니다.

cd D:\dev\work\graphTemp\graph-agent\backend
$env:DB_URL = 'postgresql://postgres:mobigen12#$@192.168.105.3:5432/postgres'
$env:LLM_BASE_URL = "http://192.168.109.254:32609/v1"
$env:LLM_MODEL = "google/gemma-4-31B-it"
$env:PORT = "8099"    
uv run python main.py