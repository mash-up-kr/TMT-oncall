# TMT-oncall — 서버 에러 자동 진단·대응 에이전트

## 목적

또맛또(TMT) 백엔드의 장애/문의를 사람이 개입하기 전에 자동으로 진단하고,
답변과 (필요 시) PR까지 만들어 Discord에 보고하는 상주 에이전트.

## 산출물 두 개

1. **`TMT-oncall`** (새 레포) — Java 21 + **Spring Boot 4.1.0**, Gradle 단일 모듈.
   트리거·오케스트레이션·Jira·PR·Discord 전송 담당. Oracle Cloud VM에 systemd로 상주.
2. **`MT-marketplace`** (기존 팀 마켓플레이스 레포) — 이 에이전트가 쓰는 **스킬을 플러그인으로 추가**.
   프롬프트는 `TMT-oncall` 레포에 넣지 않는다.

## 스택

- Java 21, Spring Boot 4.1.0, Gradle 단일 모듈
- Discord: **JDA**
- 에러 수집: **Sentry** (TMT-BE에 SDK 도입, 봇은 Sentry API 폴링)
- Claude Code 헤드리스(`claude -p --output-format json`)를 `ProcessBuilder`로 호출
- 대상 서버: TMT-BE (Kotlin/Spring, EC2 + docker compose)

## 선행 조건 — TMT-BE Sentry 도입 (별도 티켓)

TMT-oncall이 붙기 전에 TMT-BE에 아래가 들어가야 한다.

1. `sentry-spring-boot-starter` 의존성 추가, prod 프로필에만 활성화
2. DSN·환경(`environment: prod`)·릴리즈(배포 커밋 SHA) 설정
3. logback 연동(`sentry-logback`)으로 ERROR 레벨 이벤트 전송

로그 파일링(RollingFileAppender + 호스트 볼륨 마운트)은 사람 사후 분석용으로
별도 티켓 유지. 봇은 로그 파일에 접근하지 않는다.

## 트리거 세 종류

| 트리거 | 소스 |
|---|---|
| 에러 | Sentry 이슈 — **Sentry API 주기 폴링** |
| 서비스 다운 | `/actuator/health` 주기 폴링 |
| 질문 | Discord be-온콜 채널 |

## 에러 수집 — Sentry API 폴링

TMT-BE의 예외는 Sentry로 전송되고, 봇은 Oracle VM에서 Sentry API를 주기 폴링
(1분 간격)해 신규·재발 이슈를 가져온다. 인바운드 엔드포인트(webhook)는 열지 않는다.

- 에러 그루핑·중복 집계는 Sentry가 수행한다. 봇의 중복 억제 키는 **Sentry 이슈 ID**
- 스택트레이스·요청 컨텍스트·릴리즈 정보를 구조화된 형태로 가져와 분석 입력으로 쓴다
- 봇이 앱 서버와 분리되어 있어, **앱이 죽어도 봇은 살아서 알릴 수 있다**
- 마지막으로 처리한 이슈·이벤트 커서는 `OncallStore`에 저장해 재시작 후 누락·중복을 막는다
- 앱이 죽어 Sentry로 이벤트가 안 가는 경우는 다운 트리거(health 폴링)가 잡는다

## 동작 흐름

```
[에러 트리거] Sentry API 폴링 → 신규·재발 이슈 감지 → 이슈 ID 중복 억제
              → 1차 분류(Haiku) — 조치가 필요한 건인가?
              → 분석(Sonnet) → 채널에 "장애 리포트" + 수정 계획 + 버튼 ('스프링' 톤 고정)
              → [PR 만들기]를 누른 경우에만 티켓·브랜치·PR → 채널 보고

[다운 트리거] /actuator/health 폴링 실패 → 직전 Sentry 이벤트 수집
              → 분석(Sonnet) → 채널에 "서비스 다운" 리포트
              → 원인이 코드에 있으면 수정 계획 + 버튼. 인프라 원인이면 리포트만

[질문 트리거] Discord be-온콜 채널 메시지 수신
              → 질문자 Discord 역할로 audience 판정 (디자인 / 웹 / 스프링)
              → 분석(Sonnet) → 톤 분기 답변 전송
              → 코드 수정이 필요하면 수정 계획 + 버튼
```

