package egovframework.voice.collector.batch;

import com.fasterxml.jackson.annotation.JsonProperty;
import egovframework.voice.collector.model.FileProcOutcome;

import java.util.List;

/**
 * 배치 1회 실행 결과.
 *
 * @param execId     로그 컬렉터가 채번한 EXEC_ID. 미연동이면 로컬 임시 ID
 * @param window     처리한 시간창
 * @param targetCnt  조회된 대상 수
 * @param successCnt STT 까지 성공한 수
 * @param failCnt    실패 수
 * @param skippedCnt 이미 처리되어 건너뛴 수(멱등)
 * @param sentCnt    커넥터로 전송된 수
 * @param elapsedMs  소요 시간
 * @param residue    처리 후 원본 음성 잔여 현황 — PII 즉시 삭제 정책(계획서 5.3-(4)) 검증
 */
public record VoiceBatchResult(
        String execId,
        String window,
        int targetCnt,
        int successCnt,
        int failCnt,
        int skippedCnt,
        int sentCnt,
        long elapsedMs,
        PiiResidueAuditor.Residue residue,
        List<FileProcOutcome> outcomes
) {

    /**
     * 배치 상태 코드(C04) — 하나도 실패하지 않았으면 SUCCESS, 일부만 성공이면 PARTIAL.
     *
     * <p>{@code @JsonProperty} 가 필요한 이유: record 는 <b>컴포넌트만</b> 자동 직렬화된다.
     * 파생 값을 주는 이런 메서드는 {@code get} 접두어도 없어 Jackson 이 그냥 건너뛴다 —
     * 응답에서 조용히 빠져 화면에 빈칸으로 나온다.</p>
     */
    @JsonProperty("execStsCd")
    public String execStsCd() {
        if (failCnt == 0) {
            return "SUCCESS";
        }
        return successCnt > 0 ? "PARTIAL" : "FAIL";
    }

    public String summary() {
        return "execId=%s %s 대상%d 성공%d 실패%d 건너뜀%d 전송%d (%.1f초) · %s"
                .formatted(execId, window, targetCnt, successCnt, failCnt, skippedCnt,
                        sentCnt, elapsedMs / 1000.0,
                        residue == null ? "잔여 미확인" : residue.message());
    }
}
