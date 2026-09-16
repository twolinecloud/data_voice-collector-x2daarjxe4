package egovframework.voice.collector.source;

import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보라미 4단 조인 SQL 검증 — H2 의 Mock 보라미를 실제로 조회한다.
 *
 * <p><b>왜 이 테스트가 필요한가</b>: MOCK 모드 E2E 는 SQL 을 한 줄도 실행하지 않는다.
 * 조인 순서·필터 조건·타입 매핑이 맞는지는 여기서만 드러난다. 특히 아래 세 가지는
 * 실연동에서 발견하면 비싸다.</p>
 * <ul>
 *   <li>삭제 플래그가 <b>셋</b>이다 — 녹취 {@code DEL_YN}, 녹음파일 {@code RECRD_FILE_DEL_YN},
 *       공통파일 {@code DEL_YN}. 하나라도 놓치면 이미 지워진 파일을 XVARM 에 요청하게 된다.</li>
 *   <li>해제된 특이수용자({@code PTCR_PRSR_RMV_YMD})를 걸러야 한다.</li>
 *   <li>{@code FETCH FIRST n ROWS ONLY} 가 Oracle·PostgreSQL·H2 에서 모두 도는지.</li>
 * </ul>
 *
 * <p>샘플 데이터에 <b>걸러져야 할 행</b>을 일부러 섞어 두었다
 * (기대 결과는 {@code data-borami-mock.sql} 머리말 참조).</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "voice.source.mode=DIRECT_JDBC")
class BoramiSourceJdbcTest {

    private static final List<String> SPECL = List.of("0", "1", "2", "3", "5");

    /** 주입되는 것은 라우터다(@Primary). 모드에 따라 실제 구현으로 위임한다. */
    @Autowired
    private BoramiSourceClient source;

    /** 지름길 대사처럼 JDBC 구현 고유 기능을 쓸 때는 직접 주입받는다. */
    @Autowired
    private JdbcBoramiSourceClient jdbc;

    @Autowired
    private MockBoramiSeeder seeder;

    private BatchWindow wide() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @Test
    @DisplayName("DIRECT_JDBC 모드에서는 라우터가 JDBC 구현으로 위임한다")
    void usesJdbcImplementation() {
        assertThat(source).isInstanceOf(BoramiSourceRouter.class);
        assertThat(source.mode()).isEqualTo("DIRECT_JDBC");
    }

