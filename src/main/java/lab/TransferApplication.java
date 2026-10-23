package lab;

public record TransferApplication(
        String applicationId,
        String fromPointId,
        String toPointId,
        ApplicationState state,
        String externalOrderId) {

    public TransferApplication(String applicationId, String fromPointId, String toPointId) {
        this(applicationId, fromPointId, toPointId, ApplicationState.CREATED, null);
    }

    public TransferApplication withState(ApplicationState newState) {
        return new TransferApplication(applicationId, fromPointId, toPointId, newState, externalOrderId);
    }

    public TransferApplication withExternalOrderId(String newExternalOrderId) {
        return new TransferApplication(applicationId, fromPointId, toPointId, state, newExternalOrderId);
    }

    public boolean hasExternalOrder() {
        return externalOrderId != null;
    }
}
