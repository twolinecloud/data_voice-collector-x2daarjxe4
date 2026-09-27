package egovframework.voice.collector.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 원천(보라미) DB 풀의 <b>동시 사용 연결 최고치</b> — 폴링 없이 잰다.
 *
 * <p>최고치는 늘 누군가 연결을 빌리는 순간에 생긴다. 그래서 빌릴 때마다 그 순간의 사용 중 연결 수를
 * 한 번 읽어 최대값만 남긴다({@link Metered#getConnection()}). 주기적으로 읽는 방식은 짧은 순간을
 * 놓치고, Hikari 의 메트릭 트래커는 Micrometer 가 이미 쓰고 있어(한 번만 설정 가능) 건드리지 않는다.</p>
 *
 * <p>이 서비스에서 원천 DB 는 배치의 <b>대상 조회</b>에서만 쓴다 — 건 처리(확보·복호화·STT·저장)는 DB 를
 * 쓰지 않는다. 그래서 동시성을 올려도 최고치는 1~2 가 정상이다. 로그 적재는 DB 가 아니라 로그 컬렉터
 * HTTP 호출이다.</p>
 */
@Component
public class SourcePoolPeak {

    private int peak;
    private String pool;

    /** 새로 잰다. */
    public synchronized void reset() {
        peak = 0;
        pool = null;
    }

    synchronized void observe(String poolName, int active) {
        if (active > peak) {
            peak = active;
            pool = poolName;
        }
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("peak", peak);
        m.put("pool", pool);
        return m;
    }

    /** 빌릴 때마다 사용 중 연결 수를 {@link SourcePoolPeak} 에 알리는 Hikari 풀. 나머지 동작은 그대로다. */
    static final class Metered extends HikariDataSource {

        private final SourcePoolPeak peak;

        Metered(SourcePoolPeak peak) {
            this.peak = peak;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = super.getConnection();
            HikariPoolMXBean p = getHikariPoolMXBean();
            if (p != null) {
                peak.observe(getPoolName(), p.getActiveConnections());
            }
            return c;
        }
    }
}
