package io.eim.plancompiler.api;

import io.eim.plancompiler.common.TenantContext;
import io.eim.plancompiler.common.TenantContextFilter;
import io.eim.plancompiler.compile.CompileResult;
import io.eim.plancompiler.compile.PlanCompiler;
import io.eim.plancompiler.model.PlanModel;
import io.eim.plancompiler.registry.ArtifactRegistry;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plan compiler API (TG3-RUL): validate (type-check + Tarjan cycles +
 * tier checks, no persistence), publish (gate on a clean compile, then
 * append the immutable artifact set), artifact fetch and the
 * backward-chaining dispute triage preview.
 */
@Path("/api/v1/plans")
@Produces(MediaType.APPLICATION_JSON)
public class PlanResource {

    @Inject
    PlanCompiler compiler;

    @Inject
    ArtifactRegistry registry;

    public record Validation(boolean ok, Map<String, Object> report) {
    }

    @POST
    @Path("validate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response validate(PlanModel plan, @Context ContainerRequestContext request) {
        ctx(request);
        CompileResult compiled = compiler.compile(plan);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("problems", compiled.allProblems());
        report.put("topologicalOrder", compiled.topological());
        report.put("inferredTypes", compiled.inferredTypes());
        report.put("canonicalHash", ArtifactRegistry.hashOf(
                ArtifactRegistry.canonicalJson(plan)));
        return Response.ok(new Validation(compiled.ok(), report)).build();
    }

    @POST
    @Path("publish")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response publish(PlanModel plan, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        CompileResult compiled = compiler.compile(plan);
        if (!compiled.ok()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "plan failed the publish gate",
                            "problems", compiled.allProblems()))
                    .build();
        }
        try {
            String canonical = ArtifactRegistry.canonicalJson(plan);
            ArtifactRegistry.Published published = registry.publish(ctx, plan, canonical, compiled);
            return Response.accepted(Map.of(
                    "planVersionHash", published.planVersionHash(),
                    "planName", published.planName(),
                    "newlyPublished", published.newlyPublished(),
                    "topologicalOrder", compiled.topological())).build();
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "registry write failed: " + e.getMessage())).build());
        }
    }

    @GET
    @Path("{hash}/artifacts")
    public Response artifacts(@PathParam("hash") String hash,
                              @Context ContainerRequestContext request) {
        ctx(request);
        try {
            ArtifactRegistry.Stored stored = registry.fetch(hash);
            if (stored == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "unknown plan version")).build();
            }
            return Response.ok(stored).build();
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "registry read failed: " + e.getMessage())).build());
        }
    }

    private TenantContext ctx(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "tenant context missing")).build());
        }
        return ctx;
    }
}
