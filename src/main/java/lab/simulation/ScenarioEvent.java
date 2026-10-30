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

    /**
     * 动作名。
     *
     * <p>STU-SIM-02-R1 §12.1：资源领域固定 8 个规范 Action（逐字、固定参数）：
     * <ul>
     *   <li>{@link #DESTINATION_ADD}     destinationId name</li>
     *   <li>{@link #SERVICE_POINT_ADD}   servicePointId destinationId pointId（三参数）</li>
     *   <li>{@link #RESOURCE_ADD}        resourceId servicePointId</li>
     *   <li>{@link #RESOURCE_SET}        resourceId ENABLED|DISABLED</li>
     *   <li>{@link #RESERVATION_REQUEST} applicationId destinationId servicePointId resourceId</li>
     *   <li>{@link #RESERVATION_RELEASE} applicationId</li>
     *   <li>{@link #RESERVATION_CANCEL}  applicationId</li>
     *   <li>{@link #WORLD_RESTART}       无参数</li>
     * </ul>
     *
     * <p>资源新增动作的规范名是 {@code RESOURCE_ADD}；旧写法已删除，Parser 不再接受。
     */
    public enum Action {
        POINT_ADD,
        APPLICATION_ADD,
        APPLICATION_DISPATCH,
        APPLICATION_QUERY,
        APPLICATION_CALLBACK,
        OUTBOX_PROCESS,
        WORKFLOW_START,
        WORKFLOW_ADVANCE,

        // STU-SIM-02-R1 §12.1：资源领域固定 8 个规范 Action
        DESTINATION_ADD,
        SERVICE_POINT_ADD,
        RESOURCE_ADD,
        RESOURCE_SET,
        RESERVATION_REQUEST,
        RESERVATION_RELEASE,
        RESERVATION_CANCEL,
        WORLD_RESTART
    }
}
