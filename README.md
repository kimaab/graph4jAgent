# Agent Studio

브라우저에서 폼으로 LangGraph 에이전트를 정의하고, 서버가 그 정의로 그래프를 만들어 실행하며,
같은 정의로 **독립 실행 가능한 파일**을 생성해 내려주는 MVP입니다.

노드 캔버스/드래그앤드롭은 범위에 없습니다.

> **백엔드가 둘 있습니다.**
> 이 문서가 설명하는 것은 원래의 **Java · Spring Boot** 판(`src/`, `pom.xml`)입니다.
> Agent Appstore에 올리기 위한 **Python · FastAPI** 판이 `backend/`에 있습니다 —
> 같은 API, 같은 PostgreSQL 스키마, 같은 프론트엔드를 쓰고, 화면까지 한 앱으로 묶어
> 배포합니다. 그쪽은 [`backend/README.md`](backend/README.md)를 보세요.

---

## 스택

| | |
|---|---|
| 백엔드 | Java 21 · Spring Boot 4.1.1 · langgraph4j 1.8.24 · Spring AI 2.0.1 · PDFBox 3.0.3 |
| 프론트 | Next.js 16 (App Router) · TypeScript · Tailwind CSS · axios · zustand |
| DB | PostgreSQL (스펙 저장) |
| 모델 | OpenAI 호환 게이트웨이 (vLLM) |

대화 상태는 langgraph4j `MemorySaver`에 **인메모리**로 보관합니다. 서버를 재시작하면
모든 thread가 초기화됩니다 — MVP 범위에서는 의도된 동작입니다.

---

## 준비물

- **JDK 21 이상** (langgraph4j가 17+ 요구, 프로젝트는 21로 컴파일)
- **Node.js 20 이상** (Next.js 16)
- 접근 가능한 **PostgreSQL**
- OpenAI 호환 **LLM 게이트웨이** — tool calling을 지원해야 `react` 그래프가 동작합니다

---

## 설정

기본값은 `src/main/resources/application.yml`에 있고, 전부 환경변수로 덮어쓸 수 있습니다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `192.168.105.3` / `5432` / `postgres` | PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / — | 접속 계정 |
| `UPLOAD_MAX_FILE_SIZE` | `300MB` | PDF 한 개 최대 크기 |
| `UPLOAD_MAX_REQUEST_SIZE` | `320MB` | 요청 전체 최대 크기 (멀티파트 봉투 여유분 포함) |
| `LLM_BASE_URL` | `http://192.168.109.254:32609/v1` | **`/v1`까지 포함해야 합니다** (아래 함정 참고) |
| `LLM_API_KEY` | `test_api_key` | 게이트웨이 키 |
| `LLM_MODEL` | `google/gemma-4-31B-it` | 새 에이전트의 기본 모델 |

> 운영에서는 비밀번호와 API 키를 `application.yml`에 두지 말고 환경변수로 주입하세요.

테이블은 부팅 시 `schema.sql`이 `CREATE TABLE IF NOT EXISTS`로 만듭니다. 마이그레이션 도구는 없습니다.

프론트엔드는 `frontend/.env.local`:

