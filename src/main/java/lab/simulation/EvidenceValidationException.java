package lab.simulation;

/**
 * 证据包校验失败时抛出的异常。
 *
 * <p>code 为稳定错误码，detail 为可选的定位信息（不含绝对路径）。
 */
public class EvidenceValidationException extends RuntimeException {

    private final String code;
    private final String detail;

    public EvidenceValidationException(String code, String detail) {
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
