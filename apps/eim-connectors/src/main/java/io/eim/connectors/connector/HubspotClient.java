package io.eim.connectors.connector;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/**
 * HubSpot CRM v3 REST contract. RegisterRestClient binds to
 * quarkus.rest-client.hubspot.uri (real portal in prod, WireMock in tests).
 * Private-app access token supplied at call time from the secret store —
 * never a literal in source.
 */
@Path("/")
@RegisterRestClient(configKey = "hubspot")
@Produces(MediaType.APPLICATION_JSON)
public interface HubspotClient {

    @GET
    @Path("crm/v3/objects/deals")
    DealsPage deals(@HeaderParam("Authorization") String bearer,
                    @QueryParam("limit") int limit,
                    @QueryParam("after") String after);

    /** One HubSpot deal: id + its property map. */
    record Deal(String id, Map<String, Object> properties) {
    }

    /** One page of deals; paging.info.nextAfter is flattened by the stub. */
    record DealsPage(List<Deal> results, String nextAfter) {
    }
}
