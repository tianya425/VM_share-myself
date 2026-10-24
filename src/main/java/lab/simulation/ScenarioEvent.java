package lab.simulation;

import java.util.List;

/**
 * 场景中的一条输入事件。
 *
 * <p>字段：
 * <ul>
 *   <li>offsetMillis    — 相对 startAt 的毫秒偏移，必须非负</li>
 *   <li>logicalSequence — 同一时间点的稳定顺序，必须为正数</li>
 *   <li>eventId         — 场景内唯一事件编号</li>
 *   <li>action          — 允许的动作名（见 Action 枚举）</li>
 *   <li>arguments       — 动作参数的不可修改列表</li>
 * </ul>
 */
public record ScenarioEvent(
        long offsetMillis,
        long logicalSequence,
        String eventId,
        Action action,
        List<String> arguments) {

    public ScenarioEvent {
        arguments = List.copyOf(arguments);
    }

    public enum Action {
        POINT_ADD,
        APPLICATION_ADD,
        APPLICATION_DISPATCH,
        APPLICATION_QUERY,
        APPLICATION_CALLBACK,
        OUTBOX_PROCESS,
        WORKFLOW_START,
        WORKFLOW_ADVANCE,

        // Part B / PR4：资源领域动作（运行时分发在 PR5/PR6 接线）
        DESTINATION_ADD,
        SERVICE_POINT_ADD,
        VIRTUAL_RESOURCE_ADD,
        RESOURCE_SET,
        RESERVATION_REQUEST,
        RESERVATION_RELEASE,
        RESERVATION_CANCEL,
        WORLD_RESTART
    }
}
