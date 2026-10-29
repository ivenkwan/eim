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

    /** Seal yesterday's audit day (hash chain). Manual runs via AdminOpsResource. */
    @Scheduled(cron = "0 10 0 * * ?", identity = "audit-sealer")
    void sealYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT eim_ops.seal_audit_day(?)")) {
            ps.setDate(1, java.sql.Date.valueOf(yesterday));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    LOG.infof("audit day %s sealed: %s", yesterday, rs.getString(1));
                }
            }
        } catch (SQLException e) {
            // An empty day refuses to seal (expected on quiet days); anything
            // else is real and must page (runbook: audit-sealer).
            LOG.warnf(e, "audit seal for %s failed", yesterday);
        }
    }

    /** Compare mutations vs outbox vs event-audit (last hour) every minute. */
    @Scheduled(every = "60s", identity = "audit-reconciliation")
    void reconcile() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eim_ops.reconcile_audit(?)")) {
            ps.setTimestamp(1, Timestamp.from(OffsetDateTime.now().minusHours(1).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long gap = rs.getLong(4);
                    auditGapGauge.set(gap);
                    if (gap != 0) {
                        LOG.warnf("AUDIT RECONCILIATION GAP = %d (mutations=%d outbox=%d events=%d) — NFR-64 alert",
                                gap, rs.getLong(1), rs.getLong(2), rs.getLong(3));
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("audit reconciliation failed", e);
        }
    }
}
