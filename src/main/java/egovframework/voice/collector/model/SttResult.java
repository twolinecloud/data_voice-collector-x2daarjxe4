package egovframework.voice.collector.model;

/**
 * STT 결과.
 *
 * @param text        인식 전문. 외부로 보내지 않는다 — 글자 수만 배치 결과(FileProcOutcome)에 실린다.
 * @param engine      엔진 표기(로그용)
 * @param durationSec 오디오 길이(초). 모르면 0
 * @param fromSource  보라미가 이미 가지고 있던 STT 를 가져온 것인지 — 우리가 돌린 게 아니면 true
 */
public record SttResult(String text, String engine, int durationSec, boolean fromSource) {

    public int charCount() {
        return text == null ? 0 : text.length();
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
