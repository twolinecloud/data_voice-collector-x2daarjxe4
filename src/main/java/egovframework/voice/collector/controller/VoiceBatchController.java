package egovframework.voice.collector.controller;

import egovframework.voice.collector.batch.IdempotencyGuard;
import egovframework.voice.collector.batch.VoiceBatchResult;
import egovframework.voice.collector.batch.VoiceBatchScheduler;
import egovframework.voice.collector.batch.VoiceCollectService;
import egovframework.voice.collector.broker.XvarmBrokerClient;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.decrypt.DecryptService;
import egovframework.voice.collector.logging.LogCollectorClient;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.source.BoramiSourceClient;
import egovframework.voice.collector.stt.SttClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 수집 배치 운영·시연용 API.
 *
 * <p>스케줄러를 기다리지 않고 배치를 돌려볼 수 있게 한다. 다음 주 시연에서
 * "지금 한 번 돌려 보겠습니다"가 가능해야 하기 때문이다.</p>
 */
@Tag(name = "1. 음성 수집 배치", description = "보라미 음성(접견·통화) 수집 → STT → 비식별 커넥터 전달")
@RestController
@RequestMapping(value = "/api/v1/voice", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VoiceBatchController {

    private final VoiceCollectService service;
    private final VoiceBatchScheduler scheduler;
    private final IdempotencyGuard idempotency;
    private final VoiceProperties props;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final LogCollectorClient logCollector;
    private final egovframework.voice.collector.config.VoiceModeState modeState;
    private final egovframework.voice.collector.config.FaultInjector faultInjector;
    private final egovframework.voice.collector.config.MockDatasetState dataset;

    @Operation(summary = "일배치 실행",
            description = "전날 00시 ~ 오늘 00시 구간을 처리한다. 스케줄과 무관하게 즉시 실행한다.")
    @PostMapping("/batches/daily")
    public VoiceBatchResult daily(@RequestParam(required = false) List<VoiceKind> kinds) {
        return service.run(BatchWindow.daily(LocalDateTime.now()), kinds, "MANUAL");
    }

    @Operation(summary = "주기배치 실행",
            description = "지금으로부터 periodic-lag-min 분 전까지를 처리한다(기본 20분).")
    @PostMapping("/batches/periodic")
    public VoiceBatchResult periodic(@RequestParam(required = false) List<VoiceKind> kinds) {
        return service.run(BatchWindow.periodic(LocalDateTime.now(), props.batch().periodicLagMin()),
                kinds, "MANUAL");
    }

    @Operation(summary = "구간 지정 실행",
            description = "임의 시간창을 처리한다. Mock 모드에서는 시간창과 무관하게 고정 대상이 나온다.")
    @PostMapping("/batches/manual")
    public VoiceBatchResult manual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<VoiceKind> kinds) {
        return service.run(BatchWindow.manual(from, to), kinds, "MANUAL");
    }

    @Operation(summary = "현재 구성 조회",
            description = "4개 스위치(source/broker/decrypt/stt)가 각각 어느 모드인지, 하류 연결이 살아 있는지 본다.")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> modes = new LinkedHashMap<>();
        modes.put("source", source.mode());
        modes.put("broker", broker.mode());
        modes.put("decrypt", decryptService.mode());
        modes.put("stt", sttClient.mode());

        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("enabled", props.sink().enabled());
        sink.put("connectorBaseUrl", blankToNull(props.sink().connectorBaseUrl()));
        sink.put("chunkSize", props.sink().chunkSize());

        Map<String, Object> logc = new LinkedHashMap<>();
        logc.put("enabled", logCollector.isEnabled());
        logc.put("baseUrl", blankToNull(logCollector.baseUrl()));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("scheduleEnabled", props.batch().scheduleEnabled());
        batch.put("running", scheduler.isRunning());
        batch.put("dailyCron", props.batch().dailyCron());
        batch.put("periodicCron", props.batch().periodicCron());
        batch.put("periodicLagMin", props.batch().periodicLagMin());
        batch.put("maxFilesPerRun", props.batch().maxFilesPerRun());
        batch.put("speclMngSeCd", props.batch().speclMngSeCd());
        batch.put("retainSourceFile", props.batch().retainSourceFile());

        Map<String, Object> dirs = new LinkedHashMap<>();
        dirs.put("meetDir", props.sync().meetDir());
        dirs.put("phoneDir", props.sync().phoneDir());
        dirs.put("workDir", props.sync().workDir());
        dirs.put("namingPolicy", props.sync().namingPolicy());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modes", modes);
        out.put("configuredModes", modeState.configured());
        out.put("sink", sink);
        out.put("logCollector", logc);
        out.put("batch", batch);
        out.put("dirs", dirs);
        // 장애 주입이 켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다 — 항상 노출한다.
        out.put("chaos", faultInjector.snapshot());
        out.put("dataset", dataset.snapshot());
        return out;
    }

    @Operation(summary = "멱등 표식 초기화",
            description = "처리 완료 표식을 지워 같은 대상을 다시 처리할 수 있게 한다. 시연 반복용.")
    @DeleteMapping("/idempotency")
    public Map<String, Object> clearIdempotency() {
        int cleared = idempotency.clearAll();
        return Map.of("cleared", cleared);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
