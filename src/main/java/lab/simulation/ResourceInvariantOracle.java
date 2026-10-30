package lab.simulation;

import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceWorldSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源系统不变量 ORC-RES-SYS-001..006（只读、始终启用、真算）。
 *
 * <p>六条规则全部基于运行观察真实计算，没有任何一条能被场景关闭或硬编码 PASS：
 *
 * <ol>
 *   <li>ORC-RES-SYS-001 单容量：任一观察点上，同一资源最多一个 HELD；</li>
 *   <li>ORC-RES-SYS-002 队列顺序：WAITING 按 requestedOffsetMillis → requestedLogicalSequence → applicationId 排队，
 *       晋升只能取队首（不能越过更小的键）；</li>
 *   <li>ORC-RES-SYS-003 终态单调：RELEASED / CANCELLED 之后不得再变回非终态；</li>
 *   <li>ORC-RES-SYS-004 幂等稳定：IDEMPOTENT 决策不得改动原预约的编号、排序键或状态；</li>
 *   <li>ORC-RES-SYS-005 禁用不驱逐：RESOURCE_SET DISABLED 不得改变该资源的 HELD 持有者；</li>
 *   <li>ORC-RES-SYS-006 重启一致：逐对比较 restart 前后规范摘要，输出
 *       {@code restartPairs=<n>,mismatches=<m>}。</li>
 * </ol>
 */
public final class ResourceInvariantOracle {

    private ResourceInvariantOracle() {
    }

    /**
     * 计算六条资源不变量；同时用于运行期 Oracle 与反例检查。
     */
    public static List<OracleResult> check(ResourceWorldSnapshot finalWorld,
                                           List<ResourceStep> steps,
                                           List<RestartPair> restartPairs) {
        List<OracleResult> results = new ArrayList<>(6);
        results.add(singleCapacity(finalWorld, steps));
        results.add(queueOrder(finalWorld, steps));
        results.add(terminalMonotonic(finalWorld, steps));
        results.add(idempotentStable(steps));
        results.add(disableKeepsHolder(steps));
        results.add(restartReplay(restartPairs));
        return List.copyOf(results);
    }

    // ---------- ORC-RES-SYS-001 ----------

    private static OracleResult singleCapacity(ResourceWorldSnapshot finalWorld, List<ResourceStep> steps) {
        int max = 0;
        String worst = "none";
        for (ResourceWorldSnapshot snapshot : snapshots(finalWorld, steps)) {
            Map<String, Integer> held = new LinkedHashMap<>();
            for (Reservation reservation : snapshot.reservations()) {
                if (reservation.state() == ReservationState.HELD) {
                    held.merge(reservation.resourceId(), 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : held.entrySet()) {
                if (entry.getValue() > max) {
                    max = entry.getValue();
                    worst = entry.getKey();
                }
            }
        }
        String actual = "maxHeldPerResource=" + max;
        if (max <= 1) {
            return OracleResult.pass("ORC-RES-SYS-001", "SINGLE_CAPACITY", "maxHeldPerResource<=1", actual);
        }
        return OracleResult.fail("ORC-RES-SYS-001", "SINGLE_CAPACITY", "maxHeldPerResource<=1", actual,
                "resource=" + worst + " 同时 HELD=" + max);
    }

    // ---------- ORC-RES-SYS-002 ----------

    private static OracleResult queueOrder(ResourceWorldSnapshot finalWorld, List<ResourceStep> steps) {
        List<ResourceWorldSnapshot> history = snapshots(finalWorld, steps);
        int waitingPairs = 0;
        int inversions = 0;
        int promotions = 0;
        int skippedHead = 0;
        String detail = "";
        ResourceWorldSnapshot previous = ResourceWorldSnapshot.empty();
        for (ResourceWorldSnapshot snapshot : history) {
            // 2a. 每个资源的 WAITING 必须按稳定键升序。
            for (String resourceId : resourceIds(snapshot)) {
                List<Reservation> waiting = waitingOf(snapshot, resourceId);
                for (int i = 1; i < waiting.size(); i++) {
                    waitingPairs++;
                    if (compareKey(waiting.get(i - 1), waiting.get(i)) >= 0) {
                        inversions++;
                        if (detail.isEmpty()) {
                            detail = "resource=" + resourceId
                                    + " 队列逆序 " + waiting.get(i - 1).applicationId()
                                    + " -> " + waiting.get(i).applicationId();
                        }
                    }
                }
            }
            // 2b. 本次新晋升的 HELD 不能跳过队列里更小的等待键。
            for (String resourceId : resourceIds(snapshot)) {
                List<Reservation> newlyHeld = newlyHeld(previous, snapshot, resourceId);
                for (Reservation promoted : newlyHeld) {
                    promotions++;
                    for (Reservation waiting : waitingOf(snapshot, resourceId)) {
                        if (wasWaiting(previous, waiting.applicationId())
                                && compareKey(waiting, promoted) < 0) {
                            skippedHead++;
                            if (detail.isEmpty()) {
                                detail = "resource=" + resourceId
                                        + " 晋升 " + promoted.applicationId()
                                        + " 越过队首 " + waiting.applicationId();
                            }
                        }
                    }
                }
            }
            previous = snapshot;
        }
        String actual = "waitingPairs=" + waitingPairs + ",inversions=" + inversions
                + ",promotions=" + promotions + ",skippedHead=" + skippedHead;
        if (inversions == 0 && skippedHead == 0) {
            return OracleResult.pass("ORC-RES-SYS-002", "QUEUE_ORDER",
                    "queueKeyAscending,promoteHeadOnly", actual);
        }
        return OracleResult.fail("ORC-RES-SYS-002", "QUEUE_ORDER",
                "queueKeyAscending,promoteHeadOnly", actual, detail);
    }

    private static int compareKey(Reservation a, Reservation b) {
        int byOffset = Long.compare(a.requestedOffsetMillis(), b.requestedOffsetMillis());
        if (byOffset != 0) {
            return byOffset;
        }
        int bySequence = Long.compare(a.requestedLogicalSequence(), b.requestedLogicalSequence());
        if (bySequence != 0) {
            return bySequence;
        }
        return a.applicationId().compareTo(b.applicationId());
    }

    private static List<Reservation> waitingOf(ResourceWorldSnapshot snapshot, String resourceId) {
        List<Reservation> waiting = new ArrayList<>();
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                waiting.add(reservation);
            }
        }
        return waiting;
    }

