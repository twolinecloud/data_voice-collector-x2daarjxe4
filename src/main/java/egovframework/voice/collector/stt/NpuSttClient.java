package egovframework.voice.collector.stt;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.SttResult;
import egovframework.voice.collector.model.VoiceFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * NPU STT 연동 — <b>운영 경로</b>.
 *
 * <p><b>API 사양이 아직 없다</b>(계획서 Q9). 아래는 흔한 형태(multipart 업로드 → JSON 응답)를
 * 가정한 구현이며, 실제 사양이 나오면 이 클래스만 고치면 된다.</p>
 *
 * <p><b>스트리밍으로 보낸다</b> — {@link FileSystemResource} 를 쓰면 RestTemplate 이 파일을
 * 통째로 메모리에 올리지 않는다. 접견 파일이 건당 5MB 수준이고 배치당 1,300건이라
 * 메모리에 올리기 시작하면 파드가 버티지 못한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class NpuSttClient implements SttClient {

    private final VoiceProperties props;
    private final RestTemplate voiceRestTemplate;

    @Override
    public SttResult transcribe(VoiceFile file) {
        String base = props.stt().baseUrl();
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException("voice.stt.base-url 이 비어 있다 — NPU 모드에서는 필수다");
        }
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/v1/stt";

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(file.path()));
        form.add("language", "ko");
        form.add("format", file.format());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> res = voiceRestTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);

        JsonNode body = res.getBody();
        if (body == null) {
            throw new IllegalStateException("NPU STT 빈 응답 — " + file.path().getFileName());
        }
        String text = body.path("text").asText("");
        int durationSec = body.path("durationSec").asInt(0);
        log.info("[STT:NPU] {} — {}자", file.path().getFileName(), text.length());
        return new SttResult(text, "NPU", durationSec, false);
    }

    @Override
    public String mode() {
        return "NPU";
    }
}
