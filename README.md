# voice-collector — 비정형 음성 수집 서비스

보라미의 수용자 음성(접견·통화)을 골라 가져와 복호화하고 **STT 텍스트로 만드는** 서비스.
범위는 STT 처리와 내부 저장·감시까지다 — STT 텍스트를 외부 서비스로 전송하지 않는다
(비식별 커넥터 연동은 아키텍처 변경으로 제외됐다).

> 설계 근거: `data_agent-connector-dp8qbi7xqh/study/회의록/9_11/음성수집_서비스_개발계획_초안_v2.md`

---

## 1. 이 서비스가 하는 일 / 하지 않는 일

```
보라미 조회 → XVARM 브로커 → 파일 수신 → 복호화 → STT → 출력 저장 → 처리 이력(T1·T2·T4) ┃
                              (이 서비스의 범위)                                          ┃
                                          │                                              ▼
                                          ▼                          log-collector : T1 · T2(COLLECT·ANALYZE) · T4 적재 (API)
                    {base-dir}/xenon/voice/{execId}/  (접견)  ·  {base-dir}/xenon/phone/{execId}/  (전화)
                    — STT 텍스트(.txt) + 메타(.json) 를 배치 단위 폴더에 남긴다. 하류(비식별)가 여기서 읽어 간다
```

| 하는 일 | 하지 않는 일 (다른 서비스 책임) |
|---|---|
| 대상 선별(특이수용자 필터) | 로그 테이블 INSERT → `log-collector` (API 로만 적재) |
| XVARM 추출 요청 | 재식별 매핑 → `data-collector` |
| 파일 수신·포맷 판별 | STT 엔진 자체 → NPU 서버 |
| 복호화 | STT 텍스트 외부 전송 → **하지 않는다** (커넥터 연동 제외) |
| STT 호출 · 원본 즉시 삭제 · **STT 결과 파일 저장** · 처리 이력 적재 | T2 의 DEIDENT·SEND 단계 → 하류 |

**R&R 은 2026-08-19 에 확정된 것이다.** 로그 테이블(T1~T11)의 단일 writer 는 로그 컬렉터다.
이 서비스도 자체 로그 테이블을 만들지 않는다.

---

## 2. 5개 스위치 — 준비된 것부터 실물로

외부 의존이 서로 다른 시점에 준비된다. 하나가 막혀도 나머지는 진행되도록 각각 독립 스위치다.
접견(브로커)과 전화(파일 연계)는 연동 주체가 달라 스위치도 따로 둔다.

| 스위치 | 값 | 기본 (`local`) | 실물 전환 조건 |
|---|---|---|---|
| `voice.source.mode` | `MOCK` / `DIRECT_JDBC` / `ESB_HTTP2DB` | MOCK (**DIRECT_JDBC**) | 인터페이스ID(Q2)·I/F 테이블(Q15) |
| `voice.broker.mode` | `MOCK` / `REST` — 접견 전용 | MOCK (**REST**) | XVARM 사양(Q3), 브로커 배포 |
| `voice.phone.mode` | `MOCK` / `ESB` — 전화 전용 | MOCK (MOCK) | ESB 전화 연계 프로바이더 구성 |
| `voice.decrypt.mode` | `SKIP` / `REAL` | SKIP (SKIP) | 복호화 주체 확정(Q13), 키 수령(Q8) |
| `voice.stt.mode` | `MOCK` / `NPU` | MOCK (MOCK) | NPU API 사양(Q9) |

> **REAL 모드는 실패해도 Mock 으로 빠지지 않는다**(Fail-fast). Mock 결과가 실제인 양 섞이면
> 시연·검증이 통째로 무의미해지기 때문이다.

### 런타임 전환 — 재시작이 필요 없다

