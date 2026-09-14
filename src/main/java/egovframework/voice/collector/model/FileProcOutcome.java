package egovframework.voice.collector.model;

/**
 * 파일 1건의 처리 결과 — <b>T4({@code TB_FILE_PROC_LOG}) 1행</b>에 대응한다.
 *
 * <p>정합성 규칙이 {@code TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)} 라서,
 * <b>파일 1건 = T4 1행</b>을 어기면 배치 전체의 대사가 깨진다.</p>
 *
 * @param target    대상
 * @param status    결과
 * @param errMsg    실패 사유(성공 시 null). PII 가 섞이지 않도록 원문을 넣지 않는다.
 * @param fileSize  처리한 파일 크기(byte)
 * @param sttChars  STT 결과 글자 수
 * @param elapsedMs 소요 시간
 */
public record FileProcOutcome(
        VoiceTarget target,
        ProcStatus status,
        String errMsg,
        long fileSize,
        int sttChars,
        long elapsedMs
) {

    public static FileProcOutcome success(VoiceTarget t, long size, int chars, long ms) {
        return new FileProcOutcome(t, ProcStatus.SUCCESS, null, size, chars, ms);
    }

    public static FileProcOutcome fail(VoiceTarget t, String msg, long ms) {
        return new FileProcOutcome(t, ProcStatus.FAIL, msg, 0L, 0, ms);
    }

    public static FileProcOutcome skipped(VoiceTarget t, String reason) {
        return new FileProcOutcome(t, ProcStatus.SKIPPED, reason, 0L, 0, 0L);
    }

    public boolean isSuccess() {
        return status == ProcStatus.SUCCESS;
    }
}
