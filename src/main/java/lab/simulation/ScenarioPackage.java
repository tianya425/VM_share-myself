package lab.simulation;

import java.time.Instant;
import java.util.List;

/**
 * 一个场景的不可变描述。
 *
 * <p>字段：
 * <ul>
 *   <li>scenarioId      — 场景稳定编号，例如 SIM-001</li>
 *   <li>version         — 场景版本，最小为 1</li>
 *   <li>startAt         — 虚拟开始时间</li>
 *   <li>rootSeed        — 后续确定性随机的根种子</li>
 *   <li>digest          — 场景文件原始字节的 SHA-256</li>
 *   <li>externalScripts — 外部调用的顺序脚本</li>
 *   <li>events          — 业务输入时间轴</li>
 *   <li>oracles         — 最终结果与不变量规则</li>
 * </ul>
 */
public record ScenarioPackage(
        String scenarioId,
        int version,
        Instant startAt,
        long rootSeed,
        String digest,
        List<ExternalScript> externalScripts,
        List<ScenarioEvent> events,
        List<OracleRule> oracles) {

    public ScenarioPackage {
        externalScripts = List.copyOf(externalScripts);
        events = List.copyOf(events);
        oracles = List.copyOf(oracles);
    }
}
