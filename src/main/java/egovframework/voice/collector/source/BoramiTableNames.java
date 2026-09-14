package egovframework.voice.collector.source;

import egovframework.voice.collector.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 보라미 테이블의 <b>정규화된 이름</b>을 만든다 — 스키마가 있으면 {@code 스키마.테이블}, 없으면 그대로.
 *
 * <p><b>왜 필요한가</b>: 실제 보라미는 테이블명 접두어가 곧 스키마다
 * ({@code im.tb_imsc_ptpr_dt}, {@code re.tb_rerd_tfin_ds}). 반면 개발용 H2 Mock 은 평평하다.
 * SQL 을 두 벌 만들지 않으려고 테이블명만 밖에서 주입한다.</p>
 *
 * <p><b>⚠ 이 값은 MyBatis 의 {@code ${}} 로 들어간다</b> — 바인딩이 아니라 <b>문자열 치환</b>이라
 * SQL 에 그대로 박힌다. 설정 파일에서 오는 값이라 외부 입력은 아니지만, 오타 하나가 SQL 문법 오류가
 * 되고 최악의 경우 의도치 않은 구문이 될 수 있다. 그래서 <b>식별자 형식을 강제 검증</b>한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BoramiTableNames {

    /** SQL 식별자로 허용할 형식 — 영문으로 시작하고 영숫자·언더스코어만. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final VoiceProperties props;

    /** 특이수용자상세 — 실제 {@code im.TB_IMSC_PTPR_DT} */
    public String imscPtprDt() {
        return qualify(schema().imsc(), "TB_IMSC_PTPR_DT");
    }

    /** 녹취파일내역 — 실제 {@code re.TB_RERD_TFIN_DS} */
    public String rerdTfinDs() {
        return qualify(schema().rerd(), "TB_RERD_TFIN_DS");
    }

    /** 공통파일기본 — 실제 {@code sm.TB_SMSM_CMFI_BS} (2026-09-12 기준 borami-db 에 없음) */
    public String smsmCmfiBs() {
        return qualify(schema().smsm(), "TB_SMSM_CMFI_BS");
    }

    /** 사용자통화내역 — 특이수용자와 같은 {@code im} 스키마에 있다 */
    public String imphUcdrDs() {
        return qualify(schema().imsc(), "TB_IMPH_UCDR_DS");
    }

    /** XVARM 콘텐츠 메타 — 실제 스키마 미확인 (borami-db 에 없음) */
    public String asysContentElement() {
        return qualify(schema().xvarm(), "ASYSCONTENTELEMENT");
    }

    private VoiceProperties.Schema schema() {
        return props.source().schema();
    }

    /**
     * 스키마가 비어 있으면 테이블명만, 있으면 {@code 스키마.테이블} 로 만든다.
     *
     * @throws IllegalStateException 스키마명이 식별자 형식이 아닌 경우
     */
    private String qualify(String schema, String table) {
        if (!StringUtils.hasText(schema)) {
            return table;
        }
        String s = schema.trim();
        if (!IDENTIFIER.matcher(s).matches()) {
            throw new IllegalStateException(
                    "스키마명이 SQL 식별자 형식이 아니다: '%s' (영문으로 시작하는 영숫자·언더스코어만 허용)"
                            .formatted(schema));
        }
        return s + "." + table;
    }

    /** 현재 조립된 테이블명 전체(진단·기동 로그용). */
    public String describe() {
        return "특이수용자=%s, 녹취=%s, 공통파일=%s, 통화내역=%s, XVARM=%s".formatted(
                imscPtprDt(), rerdTfinDs(), smsmCmfiBs(), imphUcdrDs(), asysContentElement());
    }
}
