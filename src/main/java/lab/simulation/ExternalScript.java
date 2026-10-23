package lab.simulation;

/**
 * 一条外部调用的预定脚本。
 *
 * <p>描述"第 N 次 CREATE（或 QUERY）应该返回什么结果"。
 *
 * <p>字段：
 * <ul>
 *   <li>scriptId        — 场景内唯一脚本编号</li>
 *   <li>operation       — CREATE 或 QUERY</li>
 *   <li>ordinal         — 该 operation 的第几次调用，从 1 连续递增</li>
 *   <li>outcome         — 规定返回结果（字符串，由 ScriptedFakeRcsAdapter 解释）</li>
 *   <li>externalOrderId — 已创建或已确认外单的编号；未使用时为 "-"</li>
 * </ul>
 */
public record ExternalScript(
        String scriptId,
        Operation operation,
        int ordinal,
        String outcome,
        String externalOrderId) {

    public enum Operation {
        CREATE,
        QUERY
    }
}
