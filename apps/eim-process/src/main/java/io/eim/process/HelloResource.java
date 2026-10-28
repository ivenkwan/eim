package io.eim.process;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Read-only liveness greeting; contract lives in openapi/process.yaml */
@Path("/api/v1/hello")
public class HelloResource {

    public record HelloResponse(String service, String status, String message) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("eim-process", "ok", "EIM eim-process skeleton (TG-0)");
    }
}
