package egovframework.voice.collector.model;

import java.time.LocalDateTime;

/**
 * 배치가 훑을 시간창.
 *
 * <p>주기배치는 10분마다 돌면서 "앞에서 못 돌린 것까지 다 하고 20분 전 것까지" 처리한다
 * (2026-09-11 회의). 그래서 <b>연속한 실행의 시간창이 겹친다</b> — 같은 파일을 두 번 STT 에
 * 태우지 않으려면 멱등 키 기반 제외가 반드시 필요하다.</p>
 *
 * @param from  시작(포함)
 * @param to    종료(미포함)
 * @param label 로그 표기용 — DAILY / PERIODIC / MANUAL
 */
public record BatchWindow(LocalDateTime from, LocalDateTime to, String label) {

    public static BatchWindow daily(LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        return new BatchWindow(today.minusDays(1), today, "DAILY");
    }

    public static BatchWindow periodic(LocalDateTime now, int lagMinutes) {
        return new BatchWindow(now.minusMinutes(lagMinutes), now, "PERIODIC");
    }

    public static BatchWindow manual(LocalDateTime from, LocalDateTime to) {
        return new BatchWindow(from, to, "MANUAL");
    }

    /** 시각이 창 [from, to) 안에 있는가 — 조회 SQL 의 {@code CRT_DT >= from AND CRT_DT < to} 와 같은 판정. */
    public boolean contains(LocalDateTime at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    @Override
    public String toString() {
        return "%s[%s ~ %s)".formatted(label, from, to);
    }
}