- 역할 기반 톤 분기는 **질문 경로에서만**. 에러 경로는 항상 '스프링' 톤 리포트.
- **머지하지 않는다.** PR 생성까지만 하고 "이런 작업을 했습니다, 확인 부탁드립니다"로 보고.

## 답변 톤

Discord 역할이 그대로 audience가 된다. 역할은 **디자인 / 웹 / 스프링** 셋이다.

- **디자인** — CS 응대 톤. 서버·개발 용어를 빼고 쉬운 말로.
  "어떤 상황에서 무엇이 안 되는지 / 사용자에게 어떻게 보이는지 / 언제 고쳐지는지"만 전한다.
- **웹** — 클라이언트 개발자 톤. REST API·엔드포인트·요청/응답 필드명·상태 코드·에러 코드는
  그대로 쓴다. 서버 내부 구현(클래스·계층·쿼리)은 걷어내고, **FE에서 무엇을 바꿔야 하는지 /
  안 바꿔도 되는지**를 명확히 한다. 응답 계약이 바뀌면 그 사실을 반드시 짚는다.
- **스프링** — 스택트레이스·클래스·라인·원인을 있는 그대로 정확하게.
- 역할이 없거나 판정되지 않으면 **디자인** 톤으로 답한다.

## 조치 순서 — 분석과 실행 사이를 사람이 끊는다

봇은 코드 수정을 **자동으로 실행하지 않는다.** 분석 결과와 **수정 계획**을 먼저 보여주고,
사람이 버튼을 눌러야 실행한다.

```
분석 → 채널에 리포트 + 수정 계획 + 버튼
       [ PR 만들기 ]  [ 다시 분석 ]  [ 무시 ]
                ↓ (누른 경우에만)
Jira 티켓 생성 → 브랜치(TMT-###-...) → 수정 → ./gradlew build → PR → 채널 보고
```

리포트에 담기는 것:

- 원인 (파일·라인·근거)
- **수정 계획** — 어떤 파일을 어떻게 바꿀 것인지 항목별로
- 영향 범위 — API 응답 계약·스키마 변경 여부
- 관련 배포 — 직전 머지 커밋·배포 시각

버튼 동작:

- **PR 만들기** — 티켓 생성부터 PR까지 실행. 누른 사람이 요청자로 티켓·PR에 기록된다
- **다시 분석** — 스레드에 남긴 힌트를 반영해 재분석
- **무시** — 해당 Sentry 이슈를 억제 목록에 넣어 같은 에러로 다시 알리지 않는다.
  `/oncall unmute <이슈 ID>`로 해제할 수 있다 — 실수로 눌렀을 때
  그 에러가 영영 묻히지 않게 한다

분석·답변만 하고 끝나는 경로는 **티켓을 만들지 않는다.** Discord 스레드가 기록이다.

## 채널 명령 — 슬래시 명령으로 등록한다

| 명령 | 하는 일 |
|---|---|
| `/oncall on` · `/oncall off` | 봇 전체 정지·재개 |
| `/oncall unmute <이슈 ID>` | 억제 목록에서 빼 다시 알리게 한다 |
| `/oncall status` | 현재 on/off, 이번 달 사용량, 억제 중인 건수 |

프리픽스 명령(`!oncall`)이 아니라 슬래시 명령으로 등록하는 이유는 **권한** 때문이다.
킬 스위치는 누르면 봇이 통째로 멈추는 명령인데, 슬래시 명령은 Discord 통합 설정에서
실행 가능한 역할을 제한할 수 있어 역할 검사를 직접 짤 필요가 없다. 인자 검증과
자동완성이 따라오고, 응답을 ephemeral로 보내 채널을 어지르지 않는 것도 이점이다.

