package lab;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 申请状态机。
 *
 * <p>允许的转换固定在本枚举的 {@code ALLOWED} 表中：
 * CREATED → DISPATCHED / CANCELLED / UNKNOWN；DISPATCHED → EXECUTING / CANCELLED；
 * EXECUTING → COMPLETED / CANCELLED；UNKNOWN → DISPATCHED / CANCELLED；
 * COMPLETED 与 CANCELLED 为终态，不允许任何转换。
 */
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

    /** 是否为终态（COMPLETED / CANCELLED）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** 当前状态是否允许转换到目标状态。 */
    public boolean canTransitionTo(ApplicationState target) {
        Set<ApplicationState> allowed = ALLOWED.get(this);
        return allowed != null && allowed.contains(target);
    }
}