    @Test
    @DisplayName("접견 4단 조인 — 삭제·해제·대상외 코드를 걸러 6건(일배치 5 + 주기 1)만 나온다")
    void meetQueryFiltersProperly() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        assertThat(targets).hasSize(6);
        assertThat(targets).extracting(VoiceTarget::idempotencyKey)
                .containsExactlyInAnyOrder("MEET-001-0000000000000000", "MEET-002-0000000000000000",
                        "MEET-003-0000000000000000", "MEET-004-0000000000000000",
                        "MEET-005-0000000000000000", "MEET-006-0000000000000000");
        assertThat(targets).allSatisfy(t -> assertThat(t.kind()).isEqualTo(VoiceKind.MEET));
    }

    @Test
    @DisplayName("접견 조회는 DOC_ID 와 XVARM FILEKEY 까지 끌어온다 — 브로커 호출에 둘 다 필요하다")
    void meetQueryResolvesXvarmKey() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        VoiceTarget first = targets.stream()
                .filter(t -> t.idempotencyKey().startsWith("MEET-001"))
                .findFirst().orElseThrow();

        assertThat(first.docId()).isEqualTo("DOC0000000000001");
        assertThat(first.fileKey()).isEqualTo("XVARM/2026/09/FILEKEY-0001");
        assertThat(first.encrypted()).isTrue();
    }

    @Test
    @DisplayName("CMMN_FILE_ENC_YN='N' 인 건은 encrypted=false 로 온다 — 복호화하면 원본이 깨진다")
    void meetQueryReflectsEncryptionFlag() {
        List<VoiceTarget> targets = source.findMeetTargets(wide(), SPECL, 100);

        VoiceTarget plain = targets.stream()
                .filter(t -> t.idempotencyKey().startsWith("MEET-002"))
                .findFirst().orElseThrow();

        assertThat(plain.encrypted()).isFalse();
    }

    @Test
    @DisplayName("전화 조회 — 녹음 안 됨·삭제된 건을 걸러 6건(일배치 5 + 주기 1)만 나온다")
    void phoneQueryFiltersProperly() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).hasSize(6);
        assertThat(targets).extracting(VoiceTarget::idempotencyKey)
                .containsExactlyInAnyOrder("TUID-PHONE-001", "TUID-PHONE-002", "TUID-PHONE-003",
                        "TUID-PHONE-004", "TUID-PHONE-005", "TUID-PHONE-006");
    }

    @Test
    @DisplayName("시간창별 대상 — 일배치 창은 접견 5·전화 5, 10분 주기 창은 접견 1·전화 1")
    void seedMatchesBatchWindows() {
        LocalDateTime now = LocalDateTime.now();
        BatchWindow daily = BatchWindow.daily(now);
        BatchWindow periodic = BatchWindow.periodic(now, 20);

        assertThat(source.findMeetTargets(daily, SPECL, 100)).hasSize(5);
        assertThat(source.findPhoneTargets(daily, SPECL, 100)).hasSize(5);
        assertThat(source.findMeetTargets(periodic, SPECL, 100)).extracting(VoiceTarget::idempotencyKey)
                .containsExactly("MEET-006-0000000000000000");
        assertThat(source.findPhoneTargets(periodic, SPECL, 100)).extracting(VoiceTarget::idempotencyKey)
                .containsExactly("TUID-PHONE-006");
    }

    @Test
    @DisplayName("재적재하면 시각이 지금 기준으로 다시 깔린다 — 기동 후 시간이 지나도 주기배치 창에 1·1 이 남는다")
    void reseedRefreshesTimestamps() {
        java.util.Map<String, Object> r = seeder.reseed();

        assertThat(r.get("reseeded")).isEqualTo(true);
        BatchWindow periodic = BatchWindow.periodic(LocalDateTime.now(), 20);
        assertThat(source.findMeetTargets(periodic, SPECL, 100)).hasSize(1);
        assertThat(source.findPhoneTargets(periodic, SPECL, 100)).hasSize(1);
        assertThat(source.findMeetTargets(wide(), SPECL, 100)).hasSize(6);
    }

    @Test
    @DisplayName("전화 조회는 복호화 KEY(TELP_RECRD_FILE_ID)를 함께 가져온다")
    void phoneQueryCarriesDecryptKey() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).allSatisfy(t -> assertThat(t.decryptKey()).isNotBlank());
    }

    @Test
    @DisplayName("기존 STT 가 있는 건은 sourceSttPath 가 채워져 온다(Q1 시나리오)")
    void phoneQueryDetectsExistingStt() {
        List<VoiceTarget> targets = source.findPhoneTargets(wide(), SPECL, 100);

        assertThat(targets).filteredOn(VoiceTarget::hasSourceStt)
                .hasSize(1)
                .allSatisfy(t -> assertThat(t.idempotencyKey()).isEqualTo("TUID-PHONE-003"));
    }

    @Test
    @DisplayName("시간창 밖은 조회되지 않는다 — 증분 수집의 기본")
    void windowIsRespected() {
        LocalDateTime now = LocalDateTime.now();
        BatchWindow future = BatchWindow.manual(now.plusDays(1), now.plusDays(2));

        assertThat(source.findMeetTargets(future, SPECL, 100)).isEmpty();
        assertThat(source.findPhoneTargets(future, SPECL, 100)).isEmpty();
    }

    @Test
    @DisplayName("limit 이 FETCH FIRST 로 걸린다 — Oracle·PostgreSQL·H2 공통 문법")
    void limitIsApplied() {
        assertThat(source.findPhoneTargets(wide(), SPECL, 1)).hasSize(1);
    }

    @Test
    @DisplayName("특별관리구분코드를 좁히면 대상이 줄어든다")
    void speclCodeFilterWorks() {
        // 마약(1) 만 — MOCK0000000000001 한 명
        List<VoiceTarget> onlyDrug = source.findPhoneTargets(wide(), List.of("1"), 100);

        assertThat(onlyDrug).hasSize(1);
        assertThat(onlyDrug.get(0).idempotencyKey()).isEqualTo("TUID-PHONE-001");
    }

    @Test
    @DisplayName("지름길 필터(TELP_PTCR_PRSR_YN)와 정공법의 건수를 대사한다")
    void shortcutMatchesJoinResult() {
        int viaJoin = source.findPhoneTargets(wide(), SPECL, 100).size();
        int viaFlag = jdbc.countByFlagShortcut(wide(), 100);

        // Mock 데이터에서는 일치한다. 실연동에서 어긋나면 플래그를 믿을 수 없다는 뜻이다(계획서 4.2).
        assertThat(viaFlag).isEqualTo(viaJoin);
    }
}
