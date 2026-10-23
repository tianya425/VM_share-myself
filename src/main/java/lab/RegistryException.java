package lab;

public class RegistryException extends RuntimeException {

    private final String code;
    private final String detail;

    public RegistryException(String code, String detail) {
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

