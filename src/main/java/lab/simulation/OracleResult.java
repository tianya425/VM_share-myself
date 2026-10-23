package lab.simulation;

/**
 * 一条 Oracle 检查结果。
 *
 * <p>字段：
 * <ul>
 *   <li>oracleId — 规则编号（系统不变量用 ORC-SYS-*，场景 EXPECT 用自定义编号）</li>
 *   <li>ruleType — 规则类型或系统不变量编号</li>
 *   <li>status   — PASS / FAIL</li>
 *   <li>expected — 期望值</li>
 *   <li>actual   — 实际值</li>
 *   <li>detail   — 可选说明</li>
 * </ul>
 */
public record OracleResult(
        String oracleId,
        String ruleType,
        Status status,
        String expected,
        String actual,
        String detail) {

    public enum Status {
        PASS,
        FAIL
    }
    /**
    * 构造一条 PASS 结果。
    */
    public static OracleResult pass(String oracleId, String ruleType,
                                    String expected, String actual) {
        return new OracleResult(oracleId, ruleType, Status.PASS, expected, actual, "");
    }
    /**
    * 构造一条 FAIL 结果。
    * detail 用于说明失败原因。
    */
    public static OracleResult fail(String oracleId, String ruleType,
                                    String expected, String actual, String detail) {
        return new OracleResult(oracleId, ruleType, Status.FAIL, expected, actual, detail);
    }
}
