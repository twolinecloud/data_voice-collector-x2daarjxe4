package egovframework.voice.collector.model;

/**
 * STT 결과.
 *
 * @param text        인식 전문. 커넥터 페이로드의 {@code sttScriptText} 로 들어간다.
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
