package io.eim.process.common;

/** Request-scoped identity for the process service. */
public record TenantContext(String tenantId, String actor, String correlationId) {
}
