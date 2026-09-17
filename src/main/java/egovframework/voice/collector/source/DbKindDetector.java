package egovframework.voice.collector.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 지금 붙어 있는 DB 가 무엇인지 — H2(로컬 Mock 보라미) / PostgreSQL(개발계 borami-db) / Oracle(실 보라미).
 *
 * <p>같은 코드가 세 DB 를 상대한다. 스키마를 붙일지(H2 는 평평), 누락 테이블을 만들어도 되는지
 * (개발계 PostgreSQL 만), 시딩 SQL 을 돌려도 되는지가 전부 여기에 달려 있어 한곳에서 판정한다.
 * URL 은 기동 후 바뀌지 않으므로 한 번만 읽고 캐시한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class DbKindDetector {

    public enum DbKind { H2, POSTGRESQL, ORACLE, UNKNOWN }

    private final DataSource dataSource;

    private volatile DbKind cached;
    private volatile String url;

    /** 테스트용 — DB 를 열지 않고 종류를 고정한다. */
    public static DbKindDetector fixed(DbKind kind) {
        DbKindDetector d = new DbKindDetector(null);
        d.cached = kind;
        d.url = "";
        return d;
    }

    public DbKind kind() {
        DbKind k = cached;
        if (k == null) {
            k = detect();
            cached = k;
        }
        return k;
    }

    /** JDBC URL(계정 없음) — 화면 표기용. */
    public String url() {
        kind();
        return url == null ? "" : url;
    }

    public boolean isH2() {
        return kind() == DbKind.H2;
    }

    public boolean isPostgres() {
        return kind() == DbKind.POSTGRESQL;
    }

    /** 화면에 보여 줄 한 줄 — "H2 (로컬 Mock 보라미)" / "PostgreSQL borami-db (개발계)". */
    public String label() {
        return switch (kind()) {
            case H2 -> "H2 (로컬 Mock 보라미)";
            case POSTGRESQL -> "PostgreSQL (개발계 borami-db)";
            case ORACLE -> "Oracle (실 보라미)";
            default -> "알 수 없는 DB";
        };
    }

    private DbKind detect() {
        try (Connection c = dataSource.getConnection()) {
            String u = c.getMetaData().getURL();
            url = u == null ? "" : u.replaceAll("(?i)(password|user)=[^;&]*", "$1=***");
            String lower = url.toLowerCase();
            if (lower.startsWith("jdbc:h2:")) return DbKind.H2;
            if (lower.startsWith("jdbc:postgresql:")) return DbKind.POSTGRESQL;
            if (lower.startsWith("jdbc:oracle:")) return DbKind.ORACLE;
            return DbKind.UNKNOWN;
        } catch (Exception e) {
            log.warn("[DB] 데이터소스 확인 실패 — {}", e.getMessage());
            return DbKind.UNKNOWN;
        }
    }
}
