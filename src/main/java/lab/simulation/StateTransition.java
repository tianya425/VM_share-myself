package lab.simulation;

import lab.ApplicationState;
import lab.OutboxStatus;

import java.time.Instant;

/**
 * 一个事件执行后的状态快照（申请状态 + 该申请的 Outbox 状态）。
 *
 * <p>一次运行内按 sequence 严格递增记录；sequence 与 {@link ExternalCallAttempt}
 * 共用同一个序列，因此"某个状态之后的下一次外部动作"可以直接按 sequence 比较。
 */
public record StateTransition(
        int sequence,
        Instant virtualOccurredAt,
        String eventId,
        String applicationId,
        ApplicationState applicationState,
        OutboxStatus outboxStatus) {
}
