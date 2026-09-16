package egovframework.voice.collector.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 Mock 보라미를 <b>지금 시각 기준으로 다시 적재</b>한다 — 시뮬레이터 [Mock 데이터 초기화/생성] 이 부른다.
 *
 * <p><b>왜 다시 적재하는가</b>: {@code data-borami-mock.sql} 의 시각은 적재 시점 기준이다
 * (주기배치용 행 = 5분 전, 일배치용 행 = 어제 09시대). 기동 후 20분이 지나면 주기배치 창
 * [지금-20분, 지금) 에 걸리는 행이 없어지고, 날짜가 바뀌면 일배치 창도 어긋난다.
 * 초기화 버튼을 누를 때 다시 깔아야 시연이 언제 시작돼도 "접견 1·전화 1 / 접견 5·전화 5" 가 나온다.</p>
 *
 * <p><b>실DB 보호</b>: 데이터소스 URL 이 {@code jdbc:h2:} 로 시작할 때만 동작한다. realdb 프로파일로
 * 실제 borami-db 를 보는 상태에서는 아무것도 지우거나 넣지 않는다 — 원장 DB 직접 접근은 금지되어 있다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockBoramiSeeder {

    /** 자식 → 부모 순서. FK 는 없지만 읽는 쪽이 헷갈리지 않게 조인 역순으로 지운다. */
    private static final List<String> TABLES = List.of(
            "TB_RERD_TFIN_DS", "TB_IMPH_UCDR_DS", "ASYSCONTENTELEMENT", "TB_SMSM_CMFI_BS", "TB_IMSC_PTPR_DT");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    /** 지금 붙어 있는 DB 가 H2 Mock 인가. */
    public boolean isH2() {
        try (Connection c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            return url != null && url.startsWith("jdbc:h2:");
        } catch (Exception e) {
            log.warn("[MockSeed] 데이터소스 확인 실패 — 재적재하지 않는다 ({})", e.getMessage());
            return false;
        }
    }

    /**
     * 다시 적재한다. H2 가 아니면 건너뛰고 {@code reseeded=false} 로 알린다.
     *
     * @return 결과 요약 — 테이블별 행 수(재적재 후)
     */
    @Transactional
    public Map<String, Object> reseed() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isH2()) {
            out.put("reseeded", false);
            out.put("reason", "H2 Mock 이 아니라 건너뜀 — 실DB 는 건드리지 않는다");
            return out;
        }
        for (String t : TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("data-borami-mock.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);

        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String t : TABLES) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class);
            rows.put(t, n == null ? 0 : n);
        }
        out.put("reseeded", true);
        out.put("rows", rows);
        out.put("note", "일배치용 접견 5·전화 5 (어제 09시대) · 주기배치용 접견 1·전화 1 (5분 전) — 지금 시각 기준으로 다시 적재");
        log.info("[MockSeed] H2 Mock 보라미 재적재 — {}", rows);
        return out;
    }
}
