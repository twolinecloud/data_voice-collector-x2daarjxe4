package egovframework.voice.collector.decrypt;

import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceModeState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.util.SilentWav;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전화 복호화(시뮬레이션) — <b>Mock 데이터만 푼다.</b>
 *
 * <p>여기서 지키려는 경계가 둘이다. 하나는 "로컬에서 REAL 전 구간이 돌아야 한다" —
 * Mock 제공자가 암호화한 것을 같은 키로 풀어 낸다. 다른 하나는 "실물 경로로 새면 안 된다" —
 * ESB 연계에서는 이 복호화기가 손대지 않아 {@link PhoneAriaDecryptor} 가 사양 미확정을 말한다.</p>
 */
class PhoneSimAesDecryptorTest {

    private static final String KEY = "LOCAL_TEST_KEY_0123456789abcdefg";
    private static final String IV = "0000000000000000";

    @TempDir
    Path tmp;

    private Path keyFile;
    private VoiceModeState modeState;
    private PhoneSimAesDecryptor decryptor;

    private static VoiceTarget phone(boolean encrypted) {
        return new VoiceTarget(VoiceKind.PHONE, "SIM00000000000001", "SIM-PHONE-001", "DOC1", "KEY1",
                encrypted, null, "/src", "mock_phone_001.wav", null, LocalDateTime.now());
    }

    private static VoiceTarget meet() {
        return new VoiceTarget(VoiceKind.MEET, "SIM00000000000002", "SIM-MEET-001", "DOC2", "KEY2",
                true, null, "/src", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        keyFile = tmp.resolve("rvs_key.txt");
        Files.writeString(keyFile, String.join("\n",
                "secretkey=" + KEY,
                "iv=" + IV,
                "algorithm=AES",
                "ciphermode=CBC",
                "padding=PKCS5Padding",
                "charset=UTF-8"));

        VoiceProperties props = mock(VoiceProperties.class);
        VoiceProperties.Decrypt d = mock(VoiceProperties.Decrypt.class);
        when(d.rvsKeyPath()).thenReturn(keyFile.toString());
        when(props.decrypt()).thenReturn(d);

        VoiceDirState dirs = mock(VoiceDirState.class);
        Path work = tmp.resolve("work");
        Files.createDirectories(work);
        when(dirs.work()).thenReturn(work.toString());

        modeState = mock(VoiceModeState.class);
        when(modeState.phone()).thenReturn(VoiceProperties.PhoneMode.MOCK);

        decryptor = new PhoneSimAesDecryptor(props, dirs, modeState);
    }

    private VoiceFile encryptedPhoneFile() throws Exception {
        byte[] plain = SilentWav.of(1);
        byte[] cipher = MediaDecryptor.of(KEY, IV, "AES", "CBC", "PKCS5Padding", java.nio.charset.StandardCharsets.UTF_8).encrypt(plain);
        Path f = tmp.resolve("mock_phone_001.wav");
        Files.write(f, cipher);
        return new VoiceFile(phone(true), f, cipher.length, "wav", false);
    }

    @Test
    @DisplayName("Mock 제공자가 암호화한 것을 되돌린다 — 원본 바이트와 같아야 한다")
    void roundTripsMockData() throws Exception {
        byte[] plain = SilentWav.of(1);
        VoiceFile in = encryptedPhoneFile();

        VoiceFile out = decryptor.decrypt(in);

        assertThat(out.decrypted()).isTrue();
        assertThat(Files.readAllBytes(out.path())).isEqualTo(plain);
        assertThat(out.path().getFileName().toString()).startsWith("decrypted_");
    }

    @Test
    @DisplayName("복호화 결과는 작업 폴더에 둔다 — 수신 폴더에 쓰면 다음 배치가 새 파일로 본다")
    void writesIntoWorkDir() throws Exception {
        VoiceFile out = decryptor.decrypt(encryptedPhoneFile());

        assertThat(out.path().getParent()).isEqualTo(tmp.resolve("work"));
    }

    @Test
    @DisplayName("전화 Mock 이면 맡는다")
    void supportsMockPhone() {
        assertThat(decryptor.supports(new VoiceFile(phone(true), tmp, 1, "wav", false))).isTrue();
    }

    @Test
    @DisplayName("실물 ESB 연계면 손대지 않는다 — ARIA 스텁이 사양 미확정을 말해야 한다")
    void staysOutOfTheRealPath() {
        when(modeState.phone()).thenReturn(VoiceProperties.PhoneMode.ESB);

        assertThat(decryptor.supports(new VoiceFile(phone(true), tmp, 1, "wav", false)))
                .as("여기서 참이면 시뮬레이션 복호화가 운영 데이터에 달려든다").isFalse();
    }

    @Test
    @DisplayName("암호화 안 된 건과 접견 건은 넘기지 않는다")
    void ignoresPlainAndMeet() {
        assertThat(decryptor.supports(new VoiceFile(phone(false), tmp, 1, "wav", false))).isFalse();
        assertThat(decryptor.supports(new VoiceFile(meet(), tmp, 1, "m4a", false))).isFalse();
    }

    @Test
    @DisplayName("평문을 암호문으로 알고 풀면 사유를 분명히 말한다")
    void explainsWhenInputIsNotCipherText() throws Exception {
        Path f = tmp.resolve("plain.wav");
        Files.write(f, SilentWav.of(1));
        VoiceFile in = new VoiceFile(phone(true), f, Files.size(f), "wav", false);

        try {
            decryptor.decrypt(in);
            org.junit.jupiter.api.Assertions.fail("평문을 풀었다고 넘어가면 안 된다");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("전화 복호화 실패(시뮬레이션)");
        }
    }
}
