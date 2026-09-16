-- ════════════════════════════════════════════════════════════════════════════
--  보라미 Mock 샘플 데이터 — 시연·테스트용 최소 구성 (대상 12건)
-- ════════════════════════════════════════════════════════════════════════════
--  배치가 10초 안에 끝나도록 대상을 12건으로 제한한다. 시각은 <b>적재 시점 기준</b>으로 만든다 —
--  기동 시(spring.sql.init) 와 시뮬레이터 [Mock 데이터 초기화/생성](MockBoramiSeeder) 이 이 파일을 다시 돌린다.
--
--     일배치(DAILY)      창 [어제 00:00, 오늘 00:00)  ← 어제 09:10 ~ 09:50   접견 5 · 전화 5
--     주기배치(PERIODIC) 창 [지금-20분, 지금)          ← 5분 전               접견 1 · 전화 1
--
--  조회 SQL 의 필터가 실제로 동작하는지 보려고 <b>걸러져야 할 행</b>도 몇 건 섞었다(대상이 되지 않아
--  배치 시간에는 영향이 없다). 기대 결과 (specl codes = 0,1,2,3,5):
--     접견 selectMeetTargets  → MEET-001~005(어제) · MEET-006(5분 전)   = 6건
--        · MEET-007 : 녹취 DEL_YN='Y'            → 제외
--        · MEET-008 : 특이수용자 해제(RMV_YMD)    → 제외
--        · MEET-009 : SPECL_MNG_SE_CD='4'        → 제외(대상 코드 아님)
--     전화 selectPhoneTargets → TUID-PHONE-001~005(어제) · TUID-PHONE-006(5분 전) = 6건
--        · TUID-PHONE-007 : 녹음여부 'N'          → 제외
--        · TUID-PHONE-008 : DEL_DT 존재           → 제외
--
--  ⚠ 개인정보 컬럼(RCVER_NM·ACQT_RLTNS_NM·INTRL_TELNO)에는 실제 같은 값을 넣지 않는다.
--    수집 서비스가 이 컬럼들을 읽지 않는다는 사실을 테스트로도 지키기 위해서다.
--  ⚠ H2 문법(DATEADD · CAST(CURRENT_DATE AS TIMESTAMP)). 실DB 에는 절대 돌리지 않는다.
-- ════════════════════════════════════════════════════════════════════════════

