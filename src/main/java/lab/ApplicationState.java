package lab;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum ApplicationState {

    CREATED,
    DISPATCHED,
    EXECUTING,
    COMPLETED,
    CANCELLED,
    UNKNOWN;

    private static final Map<ApplicationState, Set<ApplicationState>> ALLOWED = Map.of(
            CREATED,    EnumSet.of(DISPATCHED, CANCELLED, UNKNOWN),
            DISPATCHED, EnumSet.of(EXECUTING, CANCELLED),
            EXECUTING,  EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED,  EnumSet.noneOf(ApplicationState.class),
            CANCELLED,  EnumSet.noneOf(ApplicationState.class),
            UNKNOWN,    EnumSet.of(DISPATCHED, CANCELLED)
    );

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean canTransitionTo(ApplicationState target) {
        Set<ApplicationState> allowed = ALLOWED.get(this);
        return allowed != null && allowed.contains(target);
    }
}