네 구현이 모두 빈으로 떠 있고, 라우터가 **호출 시점에** 현재 모드를 보고 위임한다.
그래서 시연 중에도 "이건 아직 Mock, 이건 실물"을 바꿔 가며 보여줄 수 있다.

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/modes/stt?value=NPU"
curl      "http://localhost:8085/api/v1/mock/modes"      # 현재값 + 기동 설정값 + 허용값
curl -X POST "http://localhost:8085/api/v1/mock/modes/reset"
```

시뮬레이터 화면의 드롭다운이 이 API 를 호출한다.

> 런타임 변경은 **이 프로세스에만** 남는다. 재기동하면 설정값으로 돌아간다 —
> 운영에서 실수로 바꾼 모드가 영구히 남지 않게 하려는 것이다.

---

## 3. 로컬 실행

```bash
mvn spring-boot:run
```

**프로파일을 안 주면 `local` 로 뜬다**(`spring.profiles.default: local`) — IntelliJ 에서 Active profiles 를
비워 둬도 같다. K8s 이미지는 Dockerfile 이 프로파일을 박아 넣으므로 영향이 없다.

`local` 의 기본은 **포트 8085 · 보라미 조회 `DIRECT_JDBC`(H2 Mock 보라미) + 브로커 `REST`(8082)** 다.
그래서 접견 트랙을 돌리려면 **브로커가 8082 에 `local` 프로파일로 떠 있어야 한다** — 없으면
접견 배치가 바로 실패한다(Fail-fast, 의도된 동작). 전화·복호화·STT 는 MOCK 이라 외부 의존이 없다.

```bash
# 브로커 (다른 레포) — local 프로파일이 출력 폴더를 이 레포의 work/voice_raw/meet(= voice.dirs.receive-meet) 로 맞춘다
cd ../data_borami-xvarm-broker-1joiuorqhl
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

브로커 없이 돌리려면 시뮬레이터의 [XVARM 브로커] 드롭다운을 `MOCK` 으로 내리면 된다
(또는 `VOICE_BROKER_MODE=MOCK`).

**시연 Mock 은 총 12건**이다 — H2 Mock 보라미와 MOCK 소스가 같은 구성을 쓴다.

| 배치 | 시간창 | 접견 | 전화 | 시각 |
|---|---|---:|---:|---|
| 일배치 `POST /batches/daily` | [어제 00:00, 오늘 00:00) | 5 | 5 | 어제 09:10~09:50 |
| 10분 주기 `POST /batches/periodic` (접견만·전화만 포함) | [지금-20분, 지금) | 1 | 1 | 5분 전 |

> 시각은 **적재 시점 기준**이라, 기동 후 20분이 지나면 주기배치 창에 걸리는 행이 없어진다.
> 시뮬레이터 [Mock 데이터 초기화/생성](`POST /api/v1/mock/reset`)이 H2 를 **지금 시각 기준으로 다시 적재**하고
> Mock 규모도 12건으로 되돌리므로, 시연 직전에 한 번 누르면 된다. 전체 배치는 10초 안에 끝난다
> (접견 5건은 로컬 브로커의 추출 지연 `BROKER_DELAY_MS`=1500ms 가 대부분이다 — 더 빠르게 보려면 브로커에 `BROKER_DELAY_MS=0`).

> **브로커 출력 경로가 어긋나면** 브로커는 "추출 완료" 를 돌려주지만 우리 수신 폴더는 비어 있다.
> 브로커 응답의 `filePath`(절대경로)에 파일이 **실제로 있는데** 수신 폴더 밖이면 5분을 기다리지 않고
> 그 건을 즉시 실패시키며 사유에 두 경로를 적는다(`BrokerOutputCheck`). 운영은 보라미 서버 경로라
> 우리 쪽에 존재하지 않아 이 판정에 걸리지 않는다. 배치 전 **[브로커 연결 확인]** 으로 대조하는 것이 먼저다.

### 로컬 포트 배치

사내 서비스를 동시에 띄워도 겹치지 않게 정해져 있다.

| 포트 | 서비스 | 비고 |
|---:|---|---|
| 8080 | `agent-connector` | 사내 타 서비스 (이 서비스와 연동 없음) |
| 8082 | `borami-xvarm-broker` | XVARM 브로커 — 접견 트랙이 호출 |
| **8085** | **`voice-collector`** | 이 서비스 |
| 8090 | `log-collector` | context-path **`/logc`** |

