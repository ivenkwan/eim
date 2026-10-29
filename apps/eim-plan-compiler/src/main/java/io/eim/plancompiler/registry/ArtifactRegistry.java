package io.eim.plancompiler.registry;

import io.agroal.api.AgroalDataSource;
import io.eim.plancompiler.common.TenantContext;
import io.eim.plancompiler.compile.CompileResult;
import io.eim.plancompiler.model.PlanModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable artifact registry (TG3-RUL-6, NFR-62): every publish appends
 * one row keyed by the canonical plan-version hash. The hash covers the
 * canonical JSON (sorted keys) so semantically equal plans are idempotent
 * and different versions coexist. Each publish carries a PLAN_PUBLISH
 * audit row + eim.plan.published outbox event (FR-612/613).
 */
@ApplicationScoped
public class ArtifactRegistry {

    private static final com.fasterxml.jackson.databind.ObjectMapper MODEL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String modelJsonOf(PlanModel plan) {
        try {
            return MODEL_JSON.writeValueAsString(plan);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("plan model serialization failed", e);
        }
    }

    @Inject
    AgroalDataSource ds;

    public record Published(String planVersionHash, String planName, boolean newlyPublished) {
    }

    public record Stored(String planName, String drl, String helperJava, String dmn, String publishedAt) {
    }

    /** Canonical JSON: fields in fixed order, components and parameters
     *  sorted by name - the version identity (NFR-62). */
    public static String canonicalJson(PlanModel plan) {
        StringBuilder sb = new StringBuilder("{\"components\":[");
        java.util.List<PlanModel.Component> cs = plan.components() == null
                ? java.util.List.of() : plan.components().stream()
                        .sorted(java.util.Comparator.comparing(PlanModel.Component::name))
                        .toList();
        for (int i = 0; i < cs.size(); i++) {
            PlanModel.Component c = cs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"formula\":\"").append(c.formula() == null ? "" : c.formula())
                    .append("\",\"kind\":\"").append(c.kind())
                    .append("\",\"name\":\"").append(c.name())
                    .append("\",\"outputType\":\"").append(c.outputType()).append('\"');
            java.util.List<PlanModel.Parameter> ps = c.parameters() == null
                    ? java.util.List.of() : c.parameters().stream()
                            .sorted(java.util.Comparator.comparing(PlanModel.Parameter::name))
                            .toList();
            sb.append(",\"parameters\":{");
            for (int j = 0; j < ps.size(); j++) {
                PlanModel.Parameter par = ps.get(j);
                if (j > 0) {
                    sb.append(',');
                }
                sb.append('\"').append(par.name()).append("\":\"")
                        .append(par.type()).append('=').append(par.value()).append('\"');
            }
            sb.append('}');
            if (c.tiers() != null && c.tiers().brackets() != null) {
                sb.append(",\"tiers\":\"mode=").append(c.tiers().mode())
                        .append(";basis=").append(c.tiers().basis()).append(';');
                for (PlanModel.Bracket b : c.tiers().brackets()) {
                    sb.append(b.upTo() == null ? "*" : b.upTo()).append('@')
                            .append(b.rate()).append(';');
                }
                sb.append('"');
            }
            sb.append('}');
        }
        sb.append("],\"planName\":\"")
                .append(plan.planName() == null ? "" : plan.planName())
                .append("\"}");
        return sb.toString();
    }

    public static String hashOf(String canonicalJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Published publish(TenantContext ctx, PlanModel plan, String canonicalJson,
                             CompileResult compiled) throws SQLException {
        String hash = hashOf(canonicalJson);
        boolean inserted;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, "
                             + "plan_json, model_json, drl, helper_java, dmn) "
                             + "VALUES (?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?) "
                             + "ON CONFLICT (plan_version_hash) DO NOTHING")) {
            ps.setString(1, hash);
            ps.setObject(2, java.util.UUID.fromString(ctx.tenantId()));
            ps.setString(3, plan.planName() == null ? "unnamed" : plan.planName());
            ps.setString(4, canonicalJson);
            ps.setString(5, modelJsonOf(plan));
            ps.setString(6, compiled.drl());
            ps.setString(7, compiled.helperJava());
            ps.setString(8, compiled.dmn());
            inserted = ps.executeUpdate() > 0;
        }
        if (inserted) {
            auditPublish(ctx, plan, hash, compiled);
        }
        return new Published(hash, plan.planName(), inserted);
    }

    private void auditPublish(TenantContext ctx, PlanModel plan, String hash,
                              CompileResult compiled) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                            + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                            + "correlation_id, source_channel, outcome, occurred_at) "
                            + "VALUES (?,?,?,?,?,?,NULL,?::jsonb,NULL,'plan publish',?,?,'SUCCESS',now())")) {
                ps.setObject(1, java.util.UUID.fromString(ctx.tenantId()));
                ps.setString(2, ctx.actor());
                ps.setString(3, "USER");
                ps.setString(4, "PLAN_PUBLISH");
                ps.setString(5, "plan_version");
                ps.setString(6, hash);
                ps.setString(7, "{\"plan\":\"" + plan.planName()
                        + "\",\"components\":" + compiled.topological().size()
                        + ",\"version_hash\":\"" + hash + "\"}");
                ps.setString(8, ctx.correlationId());
                ps.setString(9, "API");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, "
                            + "correlation_id, tenant_id, occurred_at) "
                            + "VALUES ('plan.published', ?, ?::jsonb, ?, ?, now())")) {
                ps.setString(1, hash);
                ps.setString(2, "{\"plan\":\"" + plan.planName()
                        + "\",\"version_hash\":\"" + hash + "\"}");
                ps.setString(3, ctx.correlationId());
                ps.setObject(4, java.util.UUID.fromString(ctx.tenantId()));
                ps.executeUpdate();
            }
        }
    }

    public Stored fetch(String hash) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plan_name, drl, helper_java, dmn, published_at::text "
                             + "FROM plan_artifact WHERE plan_version_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5))
                        : null;
            }
        }
    }
}