질문 트리거는 일반 메시지를 읽어야 하므로 Message Content Intent는 그대로 필요하다.

## 패키지 구조 (`com.tmt.oncall`)

| 경로 | 역할 |
|---|---|
| `OncallApplication.java` | 부트 |
| `config/OncallProperties.java` | 채널ID, Sentry 조직·프로젝트, health URL, 워크스페이스 경로, 역할ID→audience(디자인/웹/스프링) 매핑 |
| `trigger/SentryWatcher.java` | Sentry API 주기 폴링 → 신규·재발 이슈 감지, 이슈 ID 중복 억제, 폴링 커서 관리 |
| `trigger/HealthWatcher.java` | `/actuator/health` 주기 폴링, 다운 감지 시 직전 Sentry 이벤트 수집 |
| `trigger/DiscordListener.java` | JDA 리스너, 질문 수신 + 역할 판정 |
| `agent/AgentRunner.java` | 에이전트 CLI(`claude -p`) 실행, **경로별 모델 지정**, 스킬 지정, 타임아웃·JSON 파싱 |
| `triage/IncidentTriage.java` · `QuestionTriage.java` | 두 경로 오케스트레이션 |
| `action/JiraTicketAgent.java` | 코드 수정이 필요할 때만 TMT 프로젝트에 이슈 생성 |
| `action/PullRequestAgent.java` | 전용 클론에서 브랜치·수정·`./gradlew build`·`gh pr create` |
| `notify/DiscordNotifier.java` | 2000자 분할 전송, 스레드로 묶기, 승인 버튼 부착 |
| `notify/ButtonHandler.java` | `PR 만들기` / `다시 분석` / `무시` 버튼 이벤트 처리 |
| `guard/KillSwitch.java` | 채널 명령·환경변수로 봇 전체 정지·재개 |
| `guard/CallBudget.java` | 시간당·일일 호출 상한, 월 비용 상한(`usage` 집계), 단계적 경로 차단 |
| `store/OncallStore.java` | 처리 이력·억제 목록·폴링 커서 영속화 (SQLite) |
| `systemd/tmt-oncall.service` · `README.md` | VM 상주·셋업 |

## MT-marketplace에 추가할 플러그인 (`be-oncall-kit`)

```
plugins/be-oncall-kit/
  .claude-plugin/plugin.json
  skills/
    incident-triage/     Sentry 이벤트·스택만 보고 조치 필요 여부 1차 판정 (Haiku, 소스 안 읽음)
    incident-analyze/    소스를 읽고 원인 도출 + 수정 계획 (JSON 출력)
    answer-design/       '디자인' 역할 톤 답변
    answer-web/          '웹' 역할 톤 답변
    answer-spring/       '스프링' 역할 톤 답변
    tmt-fix-pr/          TMT-BE 규칙대로 브랜치·수정·빌드·PR
```

- 기존 `MT-marketplace`의 디렉터리 관례·`marketplace.json` 형식을 **먼저 읽고 그대로 따를 것.**
  위 구조가 안 맞으면 레포 관례가 우선.
- **티켓 생성은 기존 `jira-creator` 스킬을 재사용한다.** Jira의 `TMT` 프로젝트는 `DDK`의 이름을 바꾼 것이라
  이미 커버 범위 안이다. 같은 PR에서 `jira-creator`의 `DDK` 표기를 `TMT`로 갱신한다. (`OPS`는 별개 유지)
- VM 셋업: `/plugin marketplace add mash-up-kr/MT-marketplace` → `/plugin install be-oncall-kit@<marketplace-name>`
- 팀원이 로컬에서 수동 장애 분석할 때도 쓸 수 있게 스킬 단독으로 동작하도록 작성.

## 사전 준비 (사람이 한 번 해두는 것)

토큰 4종은 각 웹 콘솔에서 **사람이 한 번 발급**한다. 그 뒤 런타임은 **환경변수 주입만으로** 동작하며,
봇이 브라우저를 여는 일은 없다.

