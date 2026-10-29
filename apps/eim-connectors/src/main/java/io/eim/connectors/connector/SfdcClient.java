package io.eim.connectors.connector;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * SFDC REST contract. RegisterRestClient binds to quarkus.rest-client.sfdc.uri
 * (real org in prod, WireMock in tests). Token fields are form params —
 * supplied at call time from the secret store, never literals in source.
 */
@Path("/")
@RegisterRestClient(configKey = "sfdc")
@Produces(MediaType.APPLICATION_JSON)
public interface SfdcClient {

    @POST
    @Path("services/oauth2/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    TokenResponse token(@jakarta.ws.rs.FormParam("grant_type") String grantType,
                        @jakarta.ws.rs.FormParam("client_id") String clientId,
                        @jakarta.ws.rs.FormParam("client_secret") String clientSecret,
                        @jakarta.ws.rs.FormParam("username") String username,
                        @jakarta.ws.rs.FormParam("password") String password);

    @POST
    @Path("services/data/v60.0/query")
    @Consumes(MediaType.APPLICATION_JSON)
    QueryResponse query(@HeaderParam("Authorization") String bearer,
                        String soql);

    record TokenResponse(String access_token, String instance_url) {
        public String accessToken() {
            return access_token;
        }
    }

    /** records: rows of values in the order of {@link #fields}. */
    record QueryResponse(List<String> fields, List<List<Object>> records, int totalSize) {
    }
}