```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

---

## 실행

**백엔드** (`:8080`)

```powershell
# IntelliJ: GraphTempApplication 실행
# 또는 Maven 이 PATH 에 있다면
mvn spring-boot:run
```

**프론트엔드** (`:3000`)

```powershell
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:3000` → `/agents` 로 이동합니다.

---

## 화면

| 경로 | 하는 일 |
|---|---|
| `/agents` | 목록, 생성, 삭제 |
| `/agents/{id}` | 에디터. 좌측 폼(이름·모델·시스템 프롬프트·그래프 종류), 우측 툴 체크박스·PDF 첨부·`linear` 단계 편집. 「코드 보기」 탭에서 **소스를 직접 수정**하고 저장 |
| `/agents/{id}/run` | 채팅 테스트. 토큰 스트리밍, 툴 호출은 접을 수 있는 블록. `thread_id`는 진입 시 생성되고 「새 대화」로 갱신 |

저장 시 서버 검증 실패는 **필드별로** 해당 입력 아래에 표시됩니다.

---

## 에이전트 스펙

단일 진실 원천. 통신 형식은 snake_case입니다.

```json
{
  "id": "uuid",
  "name": "계산 도우미",
  "description": "",
  "model": "google/gemma-4-31B-it",
  "system_prompt": "너는 계산을 도와주는 조수다.",
  "tools": ["calculator"],
  "graph_type": "react",
  "steps": [],
  "max_iterations": 5
}
```

- `graph_type`은 `react` \| `linear` 만 허용합니다.
- `steps`는 `linear`일 때만 쓰이며, 비어 있으면 저장이 거부됩니다.
- `id`는 서버가 부여합니다. POST 본문의 `id`는 무시되고, PUT은 경로의 값을 씁니다.

### 그래프 종류

- **react** — `agent` 노드가 모델을 호출하고, 툴 호출이 있으면 `tools` 노드가 실행한 뒤 다시 `agent`로 돌아옵니다. `max_iterations`가 이 루프의 상한입니다.
- **linear** — `steps`를 순서대로 실행합니다. 각 단계는 이전 단계들의 출력을 프롬프트에 이어받습니다. 루프가 없어 툴을 쓰지 않습니다.

### 실행되는 것은 「코드 보기」에 있는 그 파일입니다

스펙에서 그래프를 직접 조립하던 `GraphBuilder` 는 없앴습니다. 실행할 때마다 서버는

1. 저장된 편집본이 있으면 그것을, 없으면 스펙으로 소스를 렌더하고
2. `JavaCompiler` 로 **메모리에서 컴파일**한 뒤 (서버 자신의 클래스패스에 대고)
3. 생성 클래스의 `static CompiledGraph<State> buildGraph(ChatModel, boolean)` 을 **반사로** 호출합니다

공유 인터페이스를 쓰지 않고 반사를 쓰는 이유는, 그 인터페이스가 클래스패스에 없는
JBang 독립 실행을 깨뜨리지 않기 위해서입니다.

경로가 하나뿐이라 내보낸 파일과 스튜디오가 어긋날 수 없습니다. 컴파일 결과는 **소스 다이제스트
기준으로 캐시**되고, 코드를 고치면 새 그래프가 되므로 대화는 초기화됩니다.

컴파일이 실패하면 javac 진단이 `compile_errors` 로 400 응답에 실려 코드 탭에 그대로 뜹니다.

> **생성 코드가 import 하는 라이브러리는 전부 서버의 런타임 의존성이어야 합니다.**
> 서버가 자기 클래스패스에 대고 컴파일하기 때문입니다 — 서버 자신은 절대 호출하지 않는
> `openai-java-client-okhttp` 같은 것도 포함입니다. 이게 `test` 스코프였을 때 테스트는
> 전부 통과하는데 실제 실행은 전부 컴파일 실패했습니다. 테스트는 test 스코프 jar를
> 클래스패스에 두고 돌지만 서버는 아니기 때문입니다.
> 부팅 시 `StartupCompileCheck` 가 빈 에이전트를 한 번 컴파일해서 이걸 즉시 잡습니다.

> 서버에 **JDK가 필요합니다** (JRE 아님). 그리고 `java.class.path` 가 실제 클래스패스여야 하므로
> **Spring Boot fat jar 로 패키징하면 동작하지 않습니다** — jar 안의 `BOOT-INF/lib` 을 javac가 못 봅니다.

### 내장 툴

| 이름 | 설명 |
|---|---|
| `calculator` | 사칙연산·괄호·단항부호. **직접 짠 재귀하강 파서**를 씁니다 — 스크립트 엔진을 쓰면 모델에게 임의 코드 실행을 넘겨주게 됩니다 |
| `http_get` | http(s) URL 본문을 텍스트로. 10초 타임아웃, 8000자 절단 |
| `web_search` | 스텁. 인터페이스는 진짜고 본문만 "미연결" 응답을 돌려줍니다 |
| `document_search` | 에이전트에 첨부한 PDF에서 검색. 파일명과 **페이지 번호**를 붙여 발췌를 돌려줍니다 |

**툴 추가하는 법** — 클래스 하나와 템플릿 하나면 끝입니다. 등록할 곳은 없습니다.

1. `tools/XxxTool.java` 를 `BuiltinTool` 상속으로 만들고 `@Component` 를 붙입니다
2. `templates/tools/{이름}.java.template` 에 독립 실행판을 씁니다
3. 끝. `ToolRegistry` 가 Spring 빈으로 자동 수집하고, 생성자 표현식과 템플릿 경로는
   이름 규칙에서 유도됩니다 (`CalculatorTool` → `tools/calculator.java.template` → `new Calculator()`)

규칙을 벗어나야 하면 `codegenClassName()` / `codegenTemplate()` / `codegenConstructor()` 를
재정의하고, 툴이 별도 라이브러리를 쓰면 `codegenDependencies()` 로 `//DEPS` 줄을 요청합니다.
템플릿을 빠뜨리면 **부팅이 실패합니다** — 예전엔 그 툴을 쓴 에이전트를 내보낼 때서야 400이 났습니다.

