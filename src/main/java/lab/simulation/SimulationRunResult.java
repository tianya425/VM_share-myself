package lab.simulation;

import java.util.List;

/**
 * 一次仿真运行的结果（schemaVersion=2）。
 *
 * <p>字段：
 * <ul>
 *   <li>schemaVersion        — 结果结构版本，固定为 2</li>
 *   <li>runStatus            — COMPLETED / FAILED / INCONCLUSIVE</li>
 *   <li>scenarioId / version — 场景编号与版本</li>
 *   <li>plannedCount         — 计划执行的事件总数</li>
 *   <li>executedCount        — 实际执行的事件数（pass + fail）</li>
 *   <li>passCount / failCount— 成功/失败事件数</li>
 *   <li>skippedCount         — 首错后跳过的事件数</li>
 *   <li>firstFailureEventId  — 首个失败事件的编号；非事件型失败时为 null</li>
 *   <li>firstFailureCode     — 首个失败的稳定错误码</li>
 *   <li>evidenceLevel        — 固定 LOCAL_SIMULATION</li>
 *   <li>realExternalWriteCount — 真实外部写入次数，固定 0</li>
 *   <li>createCallCount / queryCallCount / validExternalOrderCount — 外部调用计数</li>
 *   <li>timeline / interactions / oracleResults — 不可变快照</li>
 * </ul>
 */
public record SimulationRunResult(
        int schemaVersion,
        String runStatus,
        String scenarioId,
        int version,
        int plannedCount,
        int executedCount,
        int passCount,
        int failCount,
        int skippedCount,
        String firstFailureEventId,
        String firstFailureCode,
        String evidenceLevel,
        int realExternalWriteCount,
        int createCallCount,
        int queryCallCount,
        int validExternalOrderCount,
        List<SimulationRuntime.EventRecord> timeline,
        List<ExternalInteraction> interactions,
        List<OracleResult> oracleResults) {

    public static final int SCHEMA_VERSION = 2;
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    public static final String EVIDENCE_LEVEL = "LOCAL_SIMULATION";

    public SimulationRunResult {
        timeline = List.copyOf(timeline);
        interactions = List.copyOf(interactions);
        oracleResults = List.copyOf(oracleResults);
    }

    /**
     * 兼容 Evidence 写入器的旧访问器：事件总数 == plannedCount。
     */
    public int eventCount() {
        return plannedCount;
    }

    /**
     * 兼容 Evidence 写入器的旧访问器：成功事件数 == passCount。
     */
    public int eventPassCount() {
        return passCount;
    }

    /**
     * 兼容 Evidence 写入器的旧访问器：失败事件数 == failCount。
     */
    public int eventFailCount() {
        return failCount;
    }

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
