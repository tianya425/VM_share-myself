package lab.simulation;

import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ResourceWorldSnapshot;

/**
 * 一次资源动作执行后的观察记录（只读事实）。
 *
 * <p>每个资源 Action 成功执行后记录一条：动作前后的规范快照、动作类型、
 * 目标资源与（RESOURCE_SET 的）取值；{@code RESERVATION_REQUEST} 另外带上
 * 本次 {@link ReservationDecision}，按 eventId 与运行观察一一对应。
 *
 * @param eventId   事件编号
 * @param action    资源动作
 * @param resourceId 目标资源编号；不适用时为空串
 * @param value     RESOURCE_SET 的 ENABLED/DISABLED；不适用时为空串
 * @param before    执行前快照
 * @param after     执行后快照
 * @param decision  仅 RESERVATION_REQUEST 非 null
 */
public record ResourceStep(
        String eventId,
        ScenarioEvent.Action action,
        String resourceId,
        String value,
        ResourceWorldSnapshot before,
        ResourceWorldSnapshot after,
        ReservationDecision decision) {

    public ResourceStep {
        if (before == null || after == null) {
            throw new IllegalArgumentException("ResourceStep 的 before/after 不能为 null");
        }
        resourceId = resourceId == null ? "" : resourceId;
        value = value == null ? "" : value;
    }
}