---

## API

| 메서드 | 경로 | |
|---|---|---|
| `POST` | `/api/agents` | 생성 (201) |
| `GET` | `/api/agents` | 목록 |
| `GET` | `/api/agents/{id}` | 단건 |
| `PUT` | `/api/agents/{id}` | 수정 |
| `DELETE` | `/api/agents/{id}` | 삭제 (204) |
| `POST` | `/api/agents/{id}/run` | 실행, **SSE 스트리밍** |
| `GET` | `/api/agents/{id}/code` | 에이전트 소스 (text/plain). `X-Code-Edited` 헤더로 편집본인지 표시 |
| `PUT` | `/api/agents/{id}/code` | 소스 저장. 이후로는 이 코드가 실행됩니다 |
| `POST` | `/api/agents/{id}/code/regenerate` | 편집을 버리고 정의에서 다시 생성 |
| `GET` | `/api/agents/{id}/documents` | 첨부 문서 목록 |
| `POST` | `/api/agents/{id}/documents` | PDF 업로드 (multipart, `file`). 업로드 시점에 텍스트 추출 |
| `DELETE` | `/api/agents/{id}/documents/{docId}` | 삭제 (204) |
| `GET` | `/api/tools` | 내장 툴 목록 (이름·설명·파라미터 스키마) |

에러는 전부 `{"detail": "..."}` 형태입니다. 폼이 고칠 수 있는 검증 실패는 `errors` 맵이 함께 옵니다:

```json
{
  "detail": "spec validation failed",
  "errors": { "name": "must not be blank", "steps": "a linear graph needs at least one step" }
}
```

### SSE 이벤트

`POST /api/agents/{id}/run` 은 body `{"message": "...", "thread_id": "..."}` 를 받아 다음을 흘립니다.

| 이벤트 | 데이터 |
|---|---|
| `token` | `{"text": "조각"}` |
| `tool_call` | `{"id": "...", "name": "calculator", "arguments": "{...}"}` |
| `tool_result` | `{"id": "...", "name": "calculator", "result": "600"}` |
| `done` | `{"thread_id": "..."}` |
| `error` | `{"detail": "..."}` |

같은 `thread_id`로 다시 호출하면 대화가 이어집니다. 툴 이벤트는 **해당 턴에 새로 생긴 것만** 나갑니다.

> `EventSource`는 GET만 지원하고 헤더를 못 붙이므로, 프론트엔드는 `fetch` + `ReadableStream`으로
> SSE 프레이밍을 직접 파싱합니다 (`frontend/src/api/client.ts`).

---

## PowerShell 예제

> Windows PowerShell 5.1 기준입니다. **`Invoke-RestMethod`를 파이프에 직접 물리지 마세요** — 아래 함정 참고.

```powershell
$base = "http://localhost:8080"

function Send-Json($Method, $Uri, $Obj) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Uri $Uri -Method $Method -Body $bytes -ContentType "application/json; charset=utf-8"
}
```

**에이전트 생성**

```powershell
$agent = Send-Json POST "$base/api/agents" @{
    name           = "계산 도우미"
    model          = "google/gemma-4-31B-it"
    system_prompt  = "너는 계산을 도와주는 조수다. 계산이 필요하면 calculator 툴을 써라."
    tools          = @("calculator")
    graph_type     = "react"
    max_iterations = 5
}
$id = $agent.id
```

**실행 (SSE)** — `Invoke-RestMethod`는 스트리밍을 못 보여주므로 `curl.exe`를 씁니다.

```powershell
@{ message = "(123 + 77) * 3 은 얼마야?"; thread_id = "t1" } |
    ConvertTo-Json | Out-File -Encoding utf8 "$env:TEMP\run.json"

curl.exe -N -X POST "$base/api/agents/$id/run" `
    -H "Content-Type: application/json" --data-binary "@$env:TEMP\run.json"
