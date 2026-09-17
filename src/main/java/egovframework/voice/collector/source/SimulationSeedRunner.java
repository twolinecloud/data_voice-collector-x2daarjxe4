package egovframework.voice.collector.source;

import egovframework.voice.collector.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 시 시뮬레이션 데이터를 자동 생성한다({@code voice.sim.seed-on-startup=true}, 로컬 기본).
 *
 * <p>로컬 H2 는 매 기동마다 빈 상태로 뜨므로 여기서 10건과 더미 파일을 깔아야 화면을 열자마자 배치를 돌릴 수 있다.
 * 개발계(PostgreSQL)는 남의 시험을 방해하지 않게 기본으로 끄고, 시뮬레이터 [시뮬레이션 데이터 생성] 으로만 만든다.
 * 실패해도 기동은 막지 않는다 — 사유를 로그에 남기고 버튼으로 다시 시도할 수 있다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SimulationSeedRunner implements ApplicationRunner {

    private final VoiceProperties props;
    private final SimulationDataService sim;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.sim().seedOnStartup()) {
            log.info("[Sim] 기동 시 자동 생성 꺼짐 — 시뮬레이터 [시뮬레이션 데이터 생성] 으로 만든다");
            return;
        }
        try {
            sim.seed();
        } catch (Exception e) {
            log.warn("[Sim] 기동 시 자동 생성 실패 — 시뮬레이터에서 다시 시도할 것 ({})", e.getMessage());
        }
    }
}
