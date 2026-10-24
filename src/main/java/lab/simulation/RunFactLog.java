package lab.simulation;

import lab.simulation.resource.ReservationDecision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行内的共享事实日志（包内可见）。
 *
 * <p>外部调用尝试与状态快照共用一个严格递增的序列，因此可以判断
 * "某个状态之后的下一次外部动作是什么"。
 *
 * <p>资源运行观察单独保存：每个资源 Action 一条 {@link ResourceStep}，
 * 每次 {@code RESERVATION_REQUEST} 的 {@link ReservationDecision} 按 eventId 存档，
 * 每次 {@code WORLD_RESTART} 的前后摘要保存为 {@link RestartPair}。
 */
final class RunFactLog {

    private int sequence;
    private final List<ExternalCallAttempt> attempts = new ArrayList<>();
    private final List<StateTransition> transitions = new ArrayList<>();
    private final List<ResourceStep> resourceSteps = new ArrayList<>();
    private final List<RestartPair> restartPairs = new ArrayList<>();
    private final Map<String, ReservationDecision> reservationDecisions = new LinkedHashMap<>();

    int nextSequence() {
        return ++sequence;
    }

    void recordAttempt(ExternalCallAttempt attempt) {
        attempts.add(attempt);
    }

    void recordTransition(StateTransition transition) {
        transitions.add(transition);
    }

    void recordResourceStep(ResourceStep step) {
        resourceSteps.add(step);
    }

    void recordRestartPair(RestartPair pair) {
        restartPairs.add(pair);
    }

    void recordReservationDecision(String eventId, ReservationDecision decision) {
        reservationDecisions.put(eventId, decision);
    }

    List<ExternalCallAttempt> attempts() {
        return List.copyOf(attempts);
    }

    List<StateTransition> transitions() {
        return List.copyOf(transitions);
    }

    List<ResourceStep> resourceSteps() {
        return List.copyOf(resourceSteps);
    }

    List<RestartPair> restartPairs() {
        return List.copyOf(restartPairs);
    }

    Map<String, ReservationDecision> reservationDecisions() {
        return Map.copyOf(reservationDecisions);
    }
}