```

**생성 코드 받기**

```powershell
Invoke-RestMethod "$base/api/agents/$id/code" | Out-File -Encoding utf8 "$env:TEMP\Agent.java"
```

**목록 순회 / 삭제** — 변수에 담고 `foreach`를 씁니다.

```powershell
$agents = Invoke-RestMethod "$base/api/agents"
foreach ($a in $agents) { Invoke-RestMethod "$base/api/agents/$($a.id)" -Method Delete }
```

---

## 코드 생성

`GET /api/agents/{id}/code` 는 **단일 Java 파일**을 돌려줍니다. 서버가 실행하는 것과 같은
라이브러리(langgraph4j, Spring AI)와 같은 툴 구현을 쓰므로 동작이 일치합니다.

파일 상단의 `//DEPS` 주석 덕분에 [JBang](https://www.jbang.dev/)으로 한 줄 실행됩니다.

```powershell
# JBang 설치 (한 번만)
iex "& { $(iwr https://ps.jbang.dev) } app setup"

# 새 터미널에서
jbang "$env:TEMP\Agent.java" "(123 + 77) * 3 은 얼마야?"
```

게이트웨이 주소와 키는 파일에 기본값으로 박히지만, `LLM_BASE_URL` / `LLM_API_KEY`
환경변수로 덮어쓸 수 있습니다.

템플릿은 `src/main/resources/templates/` 에 있고 재컴파일 없이 수정할 수 있습니다.

---

## 테스트

```powershell
mvn test          # 또는 IntelliJ 에서 src/test/java 우클릭 → Run Tests
```

38개. 커버하는 것:

- `GraphBuilderTest` — react 툴 루프, linear 단계 이어받기, `max_iterations` 상한, 스펙 캐시
- `StreamingGraphTest` — 토큰 조각이 합쳐져 원문이 되는지, 스트리밍 중 툴 활동
- `CalculatorToolTest` — 계산 정확도 + `System.exit(0)` 같은 입력이 파싱 거부되는지
- `ToolRegistryTest` — 이름 조회, 모르는 툴 거절
- `CodeGeneratorTest` — 생성 결과의 구조와 이스케이프
- `GeneratedCodeCompilesTest` — **생성된 코드를 실제 컴파일러로 컴파일**

모델 호출은 `StubChatModel`로 대체하므로 테스트에 네트워크가 필요 없습니다.
마지막 항목이 특히 중요합니다 — 문자열 단언으로는 깨진 이스케이프나 없는 생성자를 못 잡고,
실제로 두 번 다 그런 버그가 났습니다.

---

## 알아두면 좋은 함정

개발하면서 실제로 부딪힌 것들입니다.

**Spring Boot 4는 Jackson 3를 씁니다.** 패키지가 `tools.jackson.*`이고
`com.fasterxml.jackson.databind`가 아닙니다. Jackson 자동설정도 `spring-boot-autoconfigure`에서
`spring-boot-jackson` 모듈로 분리됐습니다. `Jackson2ObjectMapperBuilderCustomizer`는 없고
`JsonMapperBuilderCustomizer`를 씁니다. 애노테이션(`@JsonValue` 등)만 여전히
`com.fasterxml.jackson.annotation` 2.x입니다.

**`LLM_BASE_URL`에 `/v1`이 필요합니다.** Spring AI 2.0의 OpenAI 모듈은 공식 `openai-java` SDK
위에 얹혀 있고, 그 SDK의 `baseUrl`은 버전 세그먼트를 포함합니다(기본값 `https://api.openai.com/v1`).
`/v1`을 빼면 `/chat/completions`를 때려서 게이트웨이가 `404 {"detail":"Not Found"}`를 돌려줍니다.

**PowerShell 5.1에서 `Invoke-RestMethod`를 파이프에 직접 물리면** JSON 배열이 한 덩어리로 넘어갑니다.

```powershell
Invoke-RestMethod $url | ForEach-Object { $_.id }   # 1회 반복, $_ 는 Object[]
$r = Invoke-RestMethod $url; foreach ($a in $r) { $a.id }   # 정상
```
전자로 삭제 루프를 돌리면 UUID가 전부 이어붙어 `400 'id' is not a valid UUID`가 납니다.

**응답에 `charset=UTF-8`을 붙입니다.** 안 붙이면 PS 5.1이 ISO-8859-1로 디코딩해 한글이 깨져 보입니다
(`config/WebConfig.java`). 저장된 데이터는 멀쩡하고 표시만 깨지는 문제입니다.

**대화 상태는 인메모리입니다.** 재시작하면 모든 thread가 사라집니다. 유지가 필요하면
`langgraph4j-postgres-saver`로 교체하면 됩니다 — 이미 PostgreSQL을 쓰고 있어 크게 어렵지 않습니다.

