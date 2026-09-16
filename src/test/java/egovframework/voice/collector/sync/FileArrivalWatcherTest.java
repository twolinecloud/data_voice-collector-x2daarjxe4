package egovframework.voice.collector.sync;

import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.util.SilentWav;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수신 파일 탐색 — <b>브로커가 알려준 이름을 우선하는지</b>가 핵심이다.
 *
 * <p>파일명을 우리가 정책으로 추측하면, 브로커(또는 실제 XVARM)가 다른 이름으로 만들었을 때
 * 영영 찾지 못하고 타임아웃이 난다. 에러 메시지도 "대기 타임아웃" 이라 원인이 드러나지 않는다.</p>
 */
class FileArrivalWatcherTest {

    @TempDir
    Path tmp;

    private VoiceProperties props(Path meet, Path phone) {
        return new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.MOCK, "", "",
                        new VoiceProperties.Schema("", "", "", ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y")),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.MOCK, "", java.util.List.of(), 10, 3),
                new VoiceProperties.Phone(VoiceProperties.PhoneMode.MOCK),
                new VoiceProperties.Sync(20, 3, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Dirs(tmp.toString(), meet.toString(), phone.toString(), tmp.resolve("w").toString(),
                        tmp.resolve("out/meet").toString(), tmp.resolve("out/phone").toString()),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Batch("0 0 2 * * *", "0 */10 * * * *", 20, false,
                        List.of("0", "1"), 500, "VOICE_ANALYSIS", "TEST_BATCH", "UNSTRUCTURED", false));
    }

    private VoiceTarget meetTarget() {
        return new VoiceTarget(VoiceKind.MEET, "CORR1", "TARE-0001", "DOC1", "FK1",
                true, null, "/data001", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    private FileArrivalWatcher watcher(Path meet) throws Exception {
        Path phone = tmp.resolve("phone");
        Files.createDirectories(meet);
        Files.createDirectories(phone);
        VoiceProperties p = props(meet, phone);
        egovframework.voice.collector.config.VoiceDirState dirs = new egovframework.voice.collector.config.VoiceDirState(p);
        dirs.resetToConfigured();
        return new FileArrivalWatcher(p, dirs, new EsbFileNamingPolicy());
    }

    @Test
    @DisplayName("브로커가 알려준 파일명을 우선한다 — 정책으로 추측한 이름과 달라도 찾는다")
    void hintWinsOverPolicy() throws Exception {
        Path meet = tmp.resolve("meet");
        FileArrivalWatcher w = watcher(meet);
        // 브로커가 우리 정책과 전혀 다른 이름으로 만들었다
        Files.write(meet.resolve("R_IF001_BOR_KAI_20260915120000000_12345.DAT"), SilentWav.of(1));

        VoiceFile f = w.await(meetTarget(), "R_IF001_BOR_KAI_20260915120000000_12345.DAT");

        assertThat(f.path().getFileName().toString()).endsWith(".DAT");
        assertThat(f.sizeBytes()).isPositive();
        // .DAT 라도 매직 넘버로 포맷을 알아낸다
        assertThat(f.format()).isEqualTo("wav");
    }

    @Test
    @DisplayName("힌트가 없으면 정책(ORIGINAL)으로 업무 파일명을 찾는다")
    void fallsBackToPolicy() throws Exception {
        Path meet = tmp.resolve("meet2");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));

        VoiceFile f = w.await(meetTarget());

        assertThat(f.path().getFileName().toString()).isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("힌트가 빈 문자열이면 정책으로 되돌아간다")
    void blankHintFallsBack() throws Exception {
        Path meet = tmp.resolve("meet3");
        FileArrivalWatcher w = watcher(meet);
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));

        assertThat(w.await(meetTarget(), "  ").path().getFileName().toString())
                .isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("파일이 오지 않으면 타임아웃 — 기다린 경로를 메시지에 담는다")
    void timesOutWithContext() throws Exception {
        Path meet = tmp.resolve("meet4");
        FileArrivalWatcher w = watcher(meet);

        assertThatThrownBy(() -> w.await(meetTarget(), "never-arrives.m4a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-arrives.m4a")
                .hasMessageContaining("대기 타임아웃");
    }

    @Test
    @DisplayName("0 바이트 파일은 도착으로 보지 않는다 — 쓰는 중일 수 있다")
    void emptyFileIsNotArrived() throws Exception {
        Path meet = tmp.resolve("meet5");
        FileArrivalWatcher w = watcher(meet);
        Files.createFile(meet.resolve("empty.m4a"));

        assertThatThrownBy(() -> w.await(meetTarget(), "empty.m4a"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("isArrived 는 기다리지 않고 즉시 확인한다")
    void isArrivedDoesNotBlock() throws Exception {
        Path meet = tmp.resolve("meet6");
        FileArrivalWatcher w = watcher(meet);

        assertThat(w.isArrived(meetTarget())).isFalse();
        Files.write(meet.resolve("mock_meet_001.m4a"), SilentWav.of(1));
        assertThat(w.isArrived(meetTarget())).isTrue();
    }
}
