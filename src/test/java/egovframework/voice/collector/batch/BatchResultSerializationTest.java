package egovframework.voice.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 결과 JSON 직렬화 — <b>파생 메서드가 응답에서 빠지지 않는지</b> 못 박는다.
 *
 * <p><b>왜 따로 테스트하는가</b>: 이 함정에 두 번 걸렸다. record 는 <b>컴포넌트만</b> 자동
 * 직렬화된다. {@code execStsCd()} 나 {@code message()} 처럼 값을 계산해 주는 메서드는
 * {@code get} 접두어도 없어 Jackson 이 조용히 건너뛴다 — 예외도 경고도 없이 화면에만
 * {@code undefined} 가 찍힌다. Java 객체를 직접 보는 테스트로는 절대 잡히지 않는다.</p>
 */
class BatchResultSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private VoiceBatchResult sample(int fail) {
        PiiResidueAuditor.Residue residue = new PiiResidueAuditor.Residue(0, 0, 0, 0, true);
        return new VoiceBatchResult("20260915VOC001", "DAILY[...]", 10, 10 - fail, fail,
                0, 10 - fail, 1234L, residue, List.of());
    }

    @Test
    @DisplayName("execStsCd 가 JSON 에 실린다 — record 컴포넌트가 아니라 파생 메서드다")
    void serializesExecStsCd() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample(0)));

        assertThat(json.has("execStsCd")).as("빠지면 화면 '상태' 칸이 비어 보인다").isTrue();
        assertThat(json.path("execStsCd").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("PII 잔여 메시지가 JSON 에 실린다 — 빠지면 화면에 undefined 가 찍힌다")
    void serializesResidueMessage() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample(0)));
        JsonNode residue = json.path("residue");

        assertThat(residue.has("message")).isTrue();
        assertThat(residue.path("message").asText())
                .isEqualTo("작업 폴더 내 잔여 파일: 0건 (삭제 완료)");
        // 컴포넌트도 함께 나와야 화면에서 내역을 보여줄 수 있다
        assertThat(residue.has("meetFiles")).isTrue();
        assertThat(residue.has("phoneFiles")).isTrue();
        assertThat(residue.has("workFiles")).isTrue();
        assertThat(residue.path("clean").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("잔여가 남으면 메시지가 경고 문구로 바뀐다")
    void residueMessageWhenDirty() throws Exception {
        PiiResidueAuditor.Residue dirty = new PiiResidueAuditor.Residue(2, 1, 0, 3, false);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(dirty));

        assertThat(json.path("clean").asBoolean()).isFalse();
        assertThat(json.path("message").asText())
                .contains("3건")
                .contains("삭제되지 않았습니다");
    }

    @Test
    @DisplayName("일부 실패면 PARTIAL, 전부 실패면 FAIL")
    void statusReflectsOutcome() throws Exception {
        assertThat(mapper.readTree(mapper.writeValueAsString(sample(3)))
                .path("execStsCd").asText()).isEqualTo("PARTIAL");
        assertThat(mapper.readTree(mapper.writeValueAsString(sample(10)))
                .path("execStsCd").asText()).isEqualTo("FAIL");
    }
}
