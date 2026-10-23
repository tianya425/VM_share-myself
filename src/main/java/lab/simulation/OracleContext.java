package lab.simulation;

import lab.OutboxEntry;
import lab.TransferApplication;

import java.util.List;

/**
 * 一次 Oracle 检查所需的业务对象快照。
 *
 * <p>只读；Oracle 不修改其中任何对象。
 */
public record OracleContext(
        List<TransferApplication> applications,
        List<OutboxEntry> outbox,
        List<ExternalInteraction> interactions) {

    public OracleContext {
        applications = List.copyOf(applications);
        outbox = List.copyOf(outbox);
        interactions = List.copyOf(interactions);
    }
}
