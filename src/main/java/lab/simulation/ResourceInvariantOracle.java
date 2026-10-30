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
 * 资源系统不变量 ORC-RES-SYS-001..006（只读、始终启用、真算）与 ORC-RES-EXT-001..003。
 *
 * <p>STU-SIM-02-R1 §13.1 固定绑定：
 * <ol>
 *   <li>ORC-RES-SYS-001 每个 resource 的 HELD 数量 ≤ 1；</li>
 *   <li>ORC-RES-SYS-002 每个 application 的非终态 Reservation 数量 ≤ 1；</li>
 *   <li>ORC-RES-SYS-003 每条 Reservation 的 Destination/ServicePoint/Resource 引用链有效；</li>
 *   <li>ORC-RES-SYS-004 每个 WAITING 队列按固定键排序且无重复；</li>
 *   <li>ORC-RES-SYS-005 RELEASED/CANCELLED 不再变为 WAITING/HELD；</li>
 *   <li>ORC-RES-SYS-006 WORLD_RESTART 前后已提交资源快照规范摘要一致。</li>
 * </ol>
 *
 * <p>§13.2 计算范围：001—004 检查运行过程中记录的每一个已提交快照（事件 after 快照 + 最终快照），
 * 不只检查最终快照；005 按状态历史检查终态单调；006 逐对比较 restart 前后摘要。
 * 没有 WORLD_RESTART 时 006 输出 {@code restartPairs=0,mismatches=0} 并 PASS。
 * 本类只读，不调用任何 Registry 写入口，也不读取场景 EXPECT 决定 PASS/FAIL。
 *
 * <p>§13.3 扩展检查（不替代固定六条）：
 * <ul>
 *   <li>ORC-RES-EXT-001 IDEMPOTENT_STABLE：IDEMPOTENT 决策前后预约逐字段不变；</li>
 *   <li>ORC-RES-EXT-002 DISABLE_KEEPS_HOLDER：DISABLED 不驱逐 HELD 持有者；</li>
 *   <li>ORC-RES-EXT-003 PROMOTE_HEAD_ONLY：晋升只能取队列最小键，不能越过队首。</li>
 * </ul>
 */
public final class ResourceInvariantOracle {

    private ResourceInvariantOracle() {
    }

    /**
     * 计算固定六条资源不变量与三条扩展检查；同时用于运行期 Oracle 与反例检查。
     */
    public static List<OracleResult> check(ResourceWorldSnapshot finalWorld,
                                           List<ResourceStep> steps,
                                           List<RestartPair> restartPairs) {
        List<ResourceWorldSnapshot> history = snapshots(finalWorld, steps);
        List<OracleResult> results = new ArrayList<>(9);
        results.add(heldPerResource(history));
        results.add(activePerApplication(history));
        results.add(referenceChain(history));
        results.add(waitQueue(history));
        results.add(terminalMonotonic(history));
        results.add(restartReplay(restartPairs));
        results.add(idempotentStable(steps));
        results.add(disableKeepsHolder(steps));
        results.add(promoteHeadOnly(history));
        return List.copyOf(results);
    }

    // ---------- ORC-RES-SYS-001：每个 resource 的 HELD 数量 ≤ 1 ----------