> 8080·8090 은 각 프로젝트의 `application-local.yml` 에 이미 정해져 있고 8082 는 브로커가 쓴다.
> 그래서 이 서비스는 8085 를 쓴다.

### 화면 두 개

| 주소 | 용도 |
|---|---|
| **`http://localhost:8085/voice_collector_simulator.html`** | **시뮬레이터** — 시연용 조작 화면 |
| `http://localhost:8085/swagger-ui.html` | Swagger — API 개별 호출·스펙 확인 |

### 로그 컬렉터 연동 (로컬)

K8s 의 DB 만 포트포워딩하고 컬렉터 앱은 로컬에서 직접 띄우는 구성을 기본값으로 잡아 두었다.

```yaml
log-collector:
  enabled: true
  base-url: http://localhost:8090/logc     # context-path 까지 포함해야 한다
```

- 컬렉터가 떠 있으면 **T1·T2·T4** 가 실제로 적재되고 EXEC_ID 도 컬렉터가 채번한다
- 떠 있지 않아도 이 서비스는 정상 동작한다 — 호출 실패를 경고만 남기고 넘어가며,
  EXEC_ID 는 로컬 임시 ID(`…VOC-LOCAL`)로 대체된다

시뮬레이터 화면 구성 (상세 정보는 전부 **아코디언**이라 접힌 채 얇게 보이고, 클릭하면 스르륵 펼쳐진다):

- **상단 서브헤더 — 디렉터리** — 수신(접견·전화) · 작업 · STT 출력(접견·전화) 5종 경로를 한 줄로 보여주고,
  프리셋 버튼 `로컬 기본(설정값)` / `Windows C:/k8s` / `PV /k8s` 을 고르는 즉시 반영한다(현재 값과 같은 프리셋이 진하게).
  [편집 ▾] 을 누르면 5종 입력칸과 base-dir 로 표준 배치 채우기가 펼쳐진다.
  수신(접견)을 바꾸면 로컬 브로커의 `BROKER_OUTPUT_DIR` 도 맞춰야 한다 — [브로커 연결 확인] 으로 대조
- **① 처리 구간 모드** — 접견·전화 2트랙의 스위치를 **드롭다운으로 즉시 전환**(서버 재시작 불필요).
  MOCK/SKIP 은 주황, 실물은 초록. 로그 컬렉터 연결 여부도 함께 본다.
  브로커가 `REST` 면 단계 아래에 **주소 라디오**(`개발계 K8s` / `로컬 PC`)가 뜨고 고르는 즉시 반영된다 —
  기동 설정값과 같은 항목에 `(Default)` 가 붙는다(`voice.broker.presets` 에서 내려준다)
- **② 제어**
  - 배치 4종: `접견만`(`POST /api/v1/voice/batches/periodic?kinds=MEET`) · `전화만`(`?kinds=PHONE`) ·
    `10분 주기`(`/periodic`) · `일배치`(`/daily`) — 실행 중에는 버튼이 잠기고 누른 버튼에 스피너가 돈다
  - 보조: `Mock 데이터 초기화/생성`(멱등 표식·수신 파일 삭제 + H2 재적재 12건) · `대상 미리보기` · `수신 파일` · `STT 출력 확인` · `브로커 연결 확인`
  - **고급 (접힘)**: 대용량 Mock(일배치용 건수 + 프리셋 1,000 / 5,000 / 10,000건) · 장애 주입(활성 토글 + 실패 % · 지연 % · 지연 ms).
    규모를 올리거나 장애 주입이 켜져 있으면 접힌 상태에서도 노란 요약이 뜬다
