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
                        보라미의 수용자 음성(접견·통화)을 골라 가져와 **STT 텍스트로 만들어 \
                        비식별 커넥터에 넘기는** 서비스입니다.

                        ### 이 서비스의 범위
                        `대상 선별 → 파일 확보 → 복호화 → STT → POST /deid/connect` 까지입니다.
                        비식별·PPP 전송은 **agent-connector**, 로그 적재(T1·T2·T4)는 **log-collector** 가 맡습니다
                        (R&R 확정 2026-08-19).

                        ### 시연 순서
                        1. `GET /api/v1/voice/status` — 4개 스위치(source·broker·decrypt·stt)가 어느 모드인지 확인
                        2. `GET /api/v1/mock/targets` — 이번에 처리될 대상 미리보기
                        3. `POST /api/v1/voice/batches/daily` — 배치 실행
                        4. 같은 배치를 한 번 더 실행 — `skippedCnt` 로 멱등 동작 확인
                        5. `POST /api/v1/mock/reset` — 초기화 후 반복

                        ### 확인 포인트
                        커넥터에 붙인 상태(`voice.sink.enabled=true`)라면, Mock STT 텍스트의 \
                        성명·주민번호·전화번호가 **마스킹되어** 돌아와야 합니다. \
                        그대로 나오면 `sttScriptText` 필드명이 어긋난 것입니다.

                        > 시뮬레이터 화면: [/simulator.html](/simulator.html)
                        """)
                .license(new License().name("내부 프로젝트 (KCAIS)")));
    }
}
