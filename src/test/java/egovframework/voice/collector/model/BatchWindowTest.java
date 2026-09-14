package egovframework.voice.collector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간창 계산 — 회의에서 합의된 규칙을 그대로 검증한다.
 * "전날 00시부터 오늘 00시 전까지"(일배치), "20분 전 것까지"(주기배치).
 */
class BatchWindowTest {

    @Test
    @DisplayName("일배치는 전날 00시부터 오늘 00시 전까지다")
    void dailyCoversPreviousDay() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 2, 0, 0);

        BatchWindow w = BatchWindow.daily(now);

        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0, 0));
        assertThat(w.to()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0, 0));
        assertThat(w.label()).isEqualTo("DAILY");
    }

    @Test
    @DisplayName("주기배치는 지금으로부터 lag 분 전까지를 훑는다")
    void periodicCoversLagWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 14, 30, 0);

        BatchWindow w = BatchWindow.periodic(now, 20);

        assertThat(w.from()).isEqualTo(LocalDateTime.of(2026, 9, 15, 14, 10, 0));
        assertThat(w.to()).isEqualTo(now);
    }

    @Test
    @DisplayName("주기배치 창은 10분 간격으로 실행하면 서로 겹친다 — 멱등 처리가 필요한 이유")
    void periodicWindowsOverlap() {
        LocalDateTime first = LocalDateTime.of(2026, 9, 15, 14, 0, 0);
        LocalDateTime second = first.plusMinutes(10);

        BatchWindow w1 = BatchWindow.periodic(first, 20);
        BatchWindow w2 = BatchWindow.periodic(second, 20);

        // w2 의 시작이 w1 의 종료보다 앞이다 = 겹친다
        assertThat(w2.from()).isBefore(w1.to());
    }
}