- **③ 최근 배치 결과** — 대상 / 성공 / 실패 / 건너뜀 / 상태 숫자 +
  **요약 바** `[HTTP 200 OK] [POST] /api/v1/voice/batches/periodic?kinds=MEET&test=true · 성공 1 / 실패 0 · SUCCESS · 1965ms [▼]`.
  ▼ 를 누르면 **REST 명세**(Request · Query · Request Body · Response 상태·소요) + EXEC_ID·T2 단계 배지·STT 출력 폴더 +
  **Response JSON**(구문 강조 · 복사 · 펼치기)이 펼쳐진다.
  그 아래 **처리 파일 · STT 텍스트** 표도 아코디언(건수·성공/실패 요약만 보이고 펼치면 표)
- **④ 재처리(Retry) 시나리오** — ① 브로커 장애 주입(`http://localhost:9999`) → ② 1차 배치(실패 이력) →
  ③ 브로커 복구(`http://localhost:8082`) → ④ 재처리 배치. 버튼 4개로 하나씩, 또는 **🚀 원클릭 자동 실행**
  (간격 설정 · 실행 전 Mock 초기화 옵션). 단계별 타임라인 카드는 상태 배지 + 한 줄 요약(② `FAIL · 성공 0 / 실패 1 · EXEC_ID`,
  ④ `SUCCESS · 성공 1 / 실패 0 · EXEC_ID`)만 보이고, 카드를 클릭하면 REST 명세와 Response JSON 이 펼쳐진다.
  아래 **② vs ④ JSON 대조** 바를 펼치면 실패 JSON 과 성공 JSON 이 나란히 놓인다
- **⑤ 실행 로그** — 호출 API·응답 시간·결과 요약·T2 단계·실패 사유

**시연 순서**: `Mock 데이터 초기화` → `처리 대상 미리보기` → `배치 실행` → **한 번 더 배치 실행**
(두 번째는 `건너뜀`으로 잡혀 멱등 동작이 드러난다) 

```bash
# CLI 로 하려면
curl -X POST "http://localhost:8085/api/v1/mock/reset"          # 초기화
curl      "http://localhost:8085/api/v1/mock/targets"           # 대상 미리보기
curl -X POST "http://localhost:8085/api/v1/voice/batches/daily" # 배치 실행
curl      "http://localhost:8085/api/v1/voice/status"           # 현재 구성
```

> `/api/v1/mock/**` 는 **시연 전용**이다. 운영 배포 시 인그레스·게이트웨이에서 차단한다.

### 4단 조인 SQL 검증 (H2 Mock)

H2 에 보라미 Mock 스키마가 올라간다. `voice.source.mode=DIRECT_JDBC` 로 바꾸면 실제 조회가 돈다.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--voice.source.mode=DIRECT_JDBC"
```

샘플에는 **걸러져야 할 행**을 섞어 두었다(삭제된 녹취, 해제된 특이수용자, 대상 아닌 관리코드,
녹음 안 된 통화 — 대상이 되지 않아 배치 시간에는 영향이 없다). 기대 결과는 `data-borami-mock.sql` 머리말에 적어 두었다.

**DIRECT_JDBC 원본 쿼리**(접견 4단 조인 · 전화 조인 · 전화 지름길)를 스키마·테이블명과 값을 풀어
DBeaver 에서 바로 실행할 수 있게 [`docs/borami_direct_jdbc_queries.sql`](docs/borami_direct_jdbc_queries.sql) 에 두었다.

### 실제 borami-db 조회 (`realdb` 프로파일)

포트포워딩 후 `local` 과 **겹쳐서** 띄운다.

```bash
kubectl port-forward -n data-pipeline svc/borami-db-gijoxearrw 15433:5432
```

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local,realdb
```

> `realdb` 프로파일이 `sql.init` 을 끄고 스키마·플래그 값을 실DB 에 맞춘다.
> **`local` 단독으로 datasource 만 바꾸면 안 된다** — Mock 스키마의 `DROP TABLE` 이 실DB 에서 실행된다.

로그 컬렉터용 DB는 별도다(네임스페이스가 다르니 `-n` 을 빠뜨리지 말 것).

```bash
kubectl port-forward -n service-core svc/admin-db-fy9tjq4tsk 15432:5432
```

