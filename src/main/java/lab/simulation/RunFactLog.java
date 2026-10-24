package lab.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次运行内的共享事实日志（包内可见）。
 *
 * <p>外部调用尝试与状态快照共用一个严格递增的序列，因此可以判断
 * "某个状态之后的下一次外部动作是什么"。
 */
final class RunFactLog {

    private int sequence;
    private final List<ExternalCallAttempt> attempts = new ArrayList<>();
    private final List<StateTransition> transitions = new ArrayList<>();

    /**
     * 取下一个全局序号，从 1 开始。
     */
    int nextSequence() {
        return ++sequence;
    }

    void recordAttempt(ExternalCallAttempt attempt) {
        attempts.add(attempt);
    }

    void recordTransition(StateTransition transition) {
        transitions.add(transition);
    }

    List<ExternalCallAttempt> attempts() {
        return List.copyOf(attempts);
    }

    List<StateTransition> transitions() {
        return List.copyOf(transitions);
    }
}
