package io.eim.connectors.connector;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * ERP read contracts (TG7-ING-1, FR-206.1): bookings/contracts from
 * NetSuite (SuiteQL via REST API) and SAP (OData). RegisterRestClient binds
 * per-config-key URIs — the real tenant ERP in production, WireMock stubs in
 * tests. Credentials arrive as call-time parameters from the secret store
 * (NFR-53: never literals in source).
 */
public interface ErpClient {

    /** NetSuite SuiteQL: contract lines with recognized revenue per period. */
    @Path("/")
    @RegisterRestClient(configKey = "netsuite")
    interface NetSuite extends ErpClient {

        @GET
        @Path("query/v1/suiteql")
        @Produces(MediaType.JSON)
        SuiteQlResponse suiteql(@HeaderParam("Authorization") String bearer,
                                @QueryParam("q") String suiteQl);

        record SuiteQlResponse(List<SuiteQlRow> items) {
        }

        record SuiteQlRow(String contract_ref, String period_key, Number revenue_amount,
                          String customer, String subsidiary) {
        }
    }

    /** SAP OData: ContractHeaders with billing elements. */
    @Path("/")
    @RegisterRestClient(configKey = "sap")
    interface Sap extends ErpClient {

        @GET
        @Path("api/v2/ContractHeaders")
        @Produces(MediaType.JSON)
        OdataResponse contracts(@HeaderParam("Authorization") String bearer,
                                @QueryParam("$filter") String filter);

        record OdataResponse(List<OdataRow> value) {
        }

        record OdataRow(String ContractRef, String Customer, String Currency,
                        String StartDate, String EndDate) {
        }
    }

    final class MediaType {
        public static final String JSON = "application/json";
    }
}
