package lab.simulation;

/**
 * 一次回放比较的结果。
 *
 * @param status     REPLAY_MATCH / REPLAY_DIFFERENT / REPLAY_INVALID_EVIDENCE
 * @param scenarioId 匹配时的场景编号
 * @param version    匹配时的场景版本
 * @param detail     不同时的首个文件，或非法证据时的 A:/B: 错误码
 */
public record ReplayResult(String status, String scenarioId, int version, String detail) {

    public static final String MATCH = "REPLAY_MATCH";
    public static final String DIFFERENT = "REPLAY_DIFFERENT";
    public static final String INVALID = "REPLAY_INVALID_EVIDENCE";

    public boolean isMatch() {
        return MATCH.equals(status);
    }

    /**
     * CLI 退出码：匹配 0，内容不同 1，证据非法 2。
     */
    public int exitCode() {
        return exitCode(status);
    }

    /**
     * 状态对应的 CLI 退出码。
     */
    public static int exitCode(String status) {
        if (MATCH.equals(status)) {
            return 0;
        }
        if (DIFFERENT.equals(status)) {
            return 1;
        }
        return 2;
    }

    /**
     * CLI 单行输出。
     */
    public String toLine() {
        if (MATCH.equals(status)) {
            return MATCH + " " + scenarioId + " version=" + version;
        }
        return status + (detail.isEmpty() ? "" : " " + detail);
    }
}
