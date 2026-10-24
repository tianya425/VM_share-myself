package lab;

/**
 * 搬运申请记录。
 *
 * @param applicationId   申请编号（{@code [A-Z][A-Z0-9_-]{0,31}}）
 * @param fromPointId     起点编号
 * @param toPointId       终点编号
 * @param state           当前状态（见 {@link ApplicationState}）
 * @param externalOrderId 外部订单号（未下发时为 null）
 */
public record TransferApplication(
        String applicationId,
        String fromPointId,
        String toPointId,
        ApplicationState state,
        String externalOrderId) {

    public TransferApplication(String applicationId, String fromPointId, String toPointId) {
        this(applicationId, fromPointId, toPointId, ApplicationState.CREATED, null);
    }

    /** 返回仅改状态的副本。 */
    public TransferApplication withState(ApplicationState newState) {
        return new TransferApplication(applicationId, fromPointId, toPointId, newState, externalOrderId);
    }

    /** 返回仅改外部订单号的副本。 */
    public TransferApplication withExternalOrderId(String newExternalOrderId) {
        return new TransferApplication(applicationId, fromPointId, toPointId, state, newExternalOrderId);
    }

    /** 是否已有外部订单号。 */
    public boolean hasExternalOrder() {
        return externalOrderId != null;
    }
}
