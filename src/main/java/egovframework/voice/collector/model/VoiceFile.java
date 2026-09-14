package egovframework.voice.collector.model;

import java.nio.file.Path;

/**
 * 수집 구간을 통과한 실제 파일.
 *
 * @param target   이 파일이 어느 대상의 것인지
 * @param path     로컬(또는 PV) 경로
 * @param sizeBytes 크기
 * @param format   추정 포맷(m4a/mp4/wav/unknown). ESB 수신 파일은 확장자가 {@code .DAT} 라
 *                 파일명으로 판별할 수 없어 매직 넘버와 원본 파일명으로 정한다.
 * @param decrypted 복호화를 거쳤는지
 */
public record VoiceFile(
        VoiceTarget target,
        Path path,
        long sizeBytes,
        String format,
        boolean decrypted
) {
    public VoiceFile withPath(Path newPath, long newSize, boolean nowDecrypted) {
        return new VoiceFile(target, newPath, newSize, format, nowDecrypted);
    }
}