| 대상 | 포트 | database | user | 용도 |
|---|---:|---|---|---|
| `admin-db` (service-core) | 15432 | `correction_ai` | `kcais` | 로그 컬렉터(`kcais` 스키마) |
| `borami-db` (data-pipeline) | 15433 | `borami` | `borami` | 보라미 조회 |

---

## 4. 안정성 검증 기능

### 대용량 부하 (OOM 방어)

실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건이다. 12건짜리 시연 Mock 으로는
스트리밍·청크 처리가 메모리를 지키는지 알 수 없어, **일배치용** 건수를 런타임에 올릴 수 있게 했다
(주기배치용 1·1 은 그대로라 10분 주기 배치가 수백 건을 돌 일이 없다). 올린 값은 [Mock 데이터 초기화/생성] 이 12건으로 되돌린다.

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/dataset?meet=500&phone=500"
```

- 건수를 올려도 **힙이 비례해 늘지 않아야** 한다 — 파일은 스트리밍으로 다루고
  건별로 처리한 뒤 바로 지운다
- **200건을 넘으면** Mock WAV 를 1초짜리로 줄인다. 우리가 보려는 것은 건수에 대한
  메모리 거동이지 파일 크기가 아니고, 10,000건 × 2초는 640MB 라 디스크만 잡아먹는다
- **처리 시간 주의**: 건당 파일 안정성 검사(`stable-check-ms`, local 100ms)가 그대로
  곱해진다. 1,000건 ≈ 2분, 10,000건 ≈ 20분

### 장애 주입 (Chaos)

```bash
curl -X PUT "http://localhost:8085/api/v1/mock/chaos?enabled=true&failPercent=10&delayPercent=10&delayMs=2000"
```

STT 구간에 의도적으로 실패와 지연을 섞는다. **확인하려는 것은
파일 1건이 터져도 배치가 죽지 않고 `failCnt` 만 올리며 끝까지 도는가**이다.
1,300건 배치에서 한 건 때문에 전체가 멈추면 나머지를 전부 다시 처리해야 한다.

- 기본은 꺼져 있고, 켜져 있으면 `status` 와 시뮬레이터 화면에 항상 노출된다
  (켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다)
- 실패·지연은 **독립 판정** — 지연되면서 실패할 수도 있다
- 주입된 예외는 `InjectedFaultException` 이라 진짜 버그와 로그에서 구분된다

### PII 원본 즉시 삭제

정책은 "복호화된 원본 음성은 STT 완료 즉시 삭제"다(계획서 5.3-(4)).

- 삭제는 `processOne` 의 **`finally`** 에서 한다 — STT 가 실패하는 경로에서도 지워야 한다.
  성공 경로에서만 지우면 실패한 건의 음성이 디스크에 남는다
- `ResilienceE2ETest` 가 수신·작업 디렉터리를 직접 세어 **전부 실패한 배치에서도 잔여 0** 임을 검증한다
  (예전의 `PiiResidueAuditor` · `GET /api/v1/mock/pii-residue` · 결과의 `residue` 는 제거했다)

### STT 결과 저장 — 배치 단위 폴더

STT 텍스트는 외부로 보내지 않고 **배치(EXEC_ID) 단위 폴더**에 남긴다. 하류(비식별)가 여기서 읽어 간다.

```
{base-dir}/xenon/voice/{execId}/{건ID}.txt + .json     접견
{base-dir}/xenon/phone/{execId}/{건ID}.txt + .json     전화
```

| 설정 (`voice.dirs.*`) | 환경변수 | local | dev/prod (PV) |
|---|---|---|---|
| `base-dir` | `VOICE_BASE_DIR` | `C:/k8s` | `/k8s` |
| `receive-meet` / `receive-phone` | `VOICE_MEET_DIR` / `VOICE_PHONE_DIR` | `./work/voice_raw/{meet,phone}` | `{base}/voice_raw/{meet,phone}` |
| `work` | `VOICE_WORK_DIR` | `./work/voice_work` | `{base}/voice_work` |
| `output-meet` / `output-phone` | `VOICE_OUTPUT_MEET_DIR` / `VOICE_OUTPUT_PHONE_DIR` | `{base}/xenon/{voice,phone}` | `{base}/xenon/{voice,phone}` |

- 로컬은 수신·작업 폴더만 레포 상대경로에 둔다 — 로컬 브로커(local 프로파일)가 기본으로 그곳에 떨구기 때문이다.
  전부 `C:/k8s` 로 옮기려면 시뮬레이터 ② 프리셋 "Windows C:/k8s" + 브로커 `BROKER_OUTPUT_DIR=C:/k8s/voice_raw/meet`
- 런타임 변경: `GET/PUT /api/v1/mock/dirs` · `PUT /api/v1/mock/dirs/preset?key=configured|win|pv|base&baseDir=` ·
  `POST /api/v1/mock/dirs/reset` (재기동하면 설정값으로 돌아간다)
- 배치 응답의 `outputDirs` 가 이 배치의 폴더, `outcomes[].sttPath` 가 파일별 경로다.
  텍스트 자체는 `GET /api/v1/mock/stt-outputs?execId=` 로만 본다(시뮬레이터용 · 운영 차단)
- **테스트 데이터 초기화**(`DELETE /api/v1/mock/test-data`)가 EXEC_ID 에 `TST` 가 든 출력 폴더도 통째로 지운다

### T2 단계 로그 — COLLECT · ANALYZE

로그 컬렉터의 비정형 체인은 `COLLECT → ANALYZE → DEIDENT → SEND` 다. 이 서비스는 앞의 두 단계를 남긴다
(`POST /api/v1/logs/batches/{execId}/steps` → `PATCH /api/v1/logs/steps/{stepLogId}`).

| 단계 | 여는 시점 | 마감 시점 | in / out / err |
|---|---|---|---|
| `COLLECT` | 배치 시작 | 전 건 처리 후 | 대상(건너뜀 제외) / 파일 확보·복호화 성공 / 확보 실패 |
| `ANALYZE` | 첫 STT 직전 | **STT 처리가 끝난 직후** | 확보 성공 / STT·출력 저장 성공 / STT 실패 |

- `data-type-cd` 는 **`UNSTRUCTURED`**(C01 4종) — 예전 `VOICE` 는 C01 에 없어 대시보드 필터·체인 순번을 타지 못했다
- 파일별 실패 단계는 `outcomes[].failedStep` 에 남는다. T1 마감에는 대표 오류 `[코드] 상세` 와 `ERR_TYPE_CD`(CONNECTION/TIMEOUT/DATA)가 실린다

### 재처리(Retry) 시나리오

실패한 건은 멱등 표식이 남지 않으므로 **다음 배치가 자동으로 다시 처리**한다. 시뮬레이터 ⑤ 가 이것을 4단계로 보여준다.

```bash
curl -X PUT  "http://localhost:8085/api/v1/mock/endpoints/broker?value=http://localhost:9999"   # ① 장애
curl -X POST "http://localhost:8085/api/v1/voice/batches/periodic?kinds=MEET&test=true"          # ② FAIL — T1 FAIL · T2 COLLECT FAIL · T4 FAIL
curl -X PUT  "http://localhost:8085/api/v1/mock/endpoints/broker?value=http://localhost:8082"   # ③ 복구
curl -X POST "http://localhost:8085/api/v1/voice/batches/periodic?kinds=MEET&test=true"          # ④ SUCCESS — 새 EXEC_ID · COLLECT·ANALYZE SUCCESS
```

> 브로커 요청 키는 `VOC-{execId}-{대상키}` 다. 대상만으로 키를 만들면 브로커의 멱등 캐시가 "이미 DONE" 을 돌려주고
> 파일은 이미 지워진 뒤라 재처리 배치가 수신 대기 타임아웃으로 실패한다(실제로 그랬다).

---

## 5. 실DB에서 확인된 차이 (2026-09-12)

`borami-db`에 직접 붙어 확인한 결과, **문서만 보고 짠 SQL 로는 동작하지 않는** 지점들이 있었다.
전부 설정으로 흡수했으니, 값만 바꾸면 실연동에 대응된다.

### ① 테이블이 스키마로 나뉘어 있다

```
im.tb_imsc_ptpr_dt    특이수용자상세      636행
re.tb_rerd_tfin_ds    녹취파일내역        106행
im.tb_imph_ucdr_ds    사용자통화내역       25행
```

회의 축어록 `[00:17:26]` 의 *"TVG의 RE면 RE점, IM이면 IM점"* 이 이 구조다. 테이블명 접두어가 곧 스키마다.
`voice.source.schema.{imsc,rerd,smsm,xvarm}` 로 주입하며, 비우면 수식 없이(H2 Mock) 나간다.

### ② 플래그 값 도메인이 문서와 다르다

```
telp_ptcr_prsr_yn  =  '0'  × 25건        ← 'Y' 로 비교하면 영원히 0건
telp_pcall_recrd_yn=  'N'  × 25건
telp_recrd_file_id =  NULL × 25건
```

스펙 문서에는 `CHAR NOT NULL` 이라고만 적혀 있고 값 정의가 없다. **에러가 아니라 "대상 없음"으로
보여서 알아채기 가장 어려운 종류의 결함**이라, `voice.source.flag.*` 로 빼 두었다.

### ③ 접견 경로의 3·4단 테이블이 없다

```
TB_SMSM_CMFI_BS      MISSING   (sm 스키마엔 공통코드 8개뿐)
ASYSCONTENTELEMENT   MISSING   (XVARM 관련 테이블 전무)
```

접견은 실DB 로 `DOC_ID`·`FILEKEY` 를 얻을 수 없다. 1·2단(특이수용자→녹취) 조건에 맞는 건은 11건 있다.
**접견은 두 테이블이 확보될 때까지 MOCK 을 유지한다.**

조회에 실패하면 조립된 테이블명과 원인을 그대로 돌려준다.

```
CONFIGURATION_ERROR
  현재 대상: 특이수용자=im.TB_IMSC_PTPR_DT, … 공통파일=sm.TB_SMSM_CMFI_BS, …
  원인     : ERROR: relation "sm.tb_smsm_cmfi_bs" does not exist
