package egovframework.voice.collector.batch;

/**
 * 재처리를 <b>어느 단계부터</b> 이어서 할지.
 *
 * <p>단계 구분은 T4 {@code STEP_TYPE_CD}(C05)와 같다 — COLLECT · ANALYZE · SEND.
 * 복호화는 별도 단계가 아니라 수집에 딸린 작업이라 {@code COLLECT} 안에 있다.</p>
 *
 * <p><b>보존물이 없으면 앞 단계부터 한다.</b> 이어서 하기는 <i>빠른 길</i>이지 <i>유일한 길</i>이
 * 아니다. {@code voice.resume.keep-on-failure=false} 로 중간 산출물을 남기지 않는 환경에서도
 * 재처리는 그대로 돌아야 하므로, 찾지 못하면 조용히 앞 단계로 내려간다(사유는 로그에 남는다).</p>
 */
public enum ResumeMode {

    /** 처음부터 — 수집·복호화·STT·저장 전부. */
    FULL,

    /**
     * STT 부터 — 수집·복호화를 건너뛰고 보존된 복호화 오디오({@code {ROOT}/xvram/decoding})를 쓴다.
     * 없으면 {@link #FULL} 로 내려간다.
     */
    FROM_ANALYZE,

    /**
     * 최종 저장부터 — 보존된 전사 결과({@code {ROOT}/stt_temp/{execId}})를 쓴다.
     * 없으면 {@link #FROM_ANALYZE} 로, 그것도 없으면 {@link #FULL} 로 내려간다.
     */
    FROM_SEND
}
