package lab.simulation;

import java.util.List;

/**
 * 一次仿真运行的结果。
 *
 * <p>字段：
 * <ul>
 *   <li>runStatus       — COMPLETED / FAILED / INCONCLUSIVE</li>
 *   <li>scenarioId      — 场景编号</li>
 *   <li>version         — 场景版本</li>
 *   <li>eventCount      — 事件总数</li>
 *   <li>eventPassCount  — 成功事件数</li>
 *   <li>eventFailCount  — 失败事件数</li>
 *   <li>createCallCount — CREATE 调用总数</li>
 *   <li>queryCallCount  — QUERY 调用总数</li>
 *   <li>validExternalOrderCount — 有效外单数量</li>
 *   <li>interactions    — 外部交互时间线快照</li>
 *   <li>oracleResults   — Oracle 检查结果</li>
 * </ul>
 */
public record SimulationRunResult(
        String runStatus,
        String scenarioId,
        int version,
        int eventCount,
        int eventPassCount,
        int eventFailCount,
        int createCallCount,
        int queryCallCount,
        int validExternalOrderCount,
        List<ExternalInteraction> interactions,
        List<OracleResult> oracleResults) {

    public SimulationRunResult {
        interactions = List.copyOf(interactions);
        oracleResults = List.copyOf(oracleResults);
    }

    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    /**
     * Oracle 结果中 PASS 的数量。
     */
    public int oraclePassCount() {
        int n = 0;
        for (OracleResult r : oracleResults) {
            if (r.status() == OracleResult.Status.PASS) {
                n++;
            }
        }
        return n;
    }
    /**
     * Oracle 结果中 FAIL 的数量。
     */
    public int oracleFailCount() {
        int n = 0;
        for (OracleResult r : oracleResults) {
            if (r.status() == OracleResult.Status.FAIL) {
                n++;
            }
        }
        return n;
    }
}