**Postgres `ts_headline` 에서 빈 마커는 따옴표가 필요합니다.** `StartSel=,StopSel=` 이라고 쓰면
Postgres가 `,StopSel=` 을 StartSel 의 *값* 으로 읽어서, 발췌문 안에 그 문자열이 그대로 박히고
종료 마커는 기본값 `</b>` 가 남습니다. `StartSel="",StopSel=""` 이 맞습니다.

**한글은 전문검색이 조사에서 놓칩니다.** `to_tsvector('simple', ...)` 는 공백으로만 자르므로
`스프링을` 과 `스프링` 은 다른 렉심입니다. `document_search` 가 전문검색으로 먼저 랭킹하고
결과가 없을 때만 `ILIKE` 로 다시 훑는 2단 구조인 이유입니다.

**큰 업로드는 힙이 아니라 디스크로 흘려야 합니다.** `MultipartFile.getBytes()` 는 파일 전체를
`byte[]` 로 올리고, `Loader.loadPDF(byte[])` 는 그 위에서 파싱합니다. 274MB 파일을 힙 256MB 에서
돌려보면 옛 경로는 **OutOfMemoryError**, 임시 파일로 흘리는 지금 경로는 3.6초에 통과합니다.
업로드는 `Files.copy` 로 임시 파일에 받고, PDFBox 에는 `IOUtils.createTempFileOnlyStreamCache()`
를 물려 자기 작업 데이터도 디스크에 두게 합니다.

한도만 올리고 이걸 안 하면, 깔끔한 413 이 서버 전체를 죽이는 OOM 으로 바뀝니다.
추출된 **텍스트** 는 여전히 메모리에 모이므로 페이지 수에 5,000 상한이 있습니다.

**업로드 한도는 컨트롤러가 아니라 컨테이너가 정합니다.** Spring Boot 기본값은 파일당 **1MB**라
평범한 PDF도 거부됩니다. Tomcat이 컨트롤러 진입 **전에** 잘라내므로 컨트롤러 안의 크기 검사는
절대 실행되지 않습니다 — `spring.servlet.multipart.max-file-size` 가 유일한 진실입니다.
초과분은 `MaxUploadSizeExceededException` 으로 오고, 처리하지 않으면 클래스명이 그대로 노출되는
500이 됩니다.

**Spring AI 2.0에는 `internalToolExecutionEnabled`가 없습니다.** `ChatModel.call()`이 툴을
자동 실행하지 않으므로, 모델 옵션에 콜백만 붙이면 그래프가 루프를 제어합니다.

---

## 아직 없는 것

- **Docker / docker-compose** — 미포함. 구성한다면 백엔드 `8080`, 프론트 `3000`을 열고
  위 표의 환경변수를 넘기면 됩니다. 컨테이너에서 PostgreSQL과 LLM 게이트웨이(둘 다 사설 IP)에
  닿을 수 있도록 네트워크를 잡아야 합니다.
- `web_search` 실제 백엔드 연결 — DuckDuckGo HTML 엔드포인트는 요청 서너 번 만에
  이미지 CAPTCHA를 내밉니다. 검색 API 키(Brave/Tavily 등)를 받는 쪽이 유일하게 안정적입니다
- 인증/인가 — 현재 API는 무인증입니다. **코드 편집은 서버 JVM에서 임의 코드를 실행하는
  것과 같으므로**, 외부에 노출할 계획이라면 인증이 먼저 와야 합니다
- PDF 외 형식 (docx, txt 등)
- 의미 검색 — 지금 `document_search` 는 키워드 기반입니다
- 스캔 PDF의 OCR — 텍스트 레이어가 없으면 업로드가 거부됩니다
- 대화 이력 영속화

---

## 구조

```
pom.xml
src/main/java/com/graph/graphtemp/
  agent/      스펙 record, JdbcTemplate 저장소, CRUD 컨트롤러
  tools/      내장 툴과 이름 기반 레지스트리
  graph/      스펙 → CompiledGraph (react / linear), 캐시
  run/        SSE 실행 엔드포인트
  codegen/    템플릿 렌더링과 코드 엔드포인트
  config/     Jackson snake_case, UTF-8 + CORS
  error/      {"detail": ...} 로 통일된 에러 처리
src/main/resources/
  application.yml, schema.sql
  templates/  생성 코드 템플릿 (수정해도 재컴파일 불필요)

frontend/src/
  api/client.ts     REST(axios) + SSE(fetch)
  stores/agents.ts  zustand 스토어
  app/agents/...    3개 페이지
  components/       폼 필드, 툴 선택, 단계 편집, 툴 블록
```
