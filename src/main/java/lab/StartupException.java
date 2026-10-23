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

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
