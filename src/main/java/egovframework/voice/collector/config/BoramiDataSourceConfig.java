package egovframework.voice.collector.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 보라미 DataSource 두 개 + 라우터.
 *
 * <ul>
 *   <li>{@code h2DataSource} — 로컬 H2 Mock 보라미. <b>여기에만</b> Mock 스키마·필터 검증 행을 깐다.
 *       Spring 의 {@code spring.sql.init} 은 쓰지 않는다 — 그것은 기본(라우터) DataSource 에 걸려
 *       모드가 {@code 개발계 DB} 면 DROP TABLE 이 실DB 로 갈 수 있다.</li>
 *   <li>{@code directDataSource} — 개발계 borami-db(PostgreSQL) / 운영 보라미(Oracle).
 *       기동 시 붙어 보지 않고({@code initializationFailTimeout=-1}) 첫 사용 때 붙는다.</li>
 *   <li>{@code dataSource}(@Primary) — {@link BoramiDbRouter}. MyBatis·JdbcTemplate·트랜잭션이 이것을 쓴다.</li>
 * </ul>
 */
@Log4j2
@Configuration
public class BoramiDataSourceConfig {

    @Bean(name = "h2DataSource")
    public DataSource h2DataSource(VoiceProperties props) {
        VoiceProperties.LocalH2 h2 = props.source().localH2();
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("borami-h2");
        ds.setJdbcUrl(h2.url());
        ds.setUsername(h2.username());
        ds.setPassword(h2.password());
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(1);
        // Mock 스키마(DROP/CREATE) + 필터 검증용 행. 유효 대상 10건은 SimulationSeedRunner 가 넣는다.
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema-borami-mock.sql"), new ClassPathResource("data-borami-mock.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(ds);
        log.info("[DB] H2 Mock 보라미 준비 — {}", h2.url());
        return ds;
    }

    @Bean(name = "directDataSource")
    public DataSource directDataSource(VoiceProperties props) {
        VoiceProperties.DirectDb d = props.source().directDb();
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("borami-direct");
        ds.setJdbcUrl(d.url());
        if (d.driverClassName() != null && !d.driverClassName().isBlank()) {
            ds.setDriverClassName(d.driverClassName());
        }
        ds.setUsername(d.username());
        ds.setPassword(d.password());
        ds.setMaximumPoolSize(Math.max(1, d.maxPoolSize()));
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(Math.max(250, d.connectTimeoutMs()));
        ds.setInitializationFailTimeout(-1);   // 기동 시 붙어 보지 않는다 — 포트포워딩 없는 로컬에서도 떠야 한다
        log.info("[DB] 개발계 DB(DIRECT_JDBC) 대상 — {} (user={})", d.url(), d.username());
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(VoiceModeState modeState,
                                 @org.springframework.beans.factory.annotation.Qualifier("h2DataSource") DataSource h2,
                                 @org.springframework.beans.factory.annotation.Qualifier("directDataSource") DataSource direct) {
        return new BoramiDbRouter(modeState, h2, direct);
    }
}
