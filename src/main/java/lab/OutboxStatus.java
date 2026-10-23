package lab;

/**
 * Outbox 记录的状态。
 *
 * <p>用于表达"外部执行意图"从建立到有结论的整个过程。
 *
 * <p>状态语义：
 * <ul>
 *   <li>PENDING   — 尚未获得"外部可能已执行"的证据，可以调用 create</li>
 *   <li>UNCERTAIN — create 已调用但结果不确定；只允许 query，不得直接 create</li>
 *   <li>SENT      — 已确认存在有效外单，不再 create</li>
 *   <li>FAILED    — 已获得明确拒绝且无有效外单；本轮不自动重发</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING,
    UNCERTAIN,
    SENT,
    FAILED
}
