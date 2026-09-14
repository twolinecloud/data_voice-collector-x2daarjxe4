package egovframework.voice.collector.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.sync.EsbFileNamingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 브로커(borami-xvarm-broker)와의 계약 검증.
 *
 * <p><b>여기서 지키려는 것</b>: 브로커가 {@code 202} 로 받고 상태를 따로 알려주는 구조라,
 * 클라이언트가 <b>폴링을 제대로 하는지</b>와 <b>응답의 filePath 를 흘리지 않는지</b>가 핵심이다.
 * 파일명을 우리가 추측하면 브로커가 다른 이름으로 만들었을 때 조용히 타임아웃이 난다.</p>
 */
class RestXvarmBrokerClientTest {

    private static final String BASE = "http://localhost:8082";

    private RestTemplate rt;
    private MockRestServiceServer server;
    private RestXvarmBrokerClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        client = new RestXvarmBrokerClient(props(), rt, new EsbFileNamingPolicy());
    }

    private VoiceProperties props() {
        return new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.MOCK, "", "",
                        new VoiceProperties.Schema("", "", "", ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y")),
                // 폴링 간격을 짧게 — 테스트가 몇 초씩 잡고 있을 이유가 없다
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.REST, BASE, 10, 3),
                new VoiceProperties.Sync("m", "p", "w", 10, 5, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Sink(false, "", 100, 30),
                new VoiceProperties.Batch("0 0 2 * * *", "0 */10 * * * *", 20, false,
                        List.of("0", "1"), 500, "VOICE_BATCH", "VOICE", false));
    }

    private VoiceTarget meet() {
        return new VoiceTarget(VoiceKind.MEET, "CORR1", "TARE-0001", "DOC1", "FK1",
                true, null, "/data001", "mock_meet_001.m4a", null, LocalDateTime.now());
    }

    @Test
    @DisplayName("202 로 접수하고 DONE 이 될 때까지 폴링한다")
    void pollsUntilDone() {
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"requestId":"VOC-TARE-0001","status":"ACCEPTED",
                               "expectedPath":"/data001/doc01/recv/XVARM/mock_meet_001.m4a"}"""));

        // 첫 조회는 RUNNING, 두 번째에 DONE — 폴링이 실제로 도는지 본다
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess("{\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess("""
                        {"requestId":"VOC-TARE-0001","status":"DONE",
                         "filePath":"/data001/doc01/recv/XVARM/mock_meet_001.m4a",
                         "fileName":"mock_meet_001.m4a","fileSize":64044}""",
                        MediaType.APPLICATION_JSON));

        XvarmBrokerClient.ExtractResult r = client.extract(meet());

        assertThat(r.requestId()).isEqualTo("VOC-TARE-0001");
        assertThat(r.filePath()).endsWith("mock_meet_001.m4a");
        assertThat(r.fileSize()).isEqualTo(64044L);
        server.verify();
    }

    @Test
    @DisplayName("요청에 docId·fileKey·requestId·fileName 을 모두 싣는다")
    void sendsAllIdentifiers() throws Exception {
        List<String> captured = new ArrayList<>();
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract"))
                .andRespond(req -> {
                    captured.add(((org.springframework.mock.http.client.MockClientHttpRequest) req)
                            .getBodyAsString());
                    return withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"status\":\"ACCEPTED\"}").createResponse(req);
                });
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"filePath\":\"/x/mock_meet_001.m4a\"}",
                        MediaType.APPLICATION_JSON));

        client.extract(meet());

        JsonNode body = mapper.readTree(captured.get(0));
        assertThat(body.path("docId").asText()).isEqualTo("DOC1");
        assertThat(body.path("fileKey").asText()).isEqualTo("FK1");
        assertThat(body.path("requestId").asText()).isEqualTo("VOC-TARE-0001");
        // ORIGINAL 정책이면 업무 파일명을 그대로 요청한다
        assertThat(body.path("fileName").asText()).isEqualTo("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("멱등 키는 대상 키에서 만든다 — 배치 재실행 시 중복 추출을 막는다")
    void requestIdIsDerivedFromTarget() {
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"ACCEPTED\"}"));
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"filePath\":\"/x/f.m4a\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.extract(meet()).requestId()).isEqualTo("VOC-TARE-0001");
        server.verify();   // 조회 URL 에 같은 키가 쓰였다
    }

    @Test
    @DisplayName("FAILED 면 예외를 던진다 — 없는 파일을 기다리지 않는다")
    void failsFast() {
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"ACCEPTED\"}"));
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess(
                        "{\"status\":\"FAILED\",\"errorCode\":\"EXTRACT_FAILED\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract(meet()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XVARM 추출 실패")
                .hasMessageContaining("EXTRACT_FAILED");
    }

    @Test
    @DisplayName("끝나지 않으면 타임아웃으로 끊는다 — 배치가 영원히 매달리지 않게")
    void timesOut() {
        server.expect(requestTo(BASE + "/api/v1/xvarm/extract"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"ACCEPTED\"}"));
        server.expect(ExpectedCount.manyTimes(),
                        requestTo(BASE + "/api/v1/xvarm/extract/VOC-TARE-0001"))
                .andRespond(withSuccess("{\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract(meet()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("타임아웃");
    }

    @Test
    @DisplayName("base-url 이 비어 있으면 즉시 실패한다 — REST 모드에서는 필수")
    void requiresBaseUrl() {
        VoiceProperties noUrl = new VoiceProperties(
                props().source(),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.REST, "", 10, 3),
                props().sync(), props().decrypt(), props().stt(), props().sink(), props().batch());

        assertThatThrownBy(() ->
                new RestXvarmBrokerClient(noUrl, rt, new EsbFileNamingPolicy()).extract(meet()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }
}
