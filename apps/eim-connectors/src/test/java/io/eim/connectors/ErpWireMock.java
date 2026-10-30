package io.eim.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * WireMock ERP stubs (TG7-ING-1): NetSuite SuiteQL + SAP OData read
 * endpoints. No credential literals anywhere — tokens ride the request.
 */
public class ErpWireMock implements QuarkusTestResourceLifecycleManager {

    private WireMockServer netSuite;
    private WireMockServer sap;

    @Override
    public Map<String, String> start() {
        netSuite = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        netSuite.start();
        netSuite.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/query/v1/suiteql.*"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":["
                                + "{\"contract_ref\":\"CTR-001\",\"period_key\":\"2026-08\","
                                + "\"revenue_amount\":60000,\"customer\":\"Acme\",\"subsidiary\":\"EIM-NA\"},"
                                + "{\"contract_ref\":\"CTR-001\",\"period_key\":\"2026-09\","
                                + "\"revenue_amount\":40000,\"customer\":\"Acme\",\"subsidiary\":\"EIM-NA\"}"
                                + "]}")));
        sap = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        sap.start();
        sap.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/api/v2/ContractHeaders.*"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"value\":[{\"ContractRef\":\"CTR-001\",\"Customer\":\"Acme\","
                                + "\"Currency\":\"USD\",\"StartDate\":\"2026-08-01\","
                                + "\"EndDate\":\"2027-07-31\"}]}")));
        return Map.of("quarkus.rest-client.netsuite.uri", netSuite.baseUrl(),
                      "quarkus.rest-client.sap.uri", sap.baseUrl());
    }

    @Override
    public void stop() {
        if (netSuite != null) {
            netSuite.stop();
        }
        if (sap != null) {
            sap.stop();
        }
    }
}
