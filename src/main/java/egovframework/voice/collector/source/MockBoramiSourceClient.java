package egovframework.voice.collector.source;

import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 보라미 조회 Mock — DB 없이 대상 목록을 만들어 낸다.
 *
 * <p>보라미 접속 계정도 ESB 연계도 없는 상태에서 <b>파이프라인 전체를 끝까지 돌려보기 위한</b>
 * 구현이다. 실제 스키마의 키 형식(교정번호 18자리, 녹취파일번호 26자리 등)을 흉내 내
 * 자릿수 때문에 나중에 터지는 일이 없게 한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockBoramiSourceClient implements BoramiSourceClient {

    /** 이 번호의 전화 건은 "보라미가 이미 STT 를 가지고 있는" 시나리오로 만든다(계획서 Q1). */
    private static final int PHONE_WITH_SOURCE_STT = 3;

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final MockDatasetState dataset;

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit) {
        List<VoiceTarget> list = new ArrayList<>();
        int n = Math.min(dataset.meetCount(), limit);
        for (int i = 1; i <= n; i++) {
            String seq = "%03d".formatted(i);
            list.add(new VoiceTarget(
                    VoiceKind.MEET,
                    "MOCKCORR%013d".formatted(i),            // CORR_NO VARCHAR2(18)
                    "MOCKTARE%018d".formatted(i),            // TARE_FILE_NO VARCHAR2(26)
                    "MOCKDOC" + seq,                          // DOC_ID VARCHAR2(20)
                    "XVARM/MOCK/FILEKEY/" + seq,             // FILEKEY
                    true,                                     // CMMN_FILE_ENC_YN = 'Y'
                    null,                                     // 접견은 별도 복호화 키를 DB 에서 받지 않는다
                    "/data001/doc01/recv/XVARM",
                    "mock_meet_" + seq + ".m4a",
                    null,
                    window.from().plusHours(1).plusMinutes(i)
            ));
        }
        log.info("[Source:MOCK] 접견 대상 {}건 (window={})", list.size(), window);
        return list;
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit) {
        List<VoiceTarget> list = new ArrayList<>();
        int n = Math.min(dataset.phoneCount(), limit);
        for (int i = 1; i <= n; i++) {
            String seq = "%03d".formatted(i);
            // 1건은 보라미가 이미 STT 를 가지고 있는 상황을 재현한다(계획서 Q1).
            // 경로만 주면 뒤에서 파일을 못 찾아 실패하므로, Mock 이 실제 파일까지 만들어 둔다.
            String sourceStt = (i == PHONE_WITH_SOURCE_STT) ? seedSourceStt(seq) : null;
            list.add(new VoiceTarget(
                    VoiceKind.PHONE,
                    "MOCKCORR%013d".formatted(i),
                    "MOCKTUID%042d".formatted(i),            // VRFC_ESTL_ID VARCHAR2(50)
                    null,
                    null,
                    true,
                    "MOCKKEY%093d".formatted(i),             // TELP_RECRD_FILE_ID VARCHAR2(100)
                    "/data001/phone/recv",
                    "mock_phone_" + seq + ".wav",
                    sourceStt,
                    window.from().plusHours(2).plusMinutes(i)
            ));
        }
        log.info("[Source:MOCK] 전화 대상 {}건 (window={})", list.size(), window);
        return list;
    }

    @Override
    public String mode() {
        return "MOCK";
    }

    /**
     * "보라미가 이미 만들어 둔 STT 텍스트" 를 흉내 내 파일을 깔아 둔다.
     *
     * <p>이 시나리오가 사실로 확인되면(Q1) 전화 건은 오디오를 만지지 않고 이 텍스트만
     * 가져다 쓰게 된다 — 복호화·STT 가 통째로 빠지는 큰 변화다. 그 경로가 실제로 도는지
     * Mock 에서도 끝까지 검증할 수 있어야 한다.</p>
     *
     * @return 생성한 파일 경로. 실패하면 null(해당 건은 일반 경로로 처리된다)
     */
    private String seedSourceStt(String seq) {
        Path file = Path.of(dirs.work(), "mock_source_stt", "phone_" + seq + ".txt");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, """
                        (보라미 기존 STT / 테스트 데이터)
                        여보세요 저 김수용입니다. 어머니 잘 계시죠.
                        연락처 010-9876-5432 로 전화 주세요. 주민번호는 900101-1234567 입니다.
                        """);
            }
            return file.toString();
        } catch (IOException e) {
            log.warn("[Source:MOCK] 기존 STT 파일 생성 실패 — 일반 경로로 처리된다 ({})", e.getMessage());
            return null;
        }
    }

    /** 테스트·시뮬레이터에서 임의 시각의 대상을 만들 때 쓴다. */
    public static VoiceTarget mockPhone(int i, LocalDateTime at) {
        return new VoiceTarget(VoiceKind.PHONE, "MOCKCORR%013d".formatted(i),
                "MOCKTUID%042d".formatted(i), null, null, true,
                "MOCKKEY%093d".formatted(i), "/data001/phone/recv",
                "mock_phone_%03d.wav".formatted(i), null, at);
    }
}