```

### ④ 계획서 Q1 — 보라미 기존 STT는 없었다

```
im.tb_imph_ucdr_ds : total=25, telp_stt_flpth_nm 채워진 건 = 0
```

이 DB 기준으로는 **보라미가 STT 결과를 갖고 있지 않다.** 운영 DB는 다를 수 있으나 강한 신호다.

---

## 6. 절대 바꾸면 안 되는 것

### T4 = 파일 1건 = 1행

정합성 규칙이 `TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)` 다. 이걸 어기면 배치 전체의
대사가 깨진다. `SKIPPED`(멱등으로 건너뛴 건)는 이번 배치가 처리한 것이 아니므로 T4 에 넣지 않는다.

### `CMMN_FILE_ENC_YN = 'N'` 인 파일

암호화되지 않은 파일이다. 복호화하면 **멀쩡한 원본이 깨진다.** `DecryptService` 가 먼저 거른다.

---

## 5. Turaco 레포가 생기면 — 병합 절차

이 프로젝트는 **Turaco 레포가 나오기 전에 먼저 만든 것**이다. Turaco 가 만들어 주는 레포에는
템플릿 소스가 들어 있으므로, 그쪽을 base 로 두고 우리 소스만 얹는다.

```bash
git clone https://github.com/twolinecloud/data_voice-collector-<suffix>.git
cd data_voice-collector-<suffix>

