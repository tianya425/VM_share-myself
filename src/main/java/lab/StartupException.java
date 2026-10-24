package lab;

/**
 * 启动参数解析失败时抛出的异常。
 *
 * <p>code 表示错误类别，detail 表示具体参数或冲突名。
 */
public class StartupException extends RuntimeException {

    private final String code;
    private final String detail;

    public StartupException(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : " " + detail));
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    /** 稳定错误码（STARTUP_ARGUMENT / STARTUP_CONFLICT）。 */
    public String code() {
        return code;
    }

    /** 出错的具体参数或冲突名（可能为空串）。 */
    public String detail() {
        return detail;
    }
}
