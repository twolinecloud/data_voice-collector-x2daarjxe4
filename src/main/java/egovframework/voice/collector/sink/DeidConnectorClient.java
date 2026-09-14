package egovframework.voice.collector.sink;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.SttResult;
import egovframework.voice.collector.model.VoiceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STT 텍스트를 <b>비식별 커넥터</b>로 넘긴다 — 이 서비스의 종착점.
 *
 * <p><b>여기서 끝이다.</b> 비식별(AI-R NER)·PPP 전송·T5 로그는 커넥터가 이미 다 한다.
 * 수집 서비스가 그걸 다시 구현하면 R&amp;R 을 어기는 것이고, 두 곳에서 같은 일을 하게 된다.</p>
 *
 * <p><b>계약</b> — {@code POST {base}/deid/connect}</p>
 * <pre>
 * {
 *   "header":  { "execId": "20260915VOC001", "dataTypeCd": "VOICE",
 *                "collectDtm": "2026-09-15T02:00:00", "setTypeCd": "PREDICTION" },
 *   "payload": [ { "managementNo": "...", "engineType": "AIR",
 *                  "rawDataset": { "sttScriptText": "..." } } ]
 * }
 * </pre>
 *
 * <p><b>{@code sttScriptText} 철자가 생명이다.</b> 커넥터의 {@code UnstructuredTextField}
 * 레지스트리에 등록된 이름이 정확히 이것이라, 한 글자라도 다르면 AI-R NER 대상에서 빠지고
 * <b>비식별되지 않은 원문이 그대로 PPP 로 나간다</b>. 그래서 상수로 못 박는다.</p>
 *
 * <p><b>{@code engineType=AIR}</b> 을 명시한다. 미지정 시 {@code dataTypeCd} 로 유추되지만
 * 불명확하면 ADID(정형) 로 떨어지는 하위호환 규칙이 있어, 자유 텍스트가 필드 단위 엔진을 타게 된다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class DeidConnectorClient {

    /** 커넥터 UnstructuredTextField 레지스트리 등록명 — 절대 바꾸지 않는다. */
    public static final String STT_FIELD = "sttScriptText";

    /** 비정형 자유 텍스트는 AI-R(NER 인라인 마스킹) 경로다. */
    private static final String ENGINE_AIR = "AIR";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final VoiceProperties props;
    private final RestTemplate voiceRestTemplate;
    private final egovframework.voice.collector.config.FaultInjector faultInjector;

    /**
     * STT 결과를 청크로 나눠 커넥터에 보낸다.
     *
     * @return 전송 성공 건수
     */
    public int send(String execId, List<Entry> entries) {
        if (!props.sink().enabled()) {
            log.info("[Sink] 비활성(voice.sink.enabled=false) — {}건 전송 생략", entries.size());
            return 0;
        }
        String base = props.sink().connectorBaseUrl();
        if (!StringUtils.hasText(base)) {
            log.warn("[Sink] connector-base-url 미설정 — {}건 전송 생략", entries.size());
            return 0;
        }
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/deid/connect";

        int chunkSize = Math.max(props.sink().chunkSize(), 1);
        int sent = 0;
        for (int i = 0; i < entries.size(); i += chunkSize) {
            List<Entry> chunk = entries.subList(i, Math.min(i + chunkSize, entries.size()));
            if (postChunk(url, execId, chunk)) {
                sent += chunk.size();
            }
        }
        log.info("[Sink] 커넥터 전송 완료 — {}/{}건 (execId={})", sent, entries.size(), execId);
        return sent;
    }

    private boolean postChunk(String url, String execId, List<Entry> chunk) {
        List<Map<String, Object>> payload = new ArrayList<>(chunk.size());
        for (Entry e : chunk) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put(STT_FIELD, e.stt().text());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("managementNo", e.managementNo());
            item.put("engineType", ENGINE_AIR);
            item.put("rawDataset", raw);
            payload.add(item);
        }

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("execId", execId);
        header.put("dataTypeCd", props.batch().dataTypeCd());
        header.put("collectDtm", ISO.format(LocalDateTime.now().withNano(0)));
        header.put("setTypeCd", "PREDICTION");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("header", header);
        body.put("payload", payload);

        try {
            // 장애 시뮬레이션 — 전송 구간이 실패해도 배치가 죽지 않는지 본다.
            faultInjector.maybeInject(
                    egovframework.voice.collector.config.FaultInjector.Stage.SINK);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<JsonNode> res = voiceRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode env = res.getBody();
            if (env != null && env.hasNonNull("success") && !env.path("success").asBoolean()) {
                log.warn("[Sink] 커넥터 실패 응답 — {}", env.path("error_message").asText(""));
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.error("[Sink] 커넥터 호출 실패 ({}건) — {}", chunk.size(), ex.getMessage());
            return false;
        }
    }

    /**
     * 커넥터로 보낼 한 건.
     *
     * @param managementNo 연동 추적 식별자. 교정번호를 그대로 쓰지 않는다 — 그 자체가 식별자다.
     * @param stt          STT 결과
     */
    public record Entry(String managementNo, VoiceTarget target, SttResult stt) {}
}
