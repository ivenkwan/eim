package io.eim.coreapi.audit;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AUD-6 daily hash-chain sealer + AUD-9 reconciliation job (NFR-60/64).
 * Runs as scheduled jobs in every replica; the SQL functions are idempotent
 * and safe under concurrent schedulers (unique seal_day, count-only reconcile).
 */
@ApplicationScoped
public class AuditOpsScheduler {

    private static final Logger LOG = Logger.getLogger(AuditOpsScheduler.class);

    @Inject
    AgroalDataSource ds;

    @Inject
    MeterRegistry registry;

    private final AtomicLong auditGapGauge = new AtomicLong(0);

    public AuditOpsScheduler() {
    }

    @Inject
    void registerGauge() {
        Gauge.builder("eim_audit_reconcile_gap", auditGapGauge, AtomicLong::get)
                .description("audited mutations minus published event-audit rows (NFR-64; non-zero = alert)")
                .register(registry);
    }

    /**
     * Seal completed audit days (hash chain). Manual runs via AdminOpsResource.
     * Hourly + idempotent + UTC-day computed in SQL: the trigger time no
     * longer depends on the pod timezone (Quarkus @Scheduled has no cron
     * zone attribute), so a completed UTC day is sealed within an hour of
     * UTC midnight regardless of where the JVM thinks it is.
     */
    @Scheduled(cron = "0 10 * * * ?", identity = "audit-sealer")
    void sealYesterday() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT eim_ops.seal_audit_day((now() AT TIME ZONE 'UTC')::date - 1)")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    LOG.infof("audit day sealed: %s", rs.getString(1));
                }
            }
        } catch (SQLException e) {
            // An empty day refuses to seal (expected on quiet days); anything
            // else is real and must page (runbook: audit-sealer).
            LOG.warnf(e, "audit seal failed");
        }
    }

    /** Compare mutations vs outbox vs event-audit (last hour) every minute.
     *  The NFR-64 alert keys on the API-plane gap — process transitions
     *  (source_channel='PROCESS') are audited-only by design, so the raw
     *  mutation−event delta includes them without an integrity failure. */
    @Scheduled(every = "60s", identity = "audit-reconciliation")
    void reconcile() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eim_ops.reconcile_audit_full(?)")) {
            ps.setTimestamp(1, Timestamp.from(OffsetDateTime.now().minusHours(1).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long apiGap = rs.getLong(7);
                    auditGapGauge.set(apiGap);
                    if (apiGap != 0) {
                        LOG.warnf("AUDIT RECONCILIATION API GAP = %d (api_mutations=%d "
                                        + "paired_outbox=%d raw_gap=%d) — NFR-64 alert",
                                apiGap, rs.getLong(5), rs.getLong(6), rs.getLong(4));
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("audit reconciliation failed", e);
        }
    }
}
