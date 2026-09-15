# voice-collector — 비정형 음성 수집 서비스

보라미의 수용자 음성(접견·통화)을 골라 가져와 **STT 텍스트로 만들어 비식별 커넥터에 넘기는** 서비스.

> 설계 근거: `data_agent-connector-dp8qbi7xqh/study/회의록/9_11/음성수집_서비스_개발계획_초안_v2.md`

---

## 1. 이 서비스가 하는 일 / 하지 않는 일

```
보라미 조회 → XVARM 브로커 → 파일 수신 → 복호화 → STT → ┃ POST /deid/connect
     (이 서비스의 범위)                                  ┃
                                                        ▼
                                    agent-connector : AI-R 비식별 → PPP 전송
                                    log-collector   : T1 · T2 · T4 적재
```

| 하는 일 | 하지 않는 일 (다른 서비스 책임) |
|---|---|
| 대상 선별(특이수용자 필터) | 비식별 → `agent-connector` |
| XVARM 추출 요청 | PPP 전송 → `agent-connector` |
| 파일 수신·포맷 판별 | 로그 테이블 INSERT → `log-collector` (API 로만 적재) |
| 복호화 | 재식별 매핑 → `data-collector` |
| STT 호출 | STT 엔진 자체 → NPU 서버 |

**R&R 은 2026-08-19 에 확정된 것이다.** 로그 테이블(T1~T11)의 단일 writer 는 로그 컬렉터이고,
커넥터조차 자기 DB INSERT 코드를 걷어냈다. 이 서비스도 자체 로그 테이블을 만들지 않는다.

---

## 2. 4개 스위치 — 준비된 것부터 실물로

외부 의존 네 가지가 서로 다른 시점에 준비된다. 하나가 막혀도 나머지는 진행되도록 각각 독립 스위치다.

| 스위치 | 값 | 기본 | 실물 전환 조건 |
|---|---|---|---|
| `voice.source.mode` | `MOCK` / `DIRECT_JDBC` / `ESB_HTTP2DB` | MOCK | 인터페이스ID(Q2)·I/F 테이블(Q15) |
| `voice.broker.mode` | `MOCK` / `REST` | MOCK | XVARM 사양(Q3), 브로커 배포 |
| `voice.decrypt.mode` | `SKIP` / `REAL` | SKIP | 복호화 주체 확정(Q13), 키 수령(Q8) |
| `voice.stt.mode` | `MOCK` / `NPU` | MOCK | NPU API 사양(Q9) |

> **REAL 모드는 실패해도 Mock 으로 빠지지 않는다**(Fail-fast). Mock 결과가 실제인 양 섞이면
> 시연·검증이 통째로 무의미해지기 때문이다. 커넥터와 같은 정책이다.

### 런타임 전환 — 재시작이 필요 없다

네 구현이 모두 빈으로 떠 있고, 라우터가 **호출 시점에** 현재 모드를 보고 위임한다.
그래서 시연 중에도 "이건 아직 Mock, 이건 실물"을 바꿔 가며 보여줄 수 있다.

```bash
curl -X PUT "http://localhost:8081/api/v1/mock/modes/stt?value=NPU"
curl      "http://localhost:8081/api/v1/mock/modes"      # 현재값 + 기동 설정값 + 허용값
curl -X POST "http://localhost:8081/api/v1/mock/modes/reset"
```

시뮬레이터 화면의 드롭다운이 이 API 를 호출한다.

> 런타임 변경은 **이 프로세스에만** 남는다. 재기동하면 설정값으로 돌아간다 —
> 운영에서 실수로 바꾼 모드가 영구히 남지 않게 하려는 것이다.

---

## 3. 로컬 실행

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

전 구간 MOCK 이라 **외부 의존이 하나도 없어도 배치가 끝까지 돈다.**

### 로컬 포트 배치

세 서비스를 동시에 띄워도 겹치지 않게 정해져 있다.

| 포트 | 서비스 | 비고 |
|---:|---|---|
| 8080 | `agent-connector` | 비식별 커넥터 |
| **8081** | **`voice-collector`** | 이 서비스 |
| 8090 | `log-collector` | context-path **`/logc`** |

> 커넥터와 로그 컬렉터의 포트는 각 프로젝트의 `application-local.yml` 에 이미 정해져 있어,
> 이 서비스가 8081 을 쓴다.

### 화면 두 개

| 주소 | 용도 |
|---|---|
| **`http://localhost:8081/voice_collector_simulator.html`** | **시뮬레이터** — 시연용 조작 화면 |
| `http://localhost:8081/swagger-ui.html` | Swagger — API 개별 호출·스펙 확인 |

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

시뮬레이터 화면 구성:

- **① 처리 구간 모드** — 4개 스위치를 **드롭다운으로 즉시 전환**(서버 재시작 불필요).
  MOCK/SKIP 은 주황, 실물은 초록. 하류(커넥터·로그 컬렉터) 연결 여부도 함께 본다
- **② 제어**
  - 배치: `10분 주기` · `일배치` · `Mock 초기화/생성` · `대상 미리보기` · `수신 파일` · `PII 잔여`
  - **대용량 Mock**: 접견·전화 건수 입력 + 프리셋(10 / 1,000 / 5,000 / 10,000건)
  - **장애 주입**: 활성 토글 + 실패 % · 지연 % · 지연 ms
- **③ 최근 배치 결과** — 대상 / 성공 / 실패 / 건너뜀 / 전송 / 상태 +
  **PII 원본 삭제 검증** 한 줄
- **④ 실행 로그** — 호출 API·응답 시간·결과 요약·실패 사유, 그리고
  `🔒 작업 폴더 내 잔여 파일: 0건 (삭제 완료)` 를 배치마다 명시적으로 출력