    private static OracleResult heldPerResource(List<ResourceWorldSnapshot> history) {
        int max = 0;
        String worst = "none";
        for (ResourceWorldSnapshot snapshot : history) {
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
        String expected = "heldCountPerResource<=1";
        String actual = "maxHeldPerResource=" + max + ",observedSnapshots=" + history.size()
                + (max > 1 ? ",resourceId=" + worst : "");
        if (max <= 1) {
            return OracleResult.pass("ORC-RES-SYS-001", "SINGLE_HELD_PER_RESOURCE", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-001", "SINGLE_HELD_PER_RESOURCE", expected, actual,
                "resourceId=" + worst + ";heldCount=" + max);
    }

    // ---------- ORC-RES-SYS-002：每个 application 的非终态 Reservation 数量 ≤ 1 ----------

    private static OracleResult activePerApplication(List<ResourceWorldSnapshot> history) {
        int max = 0;
        String worstApplication = "none";
        String worstReservations = "";
        Set<String> applications = new LinkedHashSet<>();
        for (ResourceWorldSnapshot snapshot : history) {
            Map<String, List<Reservation>> active = new LinkedHashMap<>();
            for (Reservation reservation : snapshot.reservations()) {
                if (!reservation.state().isTerminal()) {
                    active.computeIfAbsent(reservation.applicationId(), key -> new ArrayList<>())
                            .add(reservation);
                }
            }
            applications.addAll(active.keySet());
            for (Map.Entry<String, List<Reservation>> entry : active.entrySet()) {
                if (entry.getValue().size() > max) {
                    max = entry.getValue().size();
                    worstApplication = entry.getKey();
                    worstReservations = reservationIds(entry.getValue());
                }
            }
        }
        String expected = "activeReservationCountPerApplication<=1";
        String actual = max <= 1
                ? "applications=" + applications.size()
                        + ",maxActiveReservationsPerApplication=" + max
                        + ",observedSnapshots=" + history.size()
                : "applicationId=" + worstApplication + ";activeCount=" + max
                        + ",observedSnapshots=" + history.size();
        if (max <= 1) {
            return OracleResult.pass(
                    "ORC-RES-SYS-002", "ACTIVE_PER_APPLICATION", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-002", "ACTIVE_PER_APPLICATION", expected, actual,
                "applicationId=" + worstApplication + ";reservationIds=" + worstReservations);
    }

    // ---------- ORC-RES-SYS-003：引用链有效 ----------

    private static OracleResult referenceChain(List<ResourceWorldSnapshot> history) {
        int checked = 0;
        int orphans = 0;
        String detail = "";
        for (ResourceWorldSnapshot snapshot : history) {
            Set<String> destinations = new HashSet<>();
            Map<String, String> servicePointDestination = new HashMap<>();
            Map<String, String> resourceServicePoint = new HashMap<>();
            for (var destination : snapshot.destinations()) {
                destinations.add(destination.destinationId());
            }
            for (var servicePoint : snapshot.servicePoints()) {
                servicePointDestination.put(servicePoint.servicePointId(), servicePoint.destinationId());
            }
            for (var resource : snapshot.resources()) {
                resourceServicePoint.put(resource.resourceId(), resource.servicePointId());
            }
            for (Reservation reservation : snapshot.reservations()) {
                checked++;
                String violation = chainViolation(reservation, destinations,
                        servicePointDestination, resourceServicePoint);
                if (violation != null) {
                    orphans++;
                    if (detail.isEmpty()) {
                        detail = "reservationId=" + reservation.reservationId() + ";" + violation;
                    }
                }
            }
        }
        String expected = "reservationReferenceChainValid";
        String actual = "reservationsChecked=" + checked + ",orphanReferences=" + orphans
                + ",observedSnapshots=" + history.size();
        if (orphans == 0) {
            return OracleResult.pass("ORC-RES-SYS-003", "REFERENCE_CHAIN", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-003", "REFERENCE_CHAIN", expected, actual, detail);
    }

    private static String chainViolation(Reservation reservation,
                                         Set<String> destinations,
                                         Map<String, String> servicePointDestination,
                                         Map<String, String> resourceServicePoint) {
        if (!destinations.contains(reservation.destinationId())) {
            return "missingDestination=" + reservation.destinationId();
        }
        String servicePoint = reservation.servicePointId();
        if (!servicePointDestination.containsKey(servicePoint)) {
            return "missingServicePoint=" + servicePoint;
        }
        if (!servicePointDestination.get(servicePoint).equals(reservation.destinationId())) {
            return "servicePointDestinationMismatch=" + servicePoint;
        }
        String resource = reservation.resourceId();
        if (!resourceServicePoint.containsKey(resource)) {
            return "missingResource=" + resource;
        }
        if (!resourceServicePoint.get(resource).equals(servicePoint)) {
            return "resourceServicePointMismatch=" + resource;
        }
        return null;
    }

    // ---------- ORC-RES-SYS-004：WAITING 队列按固定键排序且无重复 ----------

    private static OracleResult waitQueue(List<ResourceWorldSnapshot> history) {
        int resourcesWithWaiting = 0;
        int waitingEntries = 0;
        int duplicateApplications = 0;
        int inversions = 0;
        String detail = "";
        for (ResourceWorldSnapshot snapshot : history) {
            Map<String, List<Reservation>> waitingByResource = new LinkedHashMap<>();
            for (Reservation reservation : snapshot.reservations()) {
                if (reservation.state() == ReservationState.WAITING) {
                    waitingByResource
                            .computeIfAbsent(reservation.resourceId(), key -> new ArrayList<>())
                            .add(reservation);
                }
            }
            for (Map.Entry<String, List<Reservation>> entry : waitingByResource.entrySet()) {
                resourcesWithWaiting++;
                List<Reservation> waiting = entry.getValue();
                waitingEntries += waiting.size();
                Set<String> applications = new HashSet<>();
                String duplicate = null;
                for (Reservation reservation : waiting) {
                    if (!applications.add(reservation.applicationId()) && duplicate == null) {
                        duplicate = reservation.applicationId();
                    }
                }
                if (duplicate != null) {
                    duplicateApplications++;
                    if (detail.isEmpty()) {
                        detail = "resourceId=" + entry.getKey()
                                + ";duplicateApplicationId=" + duplicate;
                    }
                }
                for (int i = 1; i < waiting.size(); i++) {
                    if (compareKey(waiting.get(i - 1), waiting.get(i)) >= 0) {
                        inversions++;
                        if (detail.isEmpty()) {
                            detail = "resourceId=" + entry.getKey()
                                    + ";inversion=" + waiting.get(i - 1).applicationId()
                                    + "->" + waiting.get(i).applicationId();
                        }
                    }
                }
            }
        }
        String expected = "waitQueueKeyStrictlyIncreasingAndUnique";
        String actual = "resourcesWithWaiting=" + resourcesWithWaiting
                + ",waitingEntries=" + waitingEntries
                + ",duplicateApplications=" + duplicateApplications
                + ",inversions=" + inversions
                + ",observedSnapshots=" + history.size();
        if (duplicateApplications == 0 && inversions == 0) {
            return OracleResult.pass("ORC-RES-SYS-004", "WAIT_QUEUE_SORTED_UNIQUE", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-004", "WAIT_QUEUE_SORTED_UNIQUE", expected, actual, detail);
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

    // ---------- ORC-RES-SYS-005：RELEASED/CANCELLED 不再变为 WAITING/HELD ----------

    private static OracleResult terminalMonotonic(List<ResourceWorldSnapshot> history) {
        Map<String, ReservationState> terminal = new HashMap<>();
        int observations = 0;
        int reopens = 0;
        String detail = "";
        for (ResourceWorldSnapshot snapshot : history) {
            for (Reservation reservation : snapshot.reservations()) {
                observations++;
                ReservationState known = terminal.get(reservation.applicationId());
                if (known != null) {
                    if (reservation.state() != known) {
                        reopens++;
                        if (detail.isEmpty()) {
                            detail = "applicationId=" + reservation.applicationId()
                                    + ";from=" + known + ";to=" + reservation.state();
                        }
                    }
                    continue;
                }
                if (reservation.state().isTerminal()) {
                    terminal.put(reservation.applicationId(), reservation.state());
                }
            }
        }
        String expected = "terminalStateNeverReopens";
        String actual = "reservationObservations=" + observations + ",terminalReopens=" + reopens;
        if (reopens == 0) {
            return OracleResult.pass("ORC-RES-SYS-005", "TERMINAL_MONOTONIC", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-005", "TERMINAL_MONOTONIC", expected, actual, detail);
    }

    // ---------- ORC-RES-SYS-006：WORLD_RESTART 前后摘要一致 ----------

    private static OracleResult restartReplay(List<RestartPair> restartPairs) {
        int mismatches = 0;
        String detail = "";
        for (RestartPair pair : restartPairs) {
            if (!pair.consistent()) {
                mismatches++;
                if (detail.isEmpty()) {
                    detail = "eventId=" + pair.eventId()
                            + ";before=" + pair.beforeDigest()
                            + ";after=" + pair.afterDigest();
                }
            }
        }
        String actual = "restartPairs=" + restartPairs.size() + ",mismatches=" + mismatches;
        String expected = "restartPairs=" + restartPairs.size() + ",mismatches=0";
        if (mismatches == 0) {
            return OracleResult.pass("ORC-RES-SYS-006", "RESTART_REPLAY", expected, actual);
        }
        return OracleResult.fail("ORC-RES-SYS-006", "RESTART_REPLAY", expected, actual, detail);
    }

    // ---------- ORC-RES-EXT-001：IDEMPOTENT 决策稳定 ----------

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
                    detail = "applicationId=" + applicationId + ";eventId=" + step.eventId()
                            + ";before=" + keyOf(before) + ";after=" + keyOf(after);
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
                        detail = "applicationId=" + entry.getKey() + ";eventId=" + step.eventId()
                                + ";reservations=" + entry.getValue();
                    }
                }
            }
        }
        String expected = "idempotentDecisionUnchanged";
        String actual = "idempotentRequests=" + idempotent + ",mismatches=" + mismatches;
        if (mismatches == 0) {
            return OracleResult.pass("ORC-RES-EXT-001", "IDEMPOTENT_STABLE", expected, actual);
        }
        return OracleResult.fail("ORC-RES-EXT-001", "IDEMPOTENT_STABLE", expected, actual, detail);
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

    // ---------- ORC-RES-EXT-002：DISABLED 不驱逐 holder ----------

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
                    detail = "resourceId=" + step.resourceId() + ";eventId=" + step.eventId()
                            + ";holdersBefore=" + beforeHolders + ";holdersAfter=" + afterHolders;
                }
            }
        }
        String expected = "holdersUnchangedOnDisable";
        String actual = "disableEvents=" + disableEvents + ",holderEvictions=" + evictions;
        if (evictions == 0) {
            return OracleResult.pass("ORC-RES-EXT-002", "DISABLE_KEEPS_HOLDER", expected, actual);
        }
        return OracleResult.fail("ORC-RES-EXT-002", "DISABLE_KEEPS_HOLDER", expected, actual, detail);
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

    // ---------- ORC-RES-EXT-003：晋升只能取队首 ----------

    private static OracleResult promoteHeadOnly(List<ResourceWorldSnapshot> history) {
        int promotions = 0;
        int skippedHead = 0;
        String detail = "";
        ResourceWorldSnapshot previous = ResourceWorldSnapshot.empty();
        for (ResourceWorldSnapshot snapshot : history) {
            for (String resourceId : resourceIds(snapshot)) {
                for (Reservation promoted : newlyHeld(previous, snapshot, resourceId)) {
                    promotions++;
                    for (Reservation waiting : waitingOf(snapshot, resourceId)) {
                        if (wasWaiting(previous, waiting.applicationId())
                                && compareKey(waiting, promoted) < 0) {
                            skippedHead++;
                            if (detail.isEmpty()) {
                                detail = "resourceId=" + resourceId
                                        + ";promoted=" + promoted.applicationId()
                                        + ";skippedHead=" + waiting.applicationId();
                            }
                        }
                    }
                }
            }
            previous = snapshot;
        }
        String expected = "promoteQueueHeadOnly";
        String actual = "promotions=" + promotions + ",skippedHead=" + skippedHead;
        if (skippedHead == 0) {
            return OracleResult.pass("ORC-RES-EXT-003", "PROMOTE_HEAD_ONLY", expected, actual);
        }
        return OracleResult.fail("ORC-RES-EXT-003", "PROMOTE_HEAD_ONLY", expected, actual, detail);
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

    // ---------- 工具 ----------

    private static String reservationIds(List<Reservation> reservations) {
        List<String> ids = new ArrayList<>(reservations.size());
        for (Reservation reservation : reservations) {
            ids.add(reservation.reservationId());
        }
        return String.join(",", ids);
    }

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
