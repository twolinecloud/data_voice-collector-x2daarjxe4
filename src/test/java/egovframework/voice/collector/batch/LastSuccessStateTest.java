package egovframework.voice.collector.batch;

import egovframework.voice.collector.config.VoiceDirState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [바로 실행]의 기준점 — <b>"이 시각까지는 다 수집했다" 는 뜻이어야 한다.</b>
 *
 * <p><b>왜 이 테스트가 생겼나</b>: 기준점에 <b>배치가 끝난 시각</b>을 적고 있었다. 일배치는
 * {@code [어제 00:00, 오늘 00:00)} 를 훑는데 지금 시각(예: 11:16)을 적으니, 보지도 않은
 * {@code [오늘 00:00, 11:16]} 을 수집했다고 거짓말한 셈이다. 그래서 [바로 실행]의 창이
 * {@code [11:16, 11:16]} 으로 비어 <b>아무것도 처리하지 못했다</b> — 화면 버튼에도
 * "바로 실행(11:16:17 ~ 현재)" 으로 그대로 드러났다.</p>
 */
class LastSuccessStateTest {

    @TempDir
    Path root;

    private LastSuccessState state;

    @BeforeEach
    void setUp() {
        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        state = new LastSuccessState(dirs);
    }

    @Test
    @DisplayName("훑은 창의 끝을 적는다 — 일배치를 돌려도 오늘 몫은 아직 안 본 것으로 남는다")
    void recordsTheWindowEndNotNow() {
        LocalDateTime windowEnd = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        state.record(windowEnd, "20260923TST001", 10);

        assertThat(state.lastSuccessAt()).isEqualTo(windowEnd);
        assertThat(state.lastSuccessAt()).as("'지금' 을 적으면 오늘 몫을 영영 못 본다")
                .isBefore(LocalDateTime.now());
    }

    @Test
    @DisplayName("성공 0건이면 옮기지 않는다 — 실패한 배치로 기준점을 밀면 그 구간이 사라진다")
    void doesNotMoveWhenNothingSucceeded() {
        state.record(LocalDateTime.now(), "20260923TST001", 0);

        assertThat(state.lastSuccessAt()).isNull();
    }

    @Test
    @DisplayName("뒤로는 가지 않는다 — 주기배치가 민 뒤에 일배치가 돌아도 되돌리지 않는다")
    void neverMovesBackwards() {
        LocalDateTime later = LocalDateTime.of(2026, 9, 23, 11, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 9, 23, 0, 0);

        state.record(later, "20260923TST002", 4);
        state.record(earlier, "20260923TST003", 10);

        assertThat(state.lastSuccessAt()).as("이미 끝난 구간을 다시 훑게 된다").isEqualTo(later);
        assertThat(state.snapshot()).containsEntry("lastExecId", "20260923TST002");
    }

    @Test
    @DisplayName("앞으로는 간다 — 같은 값이면 그대로 둔다")
    void movesForwardOnly() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 23, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 23, 11, 42);

        state.record(t1, "A", 1);
        state.record(t1, "B", 1);
        assertThat(state.snapshot()).as("같은 시각이면 갱신하지 않는다").containsEntry("lastExecId", "A");

        state.record(t2, "C", 1);
        assertThat(state.lastSuccessAt()).isEqualTo(t2);
        assertThat(state.snapshot()).containsEntry("lastExecId", "C");
    }

    @Test
    @DisplayName("다시 읽어도 남아 있다 — 파드가 재기동해도 기준점을 잃지 않는다")
    void survivesRestart() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 23, 11, 42);
        state.record(t, "20260923TST001", 3);

        VoiceDirState dirs = mock(VoiceDirState.class);
        when(dirs.baseDir()).thenReturn(root.toString());
        LastSuccessState reloaded = new LastSuccessState(dirs);

        assertThat(reloaded.lastSuccessAt()).isEqualTo(t);
    }
}
