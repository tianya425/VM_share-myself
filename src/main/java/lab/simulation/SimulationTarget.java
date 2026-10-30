package lab.simulation;

import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ResourceWorldSnapshot;

/**
 * 场景动作与业务入口之间的窄接口。
 *
 * <p>每个方法与 ScenarioEvent.Action 一一对应。
 * 实现类只负责把参数转成对既有 Registry 公开方法的调用。
 */
public interface SimulationTarget {

    /**
     * 登记一个点位。
     *
     * @param pointId 点位编号
     * @param name    点位显示名称
     */
    void pointAdd(String pointId, String name);

    /**
     * 登记一条搬运申请。
     *
     * @param applicationId 申请编号
     * @param fromPointId   起点编号
     * @param toPointId     终点编号
     */
    void applicationAdd(String applicationId, String fromPointId, String toPointId);

    /**
     * 下发一条搬运申请。
     * 对应场景动作 APPLICATION_DISPATCH。
     */
    void applicationDispatch(String applicationId);

    /**
     * 对 UNKNOWN 申请执行 query 对账。
     * 对应场景动作 APPLICATION_QUERY。
     */
    void applicationQuery(String applicationId);

    /**
     * 接收一次外部 callback。
     *
     * @param applicationId 申请编号
     * @param eventType     STARTED / COMPLETED / FAILED
     */
    void applicationCallback(String applicationId, String eventType);

    /**
     * 处理所有 PENDING Outbox 记录。
     * 对应场景动作 OUTBOX_PROCESS。
     */
    void outboxProcess();

    /**
     * 为一条申请启动 Workflow。
     */
    void workflowStart(String applicationId);

    /**
     * 推进一次 Workflow。
     */
    void workflowAdvance(String applicationId);

    // ---------- Part B / PR4：资源领域 ----------

    /**
     * 新增一个 Destination。
     */
    void destinationAdd(String destinationId, String name);

    /**
     * 新增一个 ServicePoint（引用已存在的 Destination 与 Registry Point；三字段合同）。
     */
    void servicePointAdd(String servicePointId, String destinationId, String pointId);

    /**
     * 新增一个 VirtualResource（容量 1，默认 ENABLED）。
     */
    void virtualResourceAdd(String resourceId, String servicePointId);

    /**
     * 设置资源可用性（ENABLED / DISABLED）。
     */
    void resourceSetAvailability(String resourceId, String availability);

    /**
     * 请求预约；requestedOffset 与 requestedLogicalSequence 来自场景事件。
     */
    ReservationDecision reservationRequest(String applicationId, String destinationId,
                                           String servicePointId, String resourceId,
                                           long requestedOffsetMillis, long requestedLogicalSequence);

    /**
     * 释放 HELD 预约。
     */
    Reservation reservationRelease(String applicationId);

    /**
     * 取消预约（HELD 或 WAITING）。
     */
    Reservation reservationCancel(String applicationId);

    /**
     * 资源世界规范快照。
     */
    ResourceWorldSnapshot resourceWorldSnapshot();
}