-- ── 특이수용자 ─────────────────────────────────────────────────────────────
INSERT INTO TB_IMSC_PTPR_DT
  (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD,
   PTCR_PRSR_RMV_YMD, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  ('MOCK0000000000001', 1, '1', 'A01', '20260101', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  ('MOCK0000000000002', 1, '2', 'A01', '20260115', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  ('MOCK0000000000003', 1, '3', 'A02', '20260201', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  -- 해제된 특이수용자 — 조회에서 빠져야 한다
  ('MOCK0000000000004', 1, '0', 'A01', '20260101', '20260801', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  -- 대상 코드가 아닌 특별관리구분(4) — 조회에서 빠져야 한다
  ('MOCK0000000000005', 1, '4', 'A03', '20260301', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  ('MOCK0000000000006', 1, '0', 'A01', '20260401', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm'),
  ('MOCK0000000000007', 1, '5', 'A02', '20260501', NULL,       DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm', DATEADD(DAY, -30, CURRENT_TIMESTAMP), 'mockadm');

-- ── 공통파일기본 ────────────────────────────────────────────────────────────
INSERT INTO TB_SMSM_CMFI_BS
  (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN,
   CMMN_FILE_ENC_YN, DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  ('CMFI0000000000001', 'DOC0000000000001', 'mock_meet_001.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  -- 암호화되지 않은 파일 — NoopDecryptor 로 빠지는지 확인용
  ('CMFI0000000000002', 'DOC0000000000002', 'mock_meet_002.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'N', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000003', 'DOC0000000000003', 'mock_meet_003.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000004', 'DOC0000000000004', 'mock_meet_004.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000005', 'DOC0000000000005', 'mock_meet_005.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000006', 'DOC0000000000006', 'mock_meet_006.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000007', 'DOC0000000000007', 'mock_meet_007.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000008', 'DOC0000000000008', 'mock_meet_008.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm'),
  ('CMFI0000000000009', 'DOC0000000000009', 'mock_meet_009.m4a', '01', 'A', CURRENT_TIMESTAMP, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'mockadm', CURRENT_TIMESTAMP, 'mockadm');

-- ── XVARM 콘텐츠 메타 ───────────────────────────────────────────────────────
INSERT INTO ASYSCONTENTELEMENT (ELEMENTID, FILEKEY) VALUES
  ('DOC0000000000001', 'XVARM/2026/09/FILEKEY-0001'),
  ('DOC0000000000002', 'XVARM/2026/09/FILEKEY-0002'),
  ('DOC0000000000003', 'XVARM/2026/09/FILEKEY-0003'),
  ('DOC0000000000004', 'XVARM/2026/09/FILEKEY-0004'),
  ('DOC0000000000005', 'XVARM/2026/09/FILEKEY-0005'),
  ('DOC0000000000006', 'XVARM/2026/09/FILEKEY-0006'),
  ('DOC0000000000007', 'XVARM/2026/09/FILEKEY-0007'),
  ('DOC0000000000008', 'XVARM/2026/09/FILEKEY-0008'),
  ('DOC0000000000009', 'XVARM/2026/09/FILEKEY-0009');

-- ── 녹취파일내역 (접견) ─────────────────────────────────────────────────────
--   CRT_DT: ①~⑤ 어제 09:10~09:50(일배치 창) · ⑥ 5분 전(주기배치 창) · ⑦~⑨ 제외 대상
INSERT INTO TB_RERD_TFIN_DS
  (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,
   TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS,
   TARE_FILE_MG_VL, TARE_FLPTH_NM, DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN,
   CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  -- ①~⑤ 일배치(어제) — 정상
  ('MEET-001-0000000000000000', 'CI00001', '01', '20260911', 1, 'MOCK0000000000001', '20260911',
   'TRCD0000000000001', 'CMFI0000000000001', 'mock_meet_001.m4a', '091000', '092500',
   '5242880', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, 550, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 550, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  -- ② 암호화 N 인 공통파일을 참조한다
  ('MEET-002-0000000000000000', 'CI00001', '01', '20260911', 2, 'MOCK0000000000002', '20260911',
   'TRCD0000000000002', 'CMFI0000000000002', 'mock_meet_002.m4a', '092000', '093000',
   '4194304', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, 560, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 560, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('MEET-003-0000000000000000', 'CI00001', '01', '20260911', 3, 'MOCK0000000000003', '20260911',
   'TRCD0000000000003', 'CMFI0000000000003', 'mock_meet_003.m4a', '093000', '094000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, 570, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 570, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('MEET-004-0000000000000000', 'CI00001', '01', '20260911', 4, 'MOCK0000000000006', '20260911',
   'TRCD0000000000004', 'CMFI0000000000004', 'mock_meet_004.m4a', '094000', '095000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, 580, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 580, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('MEET-005-0000000000000000', 'CI00001', '01', '20260911', 5, 'MOCK0000000000007', '20260911',
   'TRCD0000000000005', 'CMFI0000000000005', 'mock_meet_005.m4a', '095000', '100000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, 590, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 590, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  -- ⑥ 주기배치(5분 전) — 정상
  ('MEET-006-0000000000000000', 'CI00001', '01', '20260912', 6, 'MOCK0000000000001', '20260912',
   'TRCD0000000000006', 'CMFI0000000000006', 'mock_meet_006.m4a', '100000', '101000',
   '4194304', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- ⑦ 녹취 삭제됨 → 제외되어야 한다
  ('MEET-007-0000000000000000', 'CI00001', '01', '20260912', 7, 'MOCK0000000000003', '20260912',
   'TRCD0000000000007', 'CMFI0000000000007', 'mock_meet_007.m4a', '110000', '111000',
   '3145728', '/data001/doc01/recv', 'Y', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- ⑧ 해제된 특이수용자 → 제외되어야 한다
  ('MEET-008-0000000000000000', 'CI00001', '01', '20260912', 8, 'MOCK0000000000004', '20260912',
   'TRCD0000000000008', 'CMFI0000000000008', 'mock_meet_008.m4a', '120000', '121000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- ⑨ 대상 코드 아님(4) → 제외되어야 한다
  ('MEET-009-0000000000000000', 'CI00001', '01', '20260912', 9, 'MOCK0000000000005', '20260912',
   'TRCD0000000000009', 'CMFI0000000000009', 'mock_meet_009.m4a', '130000', '131000',
   '3145728', '/data001/doc01/recv', 'N', 'N', 'N',
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm');

-- ── 사용자통화내역 (전화) ───────────────────────────────────────────────────
--   CRT_DT: ①~⑤ 어제 09:10~09:50(일배치 창) · ⑥ 5분 전(주기배치 창) · ⑦~⑧ 제외 대상
INSERT INTO TB_IMPH_UCDR_DS
  (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD,
   RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO, TELP_PCALL_BGNG_DT, TELP_PCALL_END_DT,
   TELP_PCALL_TIME, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT,
   CORR_INSTT_CD, TELP_USE_PLACE_NM, TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM,
   TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,
   CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)
VALUES
  -- ①~⑤ 일배치(어제) — 정상. ① 만 마약(1) 수용자라 코드 필터 테스트의 기준이 된다
  ('TUID-PHONE-001', 'P001', '01', 'MOCK0000000000001', 'L001',
   '(테스트)수신자1', '(테스트)관계', '000-0000-0001', '20260911091000', '20260911091500',
   300, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_001.wav',
   'PHONEKEY-0000000000000001', NULL, NULL,
   DATEADD(MINUTE, 550, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 550, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('TUID-PHONE-002', 'P001', '01', 'MOCK0000000000002', 'L001',
   '(테스트)수신자2', '(테스트)관계', '000-0000-0002', '20260911092000', '20260911092300',
   180, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_002.wav',
   'PHONEKEY-0000000000000002', NULL, NULL,
   DATEADD(MINUTE, 560, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 560, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  -- ③ 보라미가 이미 STT 를 가지고 있는 경우 (계획서 Q1 시나리오)
  ('TUID-PHONE-003', 'P001', '01', 'MOCK0000000000003', 'L001',
   '(테스트)수신자3', '(테스트)관계', '000-0000-0003', '20260911093000', '20260911093400',
   240, 'Y', 'Y', 2, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_003.wav',
   'PHONEKEY-0000000000000003', '/data001/stt/mock_phone_003.txt', NULL,
   DATEADD(MINUTE, 570, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 570, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('TUID-PHONE-004', 'P001', '01', 'MOCK0000000000006', 'L001',
   '(테스트)수신자4', '(테스트)관계', '000-0000-0004', '20260911094000', '20260911094200',
   120, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_004.wav',
   'PHONEKEY-0000000000000004', NULL, NULL,
   DATEADD(MINUTE, 580, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 580, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  ('TUID-PHONE-005', 'P001', '01', 'MOCK0000000000007', 'L001',
   '(테스트)수신자5', '(테스트)관계', '000-0000-0005', '20260911095000', '20260911095300',
   180, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_005.wav',
   'PHONEKEY-0000000000000005', NULL, NULL,
   DATEADD(MINUTE, 590, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm', DATEADD(MINUTE, 590, DATEADD(DAY, -1, CAST(CURRENT_DATE AS TIMESTAMP))), 'mockadm'),
  -- ⑥ 주기배치(5분 전) — 정상
  ('TUID-PHONE-006', 'P001', '01', 'MOCK0000000000002', 'L001',
   '(테스트)수신자6', '(테스트)관계', '000-0000-0006', '20260912100000', '20260912100300',
   180, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_006.wav',
   'PHONEKEY-0000000000000006', NULL, NULL,
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- ⑦ 녹음 안 됨 → 제외되어야 한다
  ('TUID-PHONE-007', 'P001', '01', 'MOCK0000000000001', 'L001',
   '(테스트)수신자7', '(테스트)관계', '000-0000-0007', '20260912102000', '20260912102100',
   60, 'N', 'Y', 1, 'CI00001', '(테스트)전화실', NULL, NULL,
   NULL, NULL, NULL,
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm'),
  -- ⑧ 삭제됨 → 제외되어야 한다
  ('TUID-PHONE-008', 'P001', '01', 'MOCK0000000000002', 'L001',
   '(테스트)수신자8', '(테스트)관계', '000-0000-0008', '20260912103000', '20260912103200',
   120, 'Y', 'Y', 1, 'CI00001', '(테스트)전화실', '/data001/phone/recv', 'mock_phone_008.wav',
   'PHONEKEY-0000000000000008', NULL, CURRENT_TIMESTAMP,
   DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm', DATEADD(MINUTE, -5, CURRENT_TIMESTAMP), 'mockadm');
