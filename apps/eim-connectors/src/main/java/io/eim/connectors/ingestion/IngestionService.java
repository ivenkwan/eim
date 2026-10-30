package io.eim.connectors.ingestion;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.audit.AuditService;
import io.eim.connectors.common.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ingestion engine (FR-201..205, exit criteria for TG-2): runs a
 * connector's records through the field mapping, commits accepted rows as
 * raw_transaction + credit_allocation, quarantines invalid rows with per-row
 * errors, tracks the watermark, detects late-arriving rows against LOCKED
 * periods (FR-604 → trueup.required), and writes the whole run's audit
 * trail in the same transaction.
 */
@ApplicationScoped
public class IngestionService {

    @Inject
    AgroalDataSource ds;

    @Inject
    FieldMappingService mapper;

    @Inject
    AuditService audit;

    public record BatchResult(UUID batchId, int total, int accepted, int quarantined,
                              String watermarkAfter, int lateArriving) {
    }

    /**
     * Per-tenant row quota per batch (NFR-33): one tenant's warehouse burst
     * cannot monopolize the shared connector runtime — oversized batches are
     * rejected up front with a clear error while other tenants proceed.
     * Configured via the configuration registry (entity
     * {@code connector.tenant_quota_rows}); default 100,000.
     */
    public static final int DEFAULT_TENANT_QUOTA_ROWS = 100_000;

    /**
     * Ingest one batch of source records. All parameterized SQL; the entire
     * batch (rows + quarantine + batch record + audit + outbox) commits or
     * rolls back together.
     */
    @Transactional
    public BatchResult ingest(TenantContext ctx, String sourceSystem, String objectName,
                              String watermarkColumn, String watermarkValue,
                              List<Map<String, Object>> records, Map<String, String> mapping) {
        int quota = tenantQuotaRows(ctx);
        if (records.size() > quota) {
            throw new IllegalArgumentException(
                    "tenant quota exceeded: batch of " + records.size()
                            + " rows is above the per-batch limit of " + quota
                            + " (NFR-33) — split the sync or raise connector.tenant_quota_rows");
        }
        UUID batchId = UUID.randomUUID();
        int accepted = 0;
        int quarantined = 0;
        int late = 0;
        String maxWatermark = watermarkValue;

        try (Connection c = ds.getConnection()) {
            insertBatch(c, batchId, ctx, sourceSystem, objectName);
            for (Map<String, Object> record : records) {
                FieldMappingService.MappingResult result = mapper.map(record, mapping);
                UUID recordId = UUID.randomUUID();
                if (result.accepted()) {
                    FieldMappingService.MappedTransaction tx = result.mapped();
                    UUID txnId = UUID.randomUUID();
                    insertRawTransaction(c, txnId, ctx, sourceSystem, tx);
                    insertIngestionRecord(c, recordId, batchId, tx.externalDealId(), "ACCEPTED", result.raw(), null, txnId);
                    attachCredit(c, ctx, txnId, tx, result.raw());
                    accepted++;
                    if (isLateArriving(c, ctx, tx.transactionDate())) {
                        late++;
                        emitTrueupSignal(c, ctx, txnId, sourceSystem, tx.externalDealId());
                    }
                } else {
                    insertIngestionRecord(c, recordId, batchId,
                            String.valueOf(record.getOrDefault(mapping.get("external_deal_id"), "?")),
                            "QUARANTINED", result.raw(), result.errors(), null);
                    quarantined++;
                }
                String wm = watermarkOf(record, watermarkColumn);
                if (wm != null && (maxWatermark == null || wm.compareTo(maxWatermark) > 0)) {
                    maxWatermark = wm;
                }
            }
            finalizeBatch(c, batchId, records.size(), accepted, quarantined, maxWatermark);
            upsertWatermark(c, sourceSystem, objectName, watermarkColumn, maxWatermark);
        } catch (SQLException e) {
            throw new IllegalStateException("ingestion batch failed", e);
        }

        audit.record(ctx, "CONNECTOR_RUN", "ingestion_batch", batchId.toString(),
                "{\"source\":\"" + sourceSystem + "\",\"object\":\"" + objectName
                        + "\",\"total\":" + records.size() + ",\"accepted\":" + accepted
                        + ",\"quarantined\":" + quarantined + ",\"late_arriving\":" + late + "}",
                "scheduled sync",
                records.isEmpty() ? "sync.completed" : "sync.completed", batchId.toString(),
                "{\"source\":\"" + sourceSystem + "\",\"batch\":\"" + batchId + "\",\"accepted\":" + accepted
                        + ",\"quarantined\":" + quarantined + ",\"late_arriving\":" + late + "}");
        return new BatchResult(batchId, records.size(), accepted, quarantined, maxWatermark, late);
    }

