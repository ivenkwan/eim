package io.eim.coreapi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps {@link ConfigChangePersistenceException} to a generic 500 using the existing ErrorResponse
 * shape. SQL text, constraint names, and stack details are never placed in the body. Registered as
 * a CDI bean so Quarkus discovers the {@link ServerExceptionMapper} method.
 */
@ApplicationScoped
public class ConfigChangeExceptionMapper {

  private static final Logger LOG = Logger.getLogger(ConfigChangeExceptionMapper.class);

  @ServerExceptionMapper
  public Response mapPersistenceFailure(ConfigChangePersistenceException exception) {
    LOG.error("Configuration change persistence failure", exception);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity(new ConfigChangeResource.ErrorResponse("Configuration change could not be persisted"))
        .build();
  }
}
