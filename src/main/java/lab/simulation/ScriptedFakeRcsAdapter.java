package lab.simulation;

import lab.ExternalOrderResult;
import lab.RcsExecutionPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按脚本返回结果的 RcsExecutionPort 实现。
 *
 * <p>每次调用<b>先记录一次调用尝试</b>（{@link ExternalCallAttempt}，含 scripted=false），
 * 再按脚本执行。找不到脚本时记录 UNSCRIPTED_EXTERNAL_CALL 尝试后抛出稳定错误。
 *
 * <p>内部维护：
 * <ul>
 *   <li>CREATE 和 QUERY 各自的调用次数</li>
 *   <li>已创建的有效外单 applicationId → externalOrderId</li>
 *   <li>ExternalInteraction 只读时间线</li>
 * </ul>
 */
public class ScriptedFakeRcsAdapter implements RcsExecutionPort {

    private final VirtualClock clock;
    private final List<ExternalScript> scripts;
    private final RunFactLog facts;
    private final Map<ExternalScript.Operation, Integer> callCounts = new HashMap<>();
    private final Map<String, String> createdOrders = new HashMap<>();
    private final List<ExternalInteraction> interactions = new ArrayList<>();
    private int interactionSequence = 0;

    public ScriptedFakeRcsAdapter(VirtualClock clock, List<ExternalScript> scripts, RunFactLog facts) {
        this.clock = clock;
        this.scripts = List.copyOf(scripts);
        this.facts = facts;
        for (ExternalScript.Operation op : ExternalScript.Operation.values()) {
            callCounts.put(op, 0);
        }
    }

    /**
     * 按 CREATE 调用序查找脚本并返回结果。
     *
     * <p>副作用（按顺序）：
     * <ol>
     *   <li>记录一次调用尝试（未脚本化时 scripted=false）</li>
     *   <li>ACCEPTED 和 TIMEOUT_BUT_CREATED 把 externalOrderId 记录到 createdOrders</li>
     *   <li>追加一条 ExternalInteraction</li>
     * </ol>
     *
     * @param applicationId 申请编号
     * @param fromPointId   起点编号（本方法不使用，仅保留接口签名）
     * @param toPointId     终点编号（本方法不使用，仅保留接口签名）
     * @return 规范化结果：ACCEPTED / REJECTED / TIMEOUT
     * @throws ScenarioValidationException 找不到脚本时（code=UNSCRIPTED_EXTERNAL_CALL）
     */
    @Override
    public synchronized ExternalOrderResult createOrder(String applicationId,
                                                       String fromPointId,
                                                       String toPointId) {
        int ordinal = nextOrdinal(ExternalScript.Operation.CREATE);
        ExternalScript script = findScript(ExternalScript.Operation.CREATE, ordinal);
        if (script == null) {
            recordAttempt(ExternalScript.Operation.CREATE, ordinal, applicationId,
                    false, "", ExternalCallAttempt.UNSCRIPTED, "-");
            throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL",
                    "CREATE ordinal=" + ordinal + " app=" + applicationId);
        }
        recordAttempt(ExternalScript.Operation.CREATE, ordinal, applicationId,
                true, script.scriptId(), script.outcome(), script.externalOrderId());

        String outcome = script.outcome();
        String externalOrderId = script.externalOrderId();
        String certainty;

        switch (outcome) {
            case "ACCEPTED", "TIMEOUT_BUT_CREATED" -> {
                createdOrders.put(applicationId, externalOrderId);
                certainty = ExternalInteraction.CONFIRMED_CREATED;
            }
            case "TIMEOUT_NOT_CREATED" -> certainty = ExternalInteraction.CONFIRMED_NOT_CREATED;
            case "REJECTED" -> certainty = ExternalInteraction.REJECTED_NO_EFFECT;
            default -> throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL", "unexpected outcome=" + outcome);
        }

        recordInteraction(ExternalScript.Operation.CREATE, ordinal, applicationId,
                outcome, certainty, externalOrderId, script.scriptId());