### 1. Jira API 토큰

Atlassian 계정 → Security → **Create API token**. 발급 시 스코프를 지정할 수 있으므로
이슈 **생성·읽기·코멘트**로 최소화한다.

> Atlassian 토큰의 스코프는 API 단위라 **프로젝트 단위로는 좁힐 수 없다.** 토큰은 발급한 계정의
> 권한을 그대로 상속하므로, 처음엔 개인 계정으로 쓰되 환경변수만 교체하면 봇 계정으로
> 갈아끼울 수 있게 둔다.

### 2. GitHub Fine-grained PAT

- Resource owner: `mash-up-kr` → Repository access: **TMT-BE 레포만 선택**
- 권한: `Contents: Read and write`, `Pull requests: Read and write`, `Metadata: Read`. 그 외 No access
- 조직 정책에 따라 **오너 승인**이 필요할 수 있고, 조직이 fine-grained PAT을 막아두었다면
  설정에서 먼저 허용해야 한다

VM에서 한 번:

```bash
echo $GITHUB_TOKEN | gh auth login --with-token
```

### 3. Discord 봇

- 봇 등록 후 토큰 발급, **Message Content Intent + Server Members Intent** 활성화
  (메시지 본문 읽기와 역할 조회에 각각 필요)
- 설치 방식은 **길드 설치(Guild Install)** — 채널 메시지 상시 수신·역할 조회·능동 전송이
  전부 서버 컨텍스트를 요구한다. 사용자 설치로는 동작하지 않는다
- 초대 URL 스코프: `bot` + **`applications.commands`** (슬래시 명령 등록에 필요)
- 서버 초대 시 권한: 메시지 읽기·보내기, 스레드 생성, 메시지 히스토리 읽기,
  **메시지 컴포넌트(버튼) 사용**
- 초대 후 서버 설정 → 통합에서 `/oncall` 명령의 실행 역할을 제한한다

### 4. Sentry

- Sentry 조직에 `tmt-be` 프로젝트 생성 → DSN 발급 (TMT-BE 쪽 설정에 사용)
- 봇 폴링용 토큰은 **Internal(Custom) Integration**으로 발급한다 — 권한은 `Issue & Event: Read`,
  `Organization: Read`로 최소화. Organization Auth Token은 스코프가 `org:ci` 하나뿐이라
  (릴리즈 생성·소스맵 업로드 전용) 이벤트를 읽지 못한다. User Auth Token은 발급한 사람의
  계정에 묶여 그가 조직을 나가면 봇이 멎으므로 쓰지 않는다

### 5. 환경변수

**기본값을 두지 않는다.** 아래 값이 하나라도 없으면 기동 단계에서 실패한다 — 봇이 빈 토큰으로
반쯤 뜬 채 돌다가 정작 장애 때 아무것도 못 하는 것이 더 나쁘다.

레포 이름·Jira 프로젝트 키·Sentry 베이스 URL·가드 임계값처럼 환경에 따라 달라지지 않는 값은
환경변수가 아니라 `application.yml`에 직접 둔다.

