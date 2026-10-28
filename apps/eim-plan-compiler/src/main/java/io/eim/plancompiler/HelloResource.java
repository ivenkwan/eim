package io.eim.plancompiler;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Read-only liveness greeting; contract lives in openapi/plan-compiler.yaml */
@Path("/api/v1/hello")
public class HelloResource {

    public record HelloResponse(String service, String status, String message) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("eim-plan-compiler", "ok", "EIM eim-plan-compiler skeleton (TG-0)");
    }
}