**시연 순서**: `Mock 데이터 초기화` → `처리 대상 미리보기` → `배치 실행` → **한 번 더 배치 실행**
(두 번째는 `건너뜀`으로 잡혀 멱등 동작이 드러난다) 

```bash
# CLI 로 하려면
curl -X POST "http://localhost:8080/api/v1/mock/reset"          # 초기화
curl      "http://localhost:8080/api/v1/mock/targets"           # 대상 미리보기
curl -X POST "http://localhost:8080/api/v1/voice/batches/daily" # 배치 실행
curl      "http://localhost:8080/api/v1/voice/status"           # 현재 구성
```

> `/api/v1/mock/**` 는 **시연 전용**이다. 운영 배포 시 인그레스·게이트웨이에서 차단한다.

### 커넥터에 실제로 붙여 보기

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--voice.sink.enabled=true --voice.sink.connector-base-url=http://localhost:8081"
```

**확인할 것**: 커넥터 응답에서 STT 텍스트의 성명·주민번호·전화번호가 **마스킹되어** 돌아오는지.
Mock STT 텍스트에는 일부러 개인정보를 넣어 두었다(전부 가공 데이터).
마스킹되지 않고 그대로 나온다면 `sttScriptText` 필드명이 어긋난 것이다 — 3장 참조.

### 4단 조인 SQL 검증 (H2 Mock)

H2 에 보라미 Mock 스키마가 올라간다. `voice.source.mode=DIRECT_JDBC` 로 바꾸면 실제 조회가 돈다.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--voice.source.mode=DIRECT_JDBC"
```

샘플에는 **걸러져야 할 행**을 섞어 두었다(삭제된 녹취, 해제된 특이수용자, 대상 아닌 관리코드,
녹음 안 된 통화). 기대 결과는 `data-borami-mock.sql` 머리말에 적어 두었다.

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

실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건이다. 10건짜리 Mock 으로는
스트리밍·청크 처리가 메모리를 지키는지 알 수 없어, 건수를 런타임에 올릴 수 있게 했다.

```bash
curl -X PUT "http://localhost:8081/api/v1/mock/dataset?meet=500&phone=500"
```

- 건수를 올려도 **힙이 비례해 늘지 않아야** 한다 — 파일은 스트리밍으로 다루고
  커넥터 전송은 청크(기본 100건)로 나눠 보낸다
- **200건을 넘으면** Mock WAV 를 1초짜리로 줄인다. 우리가 보려는 것은 건수에 대한
  메모리 거동이지 파일 크기가 아니고, 10,000건 × 2초는 640MB 라 디스크만 잡아먹는다
- **처리 시간 주의**: 건당 파일 안정성 검사(`stable-check-ms`, local 100ms)가 그대로
  곱해진다. 1,000건 ≈ 2분, 10,000건 ≈ 20분

### 장애 주입 (Chaos)

```bash
curl -X PUT "http://localhost:8081/api/v1/mock/chaos?enabled=true&failPercent=10&delayPercent=10&delayMs=2000"
```

STT·커넥터 전송 구간에 의도적으로 실패와 지연을 섞는다. **확인하려는 것은
파일 1건이 터져도 배치가 죽지 않고 `failCnt` 만 올리며 끝까지 도는가**이다.
1,300건 배치에서 한 건 때문에 전체가 멈추면 나머지를 전부 다시 처리해야 한다.

- 기본은 꺼져 있고, 켜져 있으면 `status` 와 시뮬레이터 화면에 항상 노출된다
  (켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다)
- 실패·지연은 **독립 판정** — 지연되면서 실패할 수도 있다
- 주입된 예외는 `InjectedFaultException` 이라 진짜 버그와 로그에서 구분된다

### PII 원본 즉시 삭제 검증

정책은 "복호화된 원본 음성은 STT 완료 즉시 삭제"다(계획서 5.3-(4)).
그런데 삭제는 `Files.deleteIfExists` 한 줄이라 실패해도 경고만 남고 지나간다.
**정책이 지켜졌다고 말하는 것과 지켜졌음을 보이는 것은 다르므로**, 배치 결과에 잔여 건수를 싣는다.

```
🔒 작업 폴더 내 잔여 파일: 0건 (삭제 완료)
```

- 삭제는 `processOne` 의 **`finally`** 에서 한다 — STT 가 실패하는 경로에서도 지워야 한다.
  성공 경로에서만 지우면 실패한 건의 음성이 디스크에 남는다
- `ResilienceE2ETest` 가 **전부 실패한 배치에서도 잔여 0** 임을 검증한다
- 언제든 확인: `GET /api/v1/mock/pii-residue`

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

### `sttScriptText`

커넥터의 `UnstructuredTextField` 레지스트리에 등록된 이름이다. **한 글자라도 다르면
AI-R NER 대상에서 빠지고, 비식별되지 않은 원문이 그대로 PPP 로 나간다.** 에러도 나지 않는다.

`DeidConnectorClient.STT_FIELD` 상수로 못 박았고 `DeidConnectorClientTest` 가 지킨다.

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
├─ stt/        STT — Mock / Npu
├─ sink/       DeidConnectorClient  ★ 종착점
├─ logging/    LogCollectorClient (T1·T2·T4)
├─ mapper/     BoramiVoiceMapper (MyBatis)
├─ model/      VoiceTarget · VoiceFile · SttResult · FileProcOutcome …
├─ util/       InmatePidGenerator · AudioFormatDetector · SilentWav
└─ config/     VoiceProperties · RestTemplateConfig · EgovConfigDataAccess
```