```
# Discord
DISCORD_BOT_TOKEN=
DISCORD_ONCALL_CHANNEL_ID=          # be-온콜 채널
DISCORD_ROLE_ID_DESIGN=             # '디자인' 역할 ID
DISCORD_ROLE_ID_WEB=                # '웹' 역할 ID
DISCORD_ROLE_ID_SPRING=             # '스프링' 역할 ID

# Jira
JIRA_EMAIL=
JIRA_API_TOKEN=

# GitHub
GITHUB_TOKEN=

# 감시 대상
SENTRY_AUTH_TOKEN=
SENTRY_ORG_SLUG=
TMT_HEALTH_URL=https://3-39-38-23.sslip.io/api/actuator/health
TMT_WORKSPACE=~/tmt-oncall-workspace   # PR 작업용 전용 클론

# 에이전트 CLI
ONCALL_AGENT_BINARY=claude          # systemd는 최소 PATH로 뜨므로 절대경로가 필요할 수 있다
ONCALL_BILLING=api-key              # api-key | subscription
ONCALL_AGENT_API_KEY=               # 종량제일 때만 설정한다. 구독제면 설정하지 않는다

# 모델과 단가 (per 1M tokens). 모델만 바꾸고 단가를 안 바꾸면 비용 집계가 어긋나므로 함께 필수
ONCALL_MODEL_TRIAGE=claude-haiku-4-5
ONCALL_PRICE_TRIAGE_IN=1.00
ONCALL_PRICE_TRIAGE_OUT=5.00
ONCALL_MODEL_ANALYZE=claude-sonnet-5
ONCALL_PRICE_ANALYZE_IN=2.00
ONCALL_PRICE_ANALYZE_OUT=10.00
ONCALL_MODEL_FIX=claude-opus-5
ONCALL_PRICE_FIX_IN=5.00
ONCALL_PRICE_FIX_OUT=25.00

# 상태 저장
ONCALL_STORE_PATH=~/.tmt-oncall/state.db
```

에이전트 CLI의 자격 증명은 설정 객체에 바인딩하지 않는다. 우리가 쓰지 않고 하위 프로세스에
넘기기만 하는 값이라, 설정에 담아 로그·덤프로 흘리는 것보다 `AgentRunner`가 환경변수에서 직접
읽어 `ProcessBuilder`의 환경 맵에 옮기는 편이 안전하다. 이때 **두 모드 모두에서 상속된
`ANTHROPIC_API_KEY`를 먼저 제거한다** — VM에 키가 남아 있으면 구독제로 설정해두어도 자식
프로세스가 그대로 물려받아 조용히 종량제로 청구된다.

CLI가 읽는 이름(`ANTHROPIC_API_KEY`)은 `AgentRunner`의 **상수로 둔다. 설정으로 빼지 않는다** —
에이전트 교체는 스킬 플러그인 체계에 묶여 어차피 불가능하므로(ADR-001), 값이 하나뿐인 노브를
만드는 대신 결합을 상수 한 줄로 드러내는 편이 낫다.

### 6. VM

- **JDK 21** 설치 — `PullRequestAgent`가 전용 클론에서 `./gradlew build`를 실행한다
- `gh` CLI 설치
- 에이전트 CLI 설치. 종량제면 인증은 **`ONCALL_AGENT_API_KEY` 환경변수**로 한다 — 브라우저
  인증이 없어 헤드리스 VM에서 사람이 다시 붙을 일이 없고, 봇이 쓴 비용이 개인 사용과 분리되어
  숫자로 보인다 (과금 방식은 ADR-001에서 검토 중. 구독제로 바뀌어도 `AgentRunner`는 그대로이고
  `ONCALL_BILLING`만 바꾸면 된다)
- `TMT_WORKSPACE` 경로에 TMT-BE 클론

### 모델 선택 — 경로별로 나눈다

싼 모델이 앞단을 걸러내고, 비싼 모델은 실제로 코드를 고칠 때만 쓴다.

| 경로 | 모델 | 빈도 | 하는 일 |
|---|---|---|---|
| 1차 분류 | `claude-haiku-4-5` | 신규·재발 이슈마다 | 조치가 필요한 에러인지 판정. 소스는 읽지 않고 Sentry 이벤트·스택만 본다 |
| 분석·답변 | `claude-sonnet-5` | 1차 통과분 + 질문 | 소스를 읽고 원인·수정 계획 도출, 톤별 답변 생성 |
| 수정·PR | `claude-opus-5` | 버튼 승인 시에만 | 실제 코드 수정, 빌드, PR |

- 중복·재발 집계는 Sentry가 걸러내고, 1차 분류가 노이즈(일시적 타임아웃 등)를 걷어내
  비싼 호출을 아낀다
