package egovframework.voice.collector.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 메타.
 *
 * <p>시연에서 Swagger 가 곧 조작 콘솔이 된다. 그래서 설명에 <b>지금 어떤 모드로 돌고 있는지</b>와
 * <b>무엇을 확인해야 하는지</b>를 적어 둔다 — 화면만 보고도 다음 동작을 정할 수 있어야 한다.</p>
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.version:v1.0}")
    private String version;

    @Bean
    public OpenAPI voiceCollectorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("voice-collector — 비정형 음성 수집 서비스")
                .version(version)
                .description("""
                        보라미의 수용자 음성(접견·통화)을 골라 가져와 복호화하고 **STT 텍스트로 만드는** \
                        서비스입니다.

                        ### 이 서비스의 범위
                        `대상 선별 → 파일 확보 → 복호화 → STT → 처리 이력 적재(T1·T2·T4)` 까지입니다.
                        STT 텍스트를 외부 서비스로 전송하지 않습니다 — 비식별 커넥터 연동은 아키텍처 변경으로
                        제외됐고, 이 서비스의 역할은 STT 처리와 내부 저장·감시까지입니다.
                        로그 테이블 적재는 **log-collector** API 로만 합니다.

                        ### 시연 순서
                        1. `GET /api/v1/voice/status` — 5개 스위치(source·broker·phone·decrypt·stt)가 어느 모드인지 확인
                        2. `GET /api/v1/mock/targets` — 이번에 처리될 대상 미리보기
                        3. `POST /api/v1/voice/batches/daily` — 배치 실행
                        4. 같은 배치를 한 번 더 실행 — `skippedCnt` 로 멱등 동작 확인
                        5. `POST /api/v1/mock/reset` — 초기화 후 반복

                        ### 확인 포인트
                        배치 결과의 `residue` — 처리 후 수신·작업 폴더에 원본 음성이 **0건** 남아야 \
                        PII 즉시 삭제 정책이 지켜진 것입니다. 실패한 건도 예외가 아닙니다.

                        > 시뮬레이터 화면: [/voice_collector_simulator.html](/voice_collector_simulator.html)
                        """)
                .license(new License().name("내부 프로젝트 (KCAIS)")));
    }
}
