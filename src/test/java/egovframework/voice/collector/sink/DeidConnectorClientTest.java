package egovframework.voice.collector.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.voice.collector.config.FaultInjector;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.SttResult;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.sync.EsbFileNamingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 커넥터로 나가는 페이로드 구조를 못 박는다.
 *
 * <p><b>이 테스트가 특히 중요한 이유</b>: 필드명이 {@code sttScriptText} 가 아니면 커넥터의
 * {@code UnstructuredTextField} 레지스트리에 걸리지 않는다. 그러면 AI-R NER 을 타지 않고
 * <b>비식별되지 않은 원문이 그대로 PPP 로 나간다</b> — 에러도 나지 않고 조용히 통과한다.
 * 코드 리뷰로는 잡기 어려운 종류의 사고라 테스트로 고정한다.</p>
 */
class DeidConnectorClientTest {

    private static final String BASE = "http://connector:8080";

    private DeidConnectorClient client;
    private MockRestServiceServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestTemplate rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        client = new DeidConnectorClient(props(100), rt, new FaultInjector());
    }

    private VoiceProperties props(int chunkSize) {
        return new VoiceProperties(
                new VoiceProperties.Source(VoiceProperties.SourceMode.MOCK, "", "",
                        new VoiceProperties.Schema("", "", "", ""),
                        new VoiceProperties.Flag("Y", "Y", "N", "Y")),
                new VoiceProperties.Broker(VoiceProperties.BrokerMode.MOCK, "", 100, 10),
                new VoiceProperties.Phone(VoiceProperties.PhoneMode.MOCK),
                new VoiceProperties.Sync("m", "p", "w", 10, 5, EsbFileNamingPolicy.Policy.ORIGINAL),
                new VoiceProperties.Decrypt(VoiceProperties.DecryptMode.SKIP, ""),
                new VoiceProperties.Stt(VoiceProperties.SttMode.MOCK, "", 30),
                new VoiceProperties.Sink(true, BASE, chunkSize, 30),
                new VoiceProperties.Batch("0 0 2 * * *", "0 */10 * * * *", 20, false,
                        List.of("0", "1", "2", "3", "5"), 500, "VOICE_BATCH", "VOICE", false));
    }

    private DeidConnectorClient.Entry entry(int i) {
        VoiceTarget t = new VoiceTarget(VoiceKind.PHONE, "CORR" + i, "KEY" + i, null, null,
                true, "DK" + i, "/p", "f" + i + ".wav", null, LocalDateTime.now());
        return new DeidConnectorClient.Entry("VP" + i, t, new SttResult("인식된 텍스트 " + i, "MOCK", 10, false));
    }

    @Test
    @DisplayName("STT 텍스트는 rawDataset.sttScriptText 로 나간다 — 철자가 틀리면 비식별을 통과해 버린다")
    void sendsSttScriptTextFieldName() throws Exception {
        List<String> captured = new ArrayList<>();
        server.expect(requestTo(BASE + "/deid/connect"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(req -> {
                    captured.add(((org.springframework.mock.http.client.MockClientHttpRequest) req)
                            .getBodyAsString());
                    return withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON).createResponse(req);
                });

        int sent = client.send("20260915VOC001", List.of(entry(1)));

        assertThat(sent).isEqualTo(1);
        JsonNode body = mapper.readTree(captured.get(0));
        JsonNode raw = body.path("payload").get(0).path("rawDataset");

        assertThat(raw.has("sttScriptText")).as("커넥터 레지스트리 등록명과 일치해야 한다").isTrue();
        assertThat(raw.path("sttScriptText").asText()).isEqualTo("인식된 텍스트 1");
        assertThat(DeidConnectorClient.STT_FIELD).isEqualTo("sttScriptText");
        server.verify();
    }

    @Test
    @DisplayName("engineType 은 AIR 로 명시한다 — 미지정이면 ADID(정형)로 떨어질 수 있다")
    void sendsEngineTypeAir() throws Exception {
        List<String> captured = new ArrayList<>();
        server.expect(requestTo(BASE + "/deid/connect"))
                .andRespond(req -> {
                    captured.add(((org.springframework.mock.http.client.MockClientHttpRequest) req)
                            .getBodyAsString());
                    return withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON).createResponse(req);
                });

        client.send("20260915VOC001", List.of(entry(1)));

        JsonNode item = mapper.readTree(captured.get(0)).path("payload").get(0);
        assertThat(item.path("engineType").asText()).isEqualTo("AIR");
    }

    @Test
    @DisplayName("헤더에 execId 와 dataTypeCd=VOICE 가 실린다")
    void sendsHeader() throws Exception {
        List<String> captured = new ArrayList<>();
        server.expect(requestTo(BASE + "/deid/connect"))
                .andRespond(req -> {
                    captured.add(((org.springframework.mock.http.client.MockClientHttpRequest) req)
                            .getBodyAsString());
                    return withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON).createResponse(req);
                });

        client.send("20260915VOC001", List.of(entry(1)));

        JsonNode header = mapper.readTree(captured.get(0)).path("header");
        assertThat(header.path("execId").asText()).isEqualTo("20260915VOC001");
        assertThat(header.path("dataTypeCd").asText()).isEqualTo("VOICE");
        assertThat(header.path("setTypeCd").asText()).isEqualTo("PREDICTION");
        assertThat(header.path("collectDtm").asText()).isNotBlank();
    }

    @Test
    @DisplayName("교정번호를 페이로드에 싣지 않는다 — managementNo 는 비식별 PID 다")
    void doesNotLeakCorrNo() throws Exception {
        List<String> captured = new ArrayList<>();
        server.expect(requestTo(BASE + "/deid/connect"))
                .andRespond(req -> {
                    captured.add(((org.springframework.mock.http.client.MockClientHttpRequest) req)
                            .getBodyAsString());
                    return withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON).createResponse(req);
                });

        client.send("20260915VOC001", List.of(entry(7)));

        String body = captured.get(0);
        assertThat(body).doesNotContain("CORR7");
        assertThat(mapper.readTree(body).path("payload").get(0).path("managementNo").asText())
                .isEqualTo("VP7");
    }

    @Test
    @DisplayName("청크 크기를 넘으면 나눠 보낸다")
    void splitsIntoChunks() {
        client = new DeidConnectorClient(props(2), restTemplateWith3Calls(), new FaultInjector());

        int sent = client.send("20260915VOC001", List.of(entry(1), entry(2), entry(3), entry(4), entry(5)));

        assertThat(sent).isEqualTo(5);   // 2 + 2 + 1
        server.verify();
    }

    private RestTemplate restTemplateWith3Calls() {
        RestTemplate rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                        requestTo(BASE + "/deid/connect"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        return rt;
    }

    @Test
    @DisplayName("sink 가 꺼져 있으면 호출하지 않는다")
    void skipsWhenDisabled() {
        VoiceProperties disabled = new VoiceProperties(
                props(100).source(), props(100).broker(), props(100).phone(), props(100).sync(), props(100).decrypt(),
                props(100).stt(),
                new VoiceProperties.Sink(false, BASE, 100, 30),
                props(100).batch());
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer strict = MockRestServiceServer.createServer(rt);

        int sent = new DeidConnectorClient(disabled, rt, new FaultInjector()).send("E1", List.of(entry(1)));

        assertThat(sent).isZero();
        strict.verify();   // 아무 호출도 기대하지 않았고, 실제로 없었다
    }
}