        return switch (outcome) {
            case "ACCEPTED" -> ExternalOrderResult.accepted(externalOrderId);
            case "REJECTED" -> ExternalOrderResult.rejected();
            case "TIMEOUT_BUT_CREATED", "TIMEOUT_NOT_CREATED" -> ExternalOrderResult.timeout();
            default -> throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL", "unexpected outcome=" + outcome);
        };
    }

    /**
     * 按 QUERY 调用序查找脚本并返回结果。
     *
     * <p>副作用（按顺序）：
     * <ol>
     *   <li>记录一次调用尝试（未脚本化时 scripted=false）</li>
     *   <li>追加一条 ExternalInteraction</li>
     * </ol>
     *
     * <p>若脚本 outcome 为 ACCEPTED，会校验 createdOrders 中是否已有
     * 同一个 applicationId → externalOrderId 的记录；不匹配时抛异常。
     *
     * @param applicationId 申请编号
     * @return 规范化结果：ACCEPTED / NOT_FOUND
     * @throws ScenarioValidationException 找不到脚本、外单不匹配或 outcome 不合法时
     */
    @Override
    public synchronized ExternalOrderResult queryOrder(String applicationId) {
        int ordinal = nextOrdinal(ExternalScript.Operation.QUERY);
        ExternalScript script = findScript(ExternalScript.Operation.QUERY, ordinal);
        if (script == null) {
            recordAttempt(ExternalScript.Operation.QUERY, ordinal, applicationId,
                    false, "", ExternalCallAttempt.UNSCRIPTED, "-");
            throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL",
                    "QUERY ordinal=" + ordinal + " app=" + applicationId);
        }
        recordAttempt(ExternalScript.Operation.QUERY, ordinal, applicationId,
                true, script.scriptId(), script.outcome(), script.externalOrderId());

        String outcome = script.outcome();
        String externalOrderId = script.externalOrderId();
        String certainty;

        switch (outcome) {
            case "ACCEPTED" -> {
                String stored = createdOrders.get(applicationId);
                if (stored == null || !stored.equals(externalOrderId)) {
                    throw new ScenarioValidationException(
                            "UNSCRIPTED_EXTERNAL_CALL",
                            "QUERY ACCEPTED but no matching created order for " + applicationId);
                }
                certainty = ExternalInteraction.OBSERVED_EXISTING;
            }
            case "NOT_FOUND" -> certainty = ExternalInteraction.OBSERVED_NOT_FOUND;
            default -> throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL", "unexpected outcome=" + outcome);
        }

        recordInteraction(ExternalScript.Operation.QUERY, ordinal, applicationId,
                outcome, certainty, externalOrderId, script.scriptId());

        return switch (outcome) {
            case "ACCEPTED" -> ExternalOrderResult.accepted(externalOrderId);
            case "NOT_FOUND" -> ExternalOrderResult.notFound();
            default -> throw new ScenarioValidationException(
                    "UNSCRIPTED_EXTERNAL_CALL", "unexpected outcome=" + outcome);
        };
    }

    /**
     * 返回外部交互的只读快照。
     * 快照之后新增的交互不会影响此列表。
     */
    public synchronized List<ExternalInteraction> interactions() {
        return List.copyOf(interactions);
    }

    /**
     * 返回指定 operation 已调用次数。
     */
    public synchronized int callCount(ExternalScript.Operation op) {
        return callCounts.getOrDefault(op, 0);
    }

    /**
     * 返回当前有效外单数量。
     * 一个 applicationId 对应一个有效外单。
     */
    public synchronized int validExternalOrderCount() {
        return createdOrders.size();
    }

    /**
     * 返回指定申请的有效外单号；没有则返回 null。
     */
    public synchronized String externalOrderIdFor(String applicationId) {
        return createdOrders.get(applicationId);
    }

    private int nextOrdinal(ExternalScript.Operation op) {
        int count = callCounts.get(op) + 1;
        callCounts.put(op, count);
        return count;
    }

    private ExternalScript findScript(ExternalScript.Operation op, int ordinal) {
        for (ExternalScript s : scripts) {
            if (s.operation() == op && s.ordinal() == ordinal) {
                return s;
            }
        }
        return null;
    }

    private void recordAttempt(ExternalScript.Operation op, int ordinal, String applicationId,
                               boolean scripted, String scriptId, String outcome,
                               String externalOrderId) {
        facts.recordAttempt(new ExternalCallAttempt(
                facts.nextSequence(), clock.now(), op, ordinal, applicationId,
                scripted, scriptId, outcome, externalOrderId));
    }

    private void recordInteraction(ExternalScript.Operation op, int ordinal,
                                   String applicationId, String outcome,
                                   String certainty, String externalOrderId,
                                   String scriptId) {
        interactionSequence++;
        interactions.add(new ExternalInteraction(
                interactionSequence,
                clock.now(),
                op,
                ordinal,
                applicationId,
                outcome,
                certainty,
                externalOrderId,
                scriptId));
    }
}
