package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.MDC;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RLS-protected seller view (exit criterion TG-1 #1): the session's tenant /
 * payee GUCs are set from the validated identity, and PostgreSQL row-level
 * security enforces the scope — application code cannot over-fetch even by
 * mistake (FR-103, NFR-52).
 */
@Path("/api/v1/me")
public class MeResource {

    @Inject
    AgroalDataSource ds;

    @GET
    @Path("/payee")
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Map<String, Object> myPayee(@Context jakarta.ws.rs.container.ContainerRequestContext request)
            throws SQLException {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actor", ctx == null ? "anonymous" : ctx.actor());
        result.put("correlation_id", MDC.get("correlation_id"));
        result.put("tenant_id", ctx == null ? "" : ctx.tenantId());

        try (Connection c = ds.getConnection()) {
            setSessionScope(c, ctx);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payee_id::text, full_name, role_title, base_currency, "
                            + "effective_start_date::text FROM payee "
                            + "WHERE payee_id::text = current_setting('app.current_payee_id', true) "
                            + "LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> payee = new LinkedHashMap<>();
                    payee.put("payee_id", rs.getString(1));
                    payee.put("full_name", rs.getString(2));
                    payee.put("role_title", rs.getString(3));
                    payee.put("base_currency", rs.getString(4));
                    payee.put("effective_start", rs.getString(5));
                    result.put("payee", payee);
                } else {
                    result.put("payee", null);
                }
            }
        }
        return result;
    }

    /**
     * Binds the transaction to the RLS-enforced app role with transaction-local
     * GUCs (revert automatically at commit — pooled connections stay clean).
     */
    static void setSessionScope(Connection c, TenantContext ctx) throws SQLException {
        try (PreparedStatement role = c.prepareStatement("SET LOCAL ROLE eim_app")) {
            role.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, true), "
                        + "set_config('app.current_payee_id', ?, true)")) {
            s.setString(1, ctx == null ? "" : ctx.tenantId());
            s.setString(2, ctx == null ? "" : ctx.payeeId());
            s.executeQuery();
        }
    }
}