- 비용이 가장 큰 수정·PR 경로는 **버튼 승인 뒤에만** 실행되므로 사람이 누른 만큼만 나간다

단가는 per 1M tokens 기준 Haiku 4.5 $1/$5, Sonnet 5 $2/$10, Opus 5 $5/$25.

### 비용 예상과 상한

월 ERROR 30건(중복 포함) · 질문 10건 기준:

| 경로 | 건수 | 건당 | 소계 |
|---|---|---|---|
| 1차 분류 (Haiku) | 12 | $0.01 | $0.1 |
| 분석·답변 (Sonnet) | 16 | $0.40 | $6.4 |
| 수정·PR (Opus) | 2 | $2.75 | $5.5 |

합계 약 **$12**. 아래 세 가지로 **$7 (월 1만원)** 안에 들어오게 맞춘다.

1. **프롬프트 캐싱** — 시스템 프롬프트·스킬·반복 조회하는 소스에 캐시를 건다. 입력 단가가
   1/10로 떨어져 절감 효과가 가장 크다
2. **소스 읽기 범위 제한** — 스택트레이스에 등장한 파일과 그 직접 의존까지만. 레포 전체를
   훑게 두면 입력 토큰이 급증한다
3. **이벤트 첨부 상한** — 대표 이벤트 1건 + 최근 발생 이력 요약만 전달

**한도 도달 시 단계적으로 차단한다.** 봇이 통째로 멈춰 장애를 놓치는 것이 최악이므로,
알림 기능은 마지막까지 살린다.

| 월 사용액 | 동작 |
|---|---|
| 70% | 채널에 경고 |
| 85% | Opus(수정·PR) 경로 차단. 분석·답변은 계속 |
| 100% | 1차 분류(Haiku)만 유지 — 에러 알림 자체는 끊기지 않는다 |

비용은 각 응답의 `usage`로 실제 토큰을 집계해 누적한다.

### 확정 필요

- 조직(`mash-up-kr`)의 fine-grained PAT 허용 여부·오너 승인 절차
- **과금 방식** — 종량제 API vs Claude Code Max 구독 (ADR-001, 검토 중).
  구현을 막지는 않는다 — `CallBudget`에 비용 집계 on/off 플래그를 두어 양쪽 모두 수용한다

## Jira 연동

- **API 토큰 Basic 인증**으로 동작한다. 티켓 생성은 Claude가 아니라 Spring의
  `JiraTicketAgent`가 **REST API로 직접** 호출한다 — Atlassian MCP는 OAuth라
  헤드리스 환경에서 재인증에 사람이 붙어야 한다
- `jira-creator` 스킬은 **티켓 본문 작성 규칙**(제목 형식·타입·필드 구성)만 제공한다
- 상태 전환·삭제는 하지 않는다 (PR 생성 시 상태가 자동 전환되는 워크플로가 이미 있다)
- 자동 생성분: **Task 타입 + 라벨 `oncall-auto`**
- 티켓 본문: Sentry 이슈 링크 / 발생 시각 / 스택 요약 / Discord 스레드 링크

### 티켓 생성에 실패해도 PR은 낸다

이 자동화의 산출물은 **수정과 PR**이고 티켓은 기록이다. Jira가 죽었다고 고쳐놓은 코드를
버리지 않는다. 티켓 없이 진행하되, 사람이 나중에 이어붙일 수 있게 표시만 남긴다.

| | 티켓 있음 | 티켓 없음 |
|---|---|---|
| 브랜치 | `TMT-401-fix-npe` | `oncall/fix-npe` |
| PR 제목 | TMT-BE `docs/BRANCHING.md` 규칙대로 티켓 키 포함 | `[온콜] NPE 수정 (티켓 미생성)` |
| 채널 보고 | 평소대로 | "티켓을 못 만들었습니다 — 확인 후 링크해주세요" |

**키를 지어내지 않는다.** Jira 키는 순번이라 지어낸 `TMT-401`이 나중에 진짜 다른 티켓이
된다. 브랜치·PR이 남의 티켓을 가리키고 워크플로 자동 전환도 엉뚱한 곳에서 돈다.
`oncall/` 접두사는 실제 키와 부딪히지 않는다.