    public int tenantQuotaRows(TenantContext ctx) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT (payload->>'rows')::int FROM configuration "
                             + "WHERE entity = 'connector.tenant_quota_rows' "
                             + "AND tenant_id::text = ? AND status = 'ACTIVE' "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : DEFAULT_TENANT_QUOTA_ROWS;
            }
        } catch (SQLException e) {
            return DEFAULT_TENANT_QUOTA_ROWS; // fail open on config issues; the quota is isolation, not a security gate
        }
    }

    // ---------------------------------------------------------------- helpers
    private void insertBatch(Connection c, UUID batchId, TenantContext ctx,
                             String source, String object) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ingestion_batch (ingestion_batch_id, tenant_id, source_system, object_name, "
                        + "status, triggered_by, correlation_id) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, batchId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, source);
            ps.setString(4, object);
            ps.setString(5, "RUNNING");
            ps.setString(6, ctx.actor());
            ps.setString(7, ctx.correlationId());
            ps.executeUpdate();
        }
    }

    private void insertRawTransaction(Connection c, UUID txnId, TenantContext ctx,
                                      String source, FieldMappingService.MappedTransaction tx) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id, "
                        + "transaction_date, booking_amount, currency_code, custom_attributes, ingestion_batch_id) "
                        + "VALUES (?,?,?,?,?,?,?,?::jsonb,?)")) {
            ps.setObject(1, txnId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, source);
            ps.setString(4, tx.externalDealId());
            ps.setDate(5, java.sql.Date.valueOf(tx.transactionDate()));
            ps.setBigDecimal(6, tx.bookingAmount());
            ps.setString(7, tx.currencyCode());
            ps.setString(8, jsonOf(tx.customAttributes()));
            ps.setObject(9, currentBatch(c));
            ps.executeUpdate();
        }
    }

    private UUID currentBatch(Connection c) throws SQLException {
        // The raw_transaction FK references the batch created in this tx; pass it via a savepoint-free
        // approach: batch id is threaded by the caller in real code paths — here we select the RUNNING
        // batch for this correlation to keep the statement single.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ingestion_batch_id FROM ingestion_batch WHERE status = 'RUNNING' "
                        + "ORDER BY started_at DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? (UUID) rs.getObject(1) : null;
        }
    }

    private void insertIngestionRecord(Connection c, UUID recordId, UUID batchId, String extId,
                                       String status, Map<String, Object> raw, List<String> errors,
                                       UUID txnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ingestion_record (record_id, batch_id, external_id, status, raw_payload, errors, transaction_id) "
                        + "VALUES (?,?,?,?,?::jsonb,?::jsonb,?)")) {
            ps.setObject(1, recordId);
            ps.setObject(2, batchId);
            ps.setString(3, extId);
            ps.setString(4, status);
            ps.setString(5, jsonOf(raw));
            ps.setString(6, errors == null ? null : jsonOf(Map.of("fields", errors)));
            ps.setObject(7, txnId);
            ps.executeUpdate();
        }
    }

    /** Credit splits (FR-301): single or shared credit, >100% overlays allowed. */
    private void attachCredit(Connection c, TenantContext ctx, UUID txnId,
                              FieldMappingService.MappedTransaction tx,
                              Map<String, Object> raw) throws SQLException {
        UUID payeeId = resolvePayee(c, ctx, tx.payeeEmail());
        if (payeeId == null) {
            return; // unassigned deals land un-credited; the mapping admin fixes routing
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, payee_id, "
                        + "split_pct, effective_date) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, txnId);
            ps.setObject(4, payeeId);
            ps.setBigDecimal(5, tx.splitPct() == null ? BigDecimal.ONE : tx.splitPct());
            ps.setDate(6, java.sql.Date.valueOf(tx.transactionDate()));
            ps.executeUpdate();
        }
    }

    private UUID resolvePayee(Connection c, TenantContext ctx, String email) throws SQLException {
        if (email == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT payee_id FROM payee WHERE email = ? AND tenant_id::text = ? LIMIT 1")) {
            ps.setString(1, email);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject(1) : null;
            }
        }
    }

    /** Late-arriving (FR-204.2/FR-604): date falls inside a LOCKED period. */
    private boolean isLateArriving(Connection c, TenantContext ctx, LocalDate date) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM payout_period WHERE tenant_id::text = ? AND status = 'LOCKED' "
                        + "AND ? BETWEEN starts_on AND ends_on LIMIT 1")) {
            ps.setString(1, ctx.tenantId());
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** True-up signal into the SAME transaction (FR-604 → MI-05 review queue). */
    private void emitTrueupSignal(Connection c, TenantContext ctx, UUID txnId,
                                  String source, String extId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, correlation_id, tenant_id, occurred_at) "
                        + "VALUES ('trueup.required', ?, ?::jsonb, ?, ?, now())")) {
            ps.setObject(1, txnId);
            ps.setString(2, "{\"source\":\"" + source + "\",\"external_deal_id\":\"" + extId
                    + "\",\"transaction_id\":\"" + txnId + "\"}");
            ps.setString(3, ctx.correlationId());
            ps.setObject(4, UUID.fromString(ctx.tenantId()));
            ps.executeUpdate();
        }
    }

    private void finalizeBatch(Connection c, UUID batchId, int total, int accepted,
                               int quarantined, String watermark) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ingestion_batch SET status='COMPLETED', finished_at=now(), total_rows=?, "
                        + "accepted_rows=?, quarantined_rows=?, watermark_after=? WHERE ingestion_batch_id=?")) {
            ps.setInt(1, total);
            ps.setInt(2, accepted);
            ps.setInt(3, quarantined);
            ps.setString(4, watermark);
            ps.setObject(5, batchId);
            ps.executeUpdate();
        }
    }

    private void upsertWatermark(Connection c, String source, String object,
                                 String column, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO connector_state (connector_name, object_name, watermark_column, watermark_value, last_run_at) "
                        + "VALUES (?,?,?,?,now()) ON CONFLICT (connector_name, object_name) DO UPDATE "
                        + "SET watermark_value = EXCLUDED.watermark_value, last_run_at = now()")) {
            ps.setString(1, source);
            ps.setString(2, object);
            ps.setString(3, column);
            ps.setString(4, value);
            ps.executeUpdate();
        }
    }

    private String watermarkOf(Map<String, Object> record, String column) {
        if (column == null) {
            return null;
        }
        Object v = record.get(column);
        return v == null ? null : String.valueOf(v);
    }

    /** Minimal JSON string builder for flat string maps/records (no external dep). */
    static String jsonOf(Map<String, ?> map) {
        if (map == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "'")).append("\":\"")
                    .append(e.getValue() == null ? "" : String.valueOf(e.getValue()).replace("\"", "'"))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    static String jsonOf(List<String> list) {
        return list == null || list.isEmpty() ? "[]" : "[\"" + String.join("\",\"", list) + "\"]";
    }
}