# 우리 소스만 덮어쓴다
cp -r <이 프로젝트>/src/main/java/egovframework      src/main/java/
cp -r <이 프로젝트>/src/main/resources/*             src/main/resources/
cp -r <이 프로젝트>/src/test/java/egovframework       src/test/java/
```

| 파일 | 처리 |
|---|---|
| `Jenkinsfile` · `src/main/docker/Dockerfile` | **Turaco 것 유지** — 변수는 Jenkins Job 이 주입한다 |
| `.gitattributes` · `settings.xml` · `.editorconfig` | **Turaco 것 유지** |
| `pom.xml` | **Turaco 것을 base** 로, 우리가 쓴 의존성만 옮겨 적기 |
| `src/**` | **우리 것으로 덮어쓰기** |

`pom.xml` 은 `agent-connector` 것을 복사해 식별자만 바꿔 둔 상태다
(`groupId`·`artifactId`·`name`·`description`·`final-name`). Turaco 가 만든 것과 비교해
의존성 차이만 병합하면 된다.

### `data_HelmChart` 에도 차트가 필요하다

Jenkins 파이프라인이 `${GROUP_NAME}_HelmChart` 레포의 `$STAGE/$SERVICE_NAME/values.yaml` 을
찾아 이미지 태그를 갱신한다. Turaco 가 자동으로 넣어 주지 않으면
`pipeline/voice-collector-<suffix>/` 를 직접 만들어야 한다.

**PVC 가 필요하다** — 이 서비스는 음성 파일을 디스크에 받는다.

| 마운트 | 용량 | 용도 |
|---|---:|---|
| `/k8s/voice_raw` | 15Gi | ESB 수신 (접견 6.5GB + 전화 0.4GB × 여유) |
| `/k8s/voice_work` | 5Gi | 복호화 작업·멱등 표식 |

---

## 6. 미확정 사항

계획서 11장에 14건이 정리되어 있다. 코드에 영향이 큰 것만 추리면:

| # | 내용 | 영향 |
|---|---|---|
| Q1 | `TELP_STT_FLPTH_NM` 에 이미 STT 결과가 있는가 | 사실이면 전화 건은 복호화·STT 불필요 |
| Q13 | 전화 복호화를 메타빌드가 하는가 우리가 하는가 | `PhoneAriaDecryptor` 구현 필요/불필요 |
| Q4 | 로그 컬렉터 T4 요청 DTO 필드명 | `LogCollectorClient.FileProcReq` 가 추정값 |
| Q7 | ESB 수신 파일명 규칙 (`ORIGINAL` / `ESB_DAT`) | `.DAT` 면 업무 키가 없어 매핑 수단이 따로 필요 |
| Q8 | `rvs_key.txt` · `rvs-media-decryptor.html` | 접견 복호화 구현 불가 |
| Q15 | 조회 대상이 원장 테이블인가 I/F 뷰인가 | `BoramiVoiceMapper.xml` 의 테이블명 |

미구현 지점은 **조용히 빈 값을 돌려주지 않고 명시적으로 실패**시킨다.
"대상 0건"과 "아직 안 만들었음"이 같아 보이면 배치가 성공한 것처럼 보이기 때문이다.

---

## 7. 패키지 구조

```
src/main/resources/static/voice_collector_simulator.html   ← 시연용 조작 화면

egovframework.voice.collector
├─ controller/ VoiceBatchController(운영·시연) · VoiceMockController(Mock 전용)
├─ batch/      VoiceCollectService(본체) · Scheduler · IdempotencyGuard
├─ source/     보라미 조회 — Mock / Jdbc / EsbHttp2Db
├─ broker/     XVARM 브로커 — Mock / Rest
├─ sync/       파일 수신 — FileArrivalWatcher · EsbFileNamingPolicy · PhoneFileProvider
├─ decrypt/    복호화 — DecryptService · Noop / PhoneAria / MeetRvs
├─ stt/        STT — Mock / Npu · SttOutputStore(배치 폴더에 .txt/.json 저장)  ★ 텍스트를 외부로 보내지 않는다
├─ logging/    LogCollectorClient (T1 · T2 COLLECT/ANALYZE · T4)
├─ mapper/     BoramiVoiceMapper (MyBatis)
├─ model/      VoiceTarget · VoiceFile · SttResult · FileProcOutcome …
├─ util/       InmatePidGenerator · AudioFormatDetector · SilentWav
└─ config/     VoiceProperties · VoiceModeState(모드) · VoiceDirState(디렉터리) · RestTemplateConfig · EgovConfigDataAccess
```
