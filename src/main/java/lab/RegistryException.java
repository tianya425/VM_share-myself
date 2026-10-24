package lab;

/**
 * 业务错误：稳定 code + 可选 detail，CLI 输出形如 {@code ERR <code> <detail>}。
 */
public class RegistryException extends RuntimeException {

    private final String code;
    private final String detail;

    public RegistryException(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : " " + detail));
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    /** 稳定错误码。 */
    public String code() {
        return code;
    }

    /** 错误细节（可能为空串）。 */
    public String detail() {
        return detail;
    }
}