    private static List<Reservation> newlyHeld(ResourceWorldSnapshot before,
                                               ResourceWorldSnapshot after,
                                               String resourceId) {
        List<Reservation> newlyHeld = new ArrayList<>();
        for (Reservation reservation : after.reservations()) {
            if (!reservation.resourceId().equals(resourceId)
                    || reservation.state() != ReservationState.HELD) {
                continue;
            }
            if (stateOf(before, reservation.applicationId()) != ReservationState.HELD) {
                newlyHeld.add(reservation);
            }
        }
        return newlyHeld;
    }

    private static boolean wasWaiting(ResourceWorldSnapshot snapshot, String applicationId) {
        return stateOf(snapshot, applicationId) == ReservationState.WAITING;
    }

    private static ReservationState stateOf(ResourceWorldSnapshot snapshot, String applicationId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation.state();
            }
        }
        return null;
    }

    // ---------- ORC-RES-SYS-003 ----------

    private static OracleResult terminalMonotonic(ResourceWorldSnapshot finalWorld, List<ResourceStep> steps) {
        Map<String, ReservationState> terminal = new HashMap<>();
        int observations = 0;
        int reopens = 0;
        String detail = "";
        for (ResourceWorldSnapshot snapshot : snapshots(finalWorld, steps)) {
            for (Reservation reservation : snapshot.reservations()) {
                observations++;
                ReservationState known = terminal.get(reservation.applicationId());
                if (known != null && reservation.state() != known) {
                    reopens++;
                    if (detail.isEmpty()) {
                        detail = "app=" + reservation.applicationId()
                                + " " + known + " -> " + reservation.state()
                                + " at snapshot";
                    }
                    continue;
                }
                if (reservation.state().isTerminal()) {
                    terminal.put(reservation.applicationId(), reservation.state());
                }
            }
        }
        String actual = "reservationObservations=" + observations + ",terminalReopens=" + reopens;
        return reopens == 0
                ? OracleResult.pass("ORC-RES-SYS-003", "TERMINAL_MONOTONIC", "terminalNeverReopens", actual)
                : OracleResult.fail("ORC-RES-SYS-003", "TERMINAL_MONOTONIC", "terminalNeverReopens", actual,
                        detail);
    }

    // ---------- ORC-RES-SYS-004 ----------

    private static OracleResult idempotentStable(List<ResourceStep> steps) {
        int idempotent = 0;
        int mismatches = 0;
        String detail = "";
        for (ResourceStep step : steps) {
            ReservationDecision decision = step.decision();
            if (decision == null || decision.type() != ReservationDecision.DecisionType.IDEMPOTENT) {
                continue;
            }
            idempotent++;
            String applicationId = decision.reservation().applicationId();
            Reservation before = findReservation(step.before(), applicationId);
            Reservation after = findReservation(step.after(), applicationId);
            boolean stable = before != null && after != null
                    && before.reservationId().equals(after.reservationId())
                    && before.applicationId().equals(after.applicationId())
                    && before.destinationId().equals(after.destinationId())
                    && before.servicePointId().equals(after.servicePointId())
                    && before.resourceId().equals(after.resourceId())
                    && before.state() == after.state()
                    && before.requestedOffsetMillis() == after.requestedOffsetMillis()
                    && before.requestedLogicalSequence() == after.requestedLogicalSequence();
            if (!stable) {
                mismatches++;
                if (detail.isEmpty()) {
                    detail = "app=" + applicationId + " at " + step.eventId()
                            + " before=" + keyOf(before) + " after=" + keyOf(after);
                }
            }
        }
        // 同一申请在任一观察点上不得出现两条预约（重复请求不得新增/重排条目）。
        for (ResourceStep step : steps) {
            Map<String, Integer> counts = new HashMap<>();
            for (Reservation reservation : step.after().reservations()) {
                counts.merge(reservation.applicationId(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > 1) {
                    mismatches++;
                    if (detail.isEmpty()) {
                        detail = "app=" + entry.getKey() + " at " + step.eventId()
                                + " reservations=" + entry.getValue();
                    }
                }
            }
        }
        String actual = "idempotentRequests=" + idempotent + ",mismatches=" + mismatches;
        return mismatches == 0
                ? OracleResult.pass("ORC-RES-SYS-004", "IDEMPOTENT_STABLE", "idempotentUnchanged", actual)
                : OracleResult.fail("ORC-RES-SYS-004", "IDEMPOTENT_STABLE", "idempotentUnchanged", actual,
                        detail);
    }

    private static Reservation findReservation(ResourceWorldSnapshot snapshot, String applicationId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation;
            }
        }
        return null;
    }

    private static String keyOf(Reservation reservation) {
        if (reservation == null) {
            return "null";
        }
        return reservation.state() + "@" + reservation.requestedOffsetMillis()
                + "/" + reservation.requestedLogicalSequence();
    }

    // ---------- ORC-RES-SYS-005 ----------

    private static OracleResult disableKeepsHolder(List<ResourceStep> steps) {
        int disableEvents = 0;
        int evictions = 0;
        String detail = "";
        for (ResourceStep step : steps) {
            if (step.action() != ScenarioEvent.Action.RESOURCE_SET
                    || !"DISABLED".equals(step.value())) {
                continue;
            }
            disableEvents++;
            Set<String> beforeHolders = holders(step.before(), step.resourceId());
            Set<String> afterHolders = holders(step.after(), step.resourceId());
            if (!beforeHolders.equals(afterHolders)) {
                evictions++;
                if (detail.isEmpty()) {
                    detail = "resource=" + step.resourceId() + " at " + step.eventId()
                            + " holders " + beforeHolders + " -> " + afterHolders;
                }
            }
        }
        String actual = "disableEvents=" + disableEvents + ",holderEvictions=" + evictions;
        return evictions == 0
                ? OracleResult.pass("ORC-RES-SYS-005", "DISABLE_KEEPS_HOLDER",
                        "holdersUnchangedOnDisable", actual)
                : OracleResult.fail("ORC-RES-SYS-005", "DISABLE_KEEPS_HOLDER",
                        "holdersUnchangedOnDisable", actual, detail);
    }

    private static Set<String> holders(ResourceWorldSnapshot snapshot, String resourceId) {
        Set<String> holders = new LinkedHashSet<>();
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.HELD) {
                holders.add(reservation.applicationId());
            }
        }
        return holders;
    }

    // ---------- ORC-RES-SYS-006 ----------

    private static OracleResult restartReplay(List<RestartPair> restartPairs) {
        int mismatches = 0;
        String detail = "";
        for (RestartPair pair : restartPairs) {
            if (!pair.consistent()) {
                mismatches++;
                if (detail.isEmpty()) {
                    detail = "eventId=" + pair.eventId()
                            + " before=" + pair.beforeDigest()
                            + " after=" + pair.afterDigest();
                }
            }
        }
        String actual = "restartPairs=" + restartPairs.size() + ",mismatches=" + mismatches;
        String expected = "restartPairs=" + restartPairs.size() + ",mismatches=0";
        return mismatches == 0
                ? OracleResult.pass("ORC-RES-SYS-006", "RESTART_REPLAY", expected, actual)
                : OracleResult.fail("ORC-RES-SYS-006", "RESTART_REPLAY", expected, actual, detail);
    }

    // ---------- 工具 ----------

    private static List<ResourceWorldSnapshot> snapshots(ResourceWorldSnapshot finalWorld,
                                                         List<ResourceStep> steps) {
        List<ResourceWorldSnapshot> history = new ArrayList<>(steps.size() + 1);
        for (ResourceStep step : steps) {
            history.add(step.after());
        }
        history.add(finalWorld);
        return history;
    }

    private static Set<String> resourceIds(ResourceWorldSnapshot snapshot) {
        Set<String> ids = new LinkedHashSet<>();
        for (Reservation reservation : snapshot.reservations()) {
            ids.add(reservation.resourceId());
        }
        return ids;
    }
}
