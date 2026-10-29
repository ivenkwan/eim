package io.eim.connectors;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.util.Map;

/**
 * WireMock SFDC stub (TG2-ING-2): token endpoint + SOQL query endpoint.
 * Credentials ride the request from the secret store; the stub validates
 * only shapes — no credential literals live anywhere.
 */
public class SfdcWireMock implements QuarkusTestResourceLifecycleManager {

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/services/oauth2/token"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"stub-token\",\"instance_url\":\"" + server.baseUrl() + "\"}")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/services/data/v60.0/query"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fields\":[\"external_deal_id\",\"close_date\",\"amount\",\"currency\",\"payee_email\"],"
                                + "\"records\":[[\"SF-1\",\"2026-04-01\",\"42000\",\"USD\",\"ray@example.test\"]],"
                                + "\"totalSize\":1}")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/crm/v3/objects/deals?limit=100"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"results\":[{\"id\":\"HS-1\",\"properties\":{"
                                + "\"closedate\":\"1767225600000\",\"amount\":\"77000\","
                                + "\"currency\":\"USD\",\"payee_email\":\"ray@example.test\"}}],"
                                + "\"paging\":{\"next\":{\"after\":\"\"}}}")));
        return Map.of("quarkus.rest-client.sfdc.uri", server.baseUrl(),
                      "quarkus.rest-client.hubspot.uri", server.baseUrl());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
