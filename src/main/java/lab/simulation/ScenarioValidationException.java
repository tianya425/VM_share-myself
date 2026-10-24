package lab.simulation;

/**
 * 场景文件解析或校验失败时抛出的异常。
 *
 * <p>code 表示稳定错误码，detail 表示可选的具体位置或参数。
 * 例如：
 * <ul>
 *   <li>SCENARIO_FILE_NOT_FOUND</li>
 *   <li>SCENARIO_FILE_TOO_LARGE</li>
 *   <li>SCENARIO_ENCODING</li>
 *   <li>SCENARIO_HEADER_MISSING</li>
 *   <li>SCENARIO_END_MISSING</li>
 *   <li>SCENARIO_EVENT_ORDER</li>
 *   <li>SCENARIO_DUPLICATE_EVENT</li>
 *   <li>SCENARIO_SCRIPT_ORDINAL</li>
 *   <li>SCENARIO_UNKNOWN_ACTION</li>
 *   <li>SCENARIO_UNKNOWN_ORACLE</li>
 *   <li>SCENARIO_ARGUMENT_COUNT</li>
 * </ul>
 */
public class ScenarioValidationException extends RuntimeException {

    private final String code;
    private final String detail;

    public ScenarioValidationException(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : " " + detail));
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    /** 稳定错误码（如 SCENARIO_EVENT_ORDER）。 */
    public String code() {
        return code;
    }

    /** 错误细节（可能为空串）。 */
    public String detail() {
        return detail;
    }
}