PR 제목의 티켓 키는 CI로 강제되지 않아 티켓 없는 PR도 올라간다. 사람이 제목에 키를
붙이면 그때부터 워크플로가 정상 동작한다. 티켓 생성을 뒤에서 다시 시도하지는 않는다 —
사람이 버튼을 다시 누르는 편이 단순하다.

## 안전장치

1. **작업 디렉터리 분리** — 수정·PR은 전용 클론(`~/tmt-oncall-workspace`)에서만. 운영 배포 소스 미접촉
2. **main 직접 push 금지.** 항상 브랜치 + PR. 머지·승인 바이패스 없음
3. `./gradlew build` 실패 시 PR을 올리지 않고 "분석은 됐지만 자동 수정 실패"로 보고.
   빌드 실패는 우리 수정이 틀렸다는 뜻이라 결과물을 내보내지 않는다 — Jira 장애로 티켓만
   못 만든 경우와는 다르다 (§티켓 생성에 실패해도 PR은 낸다)
4. **폭주 방지** — 동일 Sentry 이슈 30분 1회. `무시` 처리된 이슈는 영구 억제.
   트리거는 분석에 넘기기 **전에** 처리 이력을 남긴다 — 뒤에서 실패해도 폴링마다 같은 건이
   다시 뜨지 않게. 대신 실패한 건은 **2회까지 다시 시도한다.** 시도 횟수와 완료 여부를
   이력에 함께 두고, 완료 표시는 성패를 아는 오케스트레이션이 찍는다
5. **호출 상한** — 시간당 5회·일일 10회를 넘으면 호출을 멈추고 채널에 통지. 폭주를 빨리 끊는다
6. **비용 상한** — 월 $7. 70%/85%/100%에서 단계적으로 경로를 차단한다 (§비용 예상과 상한)
7. **킬 스위치** — `/oncall off` / `on`으로 봇 전체를 즉시 정지·재개. 실행 권한은
   Discord 통합 설정에서 역할로 제한한다. 기동 시 기본 상태는 환경변수로 정한다
8. **폴링 스위치** — `oncall.trigger.enabled`로 주기 폴링만 끈다. 킬 스위치와 별개다.
   스케줄을 감지 로직에서 분리해 꺼두면 스케줄 자체가 등록되지 않으므로, 테스트나 로컬 실행이
   실수로 외부 API를 때리는 길이 코드로 막힌다
9. **상태 영속화** — 처리 이력·억제 목록·폴링 커서를 SQLite에 저장해, 재시작 후 같은 건을
   다시 분석하거나 중복 PR을 만들지 않게 한다
10. **봇 자기 감시** — systemd 자동 재시작. 기동·종료 시 채널에 한 줄 알린다.
   API 호출 실패가 연속되면 채널에 알린다 — 조용히 멈추면 장애를 놓친다
11. 토큰·웹훅은 전부 환경변수. 커밋 금지

## TMT-BE 규칙 준수 (수정 PR 생성 시)

- 브랜치·커밋·PR 규칙은 TMT-BE `docs/BRANCHING.md` 기준. 브랜치/PR 제목에 Jira 티켓 키(`TMT-###`)
- 커밋 메시지는 **제목만**. `Co-Authored-By` 등 트레일러·Claude 서명 금지
- DB 스키마 변경 시 Flyway 새 마이그레이션 + `docs/DB-SCHEMA.md` 동반 갱신
- API 계약 변경은 자동 PR 대상에서 제외 — 채널에 "계약 변경이 필요해 보임"으로 보고만

## 1차 목표

전 구간 프로토타입 — 트리거 → 분석 → 답변 → 티켓·브랜치·PR → 채널 보고까지.
`TMT-oncall` 첫 커밋과 `MT-marketplace` 플러그인 PR까지 만들 것.
