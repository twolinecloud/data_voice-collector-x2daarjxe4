package egovframework.voice.collector.transfer;

import egovframework.voice.collector.config.DeployEnvPreset;
import egovframework.voice.collector.model.VoiceKind;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 커넥터 이관 — STT 산출물을 넘긴다.
 *
 * <p><b>1차 저장은 우리 몫, 이관은 커넥터 몫이다.</b> 배치는 STT 결과를
 * {@code {ROOT}/xenon/{meet|phone}/{execId}/} 에 먼저 남기고(여기까지가 이 서비스의 완결),
 * 그 뒤 커넥터의 이관 API 를 부른다. 이관이 실패해도 <b>배치는 성공으로 둔다</b> —
 * 우리 구간의 산출물은 이미 디스크에 있고, 못 넘긴 것은 폴더를 보고 다시 넘기면 된다.
 * 이관 실패로 STT 를 다시 돌리는 것은 낭비다.</p>
 *
 * <p><b>비식별은 켜지 않는다</b>({@code deidentificationEnabled=false}). 적용하지 않기로
 * 결정되어 커넥터가 ADID/AIR 를 호출하지 않고 원본 그대로 적재한다.</p>
 */
@Log4j2
@Component
public class AgentConnectorClient {

    /** 이관 결과 한 장 — 화면·로그가 그대로 읽는다. */
    public record TransferResult(boolean enabled, boolean success, int sent, String baseUrl,
                                 String message, String dir) {

        static TransferResult off(String why) {
            return new TransferResult(false, true, 0, null, why, null);
        }
    }

    private final RestTemplate rest;
    private final DeployEnvPreset env;

    @Value("${agent-connector.enabled:true}")
    private boolean enabled;

    /** 비면 환경 프리셋 — 로컬 {@code http://localhost:8080} · K8s 커넥터 서비스명. */
    @Value("${agent-connector.base-url:}")
    private String baseUrlConfigured;

    @Value("${agent-connector.deidentification-enabled:false}")
    private boolean deidentificationEnabled;

    public AgentConnectorClient(RestTemplate voiceRestTemplate, DeployEnvPreset env) {
        this.rest = voiceRestTemplate;
        this.env = env;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String baseUrl() {
        return StringUtils.hasText(baseUrlConfigured) ? baseUrlConfigured.trim() : env.agentConnectorBaseUrl();
    }

    /**
     * STT 산출물을 커넥터로 넘긴다.
     *
     * @param execId  이 배치의 실행 ID — 커넥터가 파일명 접두로 쓴다
     * @param outputs 넘길 산출물(종류 · 파일명 · 본문)
     */
    public TransferResult send(String execId, List<Output> outputs) {
        if (!enabled) {
            return TransferResult.off("이관 비활성(agent-connector.enabled=false)");
        }
        if (outputs == null || outputs.isEmpty()) {
            return TransferResult.off("넘길 산출물이 없습니다");
        }
        String base = baseUrl();
        if (!StringUtils.hasText(base)) {
            return new TransferResult(true, false, 0, null, "커넥터 주소가 비어 있습니다", null);
        }
        String url = base.replaceAll("/+$", "") + "/api/v1/pipeline/ingest/voice"
                + "?deidentificationEnabled=" + deidentificationEnabled;

        List<Map<String, Object>> files = new ArrayList<>(outputs.size());
        for (Output o : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", o.kind().name());
            m.put("fileName", o.fileName());
            m.put("text", o.text());
            files.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execId", execId);
        body.put("source", "voice-collector");
        body.put("files", files);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<JsonNode> res =
                    rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode b = res.getBody();
            int stored = b == null ? 0 : b.path("stored").asInt(0);
            boolean ok = b != null && b.path("success").asBoolean(false);
            String dir = b == null ? null : b.path("dirs").path("meet").asText(null);
            String msg = b == null ? "응답 본문 없음" : b.path("message").asText("");
            log.info("[Transfer] 커넥터 이관 — execId={} {}건 → {} ({})", execId, stored, base, msg);
            return new TransferResult(true, ok, stored, base, msg, dir);
        } catch (ResourceAccessException e) {
            // 붙지 못한 것 — 배치를 실패시키지 않는다. 산출물은 우리 폴더에 그대로 있다.
            String why = e.getCause() == null || e.getCause().getMessage() == null
                    ? e.getClass().getSimpleName() : e.getCause().getMessage();
            log.warn("[Transfer] 커넥터에 붙지 못했습니다 — {} ({}) · 산출물은 1차 저장 폴더에 남아 있습니다", base, why);
            return new TransferResult(true, false, 0, base,
                    "커넥터에 붙지 못했습니다 — 파드가 떠 있는지 확인하십시오. [" + why + "]", null);
        } catch (Exception e) {
            log.warn("[Transfer] 커넥터 이관 실패 — {} ({})", base, e.toString());
            return new TransferResult(true, false, 0, base, "이관 실패 — " + e.getMessage(), null);
        }
    }

    /** 넘길 산출물 한 건. */
    public record Output(VoiceKind kind, String fileName, String text) { }
}
