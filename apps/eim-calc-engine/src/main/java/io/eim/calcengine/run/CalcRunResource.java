package io.eim.calcengine.run;

import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.engine.Engine;
import io.eim.calcengine.engine.EngineModel;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calculation run API (TG3-CAL): execute a run against a published plan
 * version (wavefront-scheduled, atomically committed), synchronous sandbox
 * preview for the builder (FR-407 — nothing persisted), and the shadow
 * replay true-up path (FR-508). Every run is audited (CALCULATION_RUN) and
 * announced on eim.calc.run.completed.
 */
@Path("/api/v1/calc")
@Produces(MediaType.APPLICATION_JSON)
public class CalcRunResource {

    @Inject
    WavefrontRunner runner;

    @Inject
    ShadowReplay replay;

    @Inject
    AgroalDataSource ds;

    public record RunRequest(String tenantId, String payoutPeriodId, String planVersionHash,
                             String roundingMode) {
    }

    public record PreviewTxn(String date, String amount) {
    }

    public record PreviewRequest(EngineModel plan, List<PreviewTxn> transactions,
                                 Map<String, String> ctx, String roundingMode) {
    }

    @POST
    @Path("runs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(RunRequest request, @Context ContainerRequestContext req) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.payoutPeriodId())
                || isBlank(request.planVersionHash())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "tenantId, payoutPeriodId and planVersionHash are required"))
                    .build();
        }
        UUID runId = UUID.randomUUID();
        long start = System.nanoTime();
        try {
            WavefrontRunner.RunStats stats = runner.run(
                    UUID.fromString(request.tenantId()),
                    UUID.fromString(request.payoutPeriodId()),
                    request.planVersionHash(),
                    request.roundingMode());
            auditCalculationRun(request.tenantId(), "SUCCESS", stats.toString());
            return Response.accepted(stats).build();
        } catch (Exception e) {
            long dur = (System.nanoTime() - start) / 1_000_000;
            runner.recordFailure(runId, UUID.fromString(request.tenantId()),
                    UUID.fromString(request.payoutPeriodId()),
                    request.planVersionHash(), dur, String.valueOf(e.getMessage()));
            return Response.status(500)
                    .entity(Map.of("error", "calculation run failed: " + e.getMessage(),
                            "runId", runId.toString()))
                    .build();
        }
    }

    /** Sandbox preview (FR-407): inline DRAFT plan + synthetic deals,
     *  nothing persisted — the <800ms builder dock path (NFR-23). */
    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response preview(PreviewRequest request) {
        if (request == null || request.plan() == null || request.transactions() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "plan and transactions are required")).build();
        }
        long start = System.nanoTime();
        Engine engine = new Engine(request.plan(),
                "HALF_UP".equals(request.roundingMode())
                        ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN);
        List<Engine.Txn> txns = new ArrayList<>();
        for (PreviewTxn t : request.transactions()) {
            txns.add(new Engine.Txn("preview-" + txns.size(),
                    LocalDate.parse(t.date()), "1970-01-01T00:00:00Z",
                    new BigDecimal(t.amount())));
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        if (request.ctx() != null) {
            ctx.putAll(request.ctx());
        }
        ctx.putIfAbsent("period_start", "2026-04-01");
        ctx.putIfAbsent("period_end", "2026-04-30");
        ctx.putIfAbsent("period_days", "30");
        ctx.putIfAbsent("active_days", "30");
        Engine.PayeeResult r = engine.evaluate("preview", txns, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("components", r.components().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().toPlainString())));
        out.put("lines", r.lines());
        out.put("trace", r.trace());
        out.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
        return Response.ok(out).build();
    }

    public record ReplayRequest(String tenantId, String lockedPeriodId, String openPeriodId,
                                String planVersionHash) {
    }

    @POST
    @Path("shadow-replay")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response shadowReplay(ReplayRequest request) {
        if (request == null || isBlank(request.lockedPeriodId()) || isBlank(request.openPeriodId())
                || isBlank(request.planVersionHash()) || isBlank(request.tenantId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error",
                            "tenantId, lockedPeriodId, openPeriodId and planVersionHash are required"))
                    .build();
        }
        try {
            ShadowReplay.ReplayResult result = replay.replay(
                    UUID.fromString(request.tenantId()),
                    UUID.fromString(request.lockedPeriodId()),
                    UUID.fromString(request.openPeriodId()),
                    request.planVersionHash());
            return Response.accepted(Map.of("diffs", result.diffs(), "lines", result.lines())).build();
        } catch (Exception e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "shadow replay failed: " + e.getMessage())).build());
        }
    }

    private void auditCalculationRun(String tenantId, String outcome, String detail) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                             + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                             + "correlation_id, source_channel, outcome, occurred_at) "
                             + "VALUES (?::uuid,'calc-engine','SYSTEM','CALCULATION_RUN','calc_run',"
                             + "'runtime',NULL,?::jsonb,NULL,'calculation run',"
                             + "gen_random_uuid()::text,'KAFKA',?,now())")) {
            ps.setString(1, tenantId);
            ps.setString(2, "{\"detail\":\"" + detail.replace("\"", "'") + "\"}");
            ps.setString(3, outcome);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "audit write failed: " + e.getMessage())).build());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
