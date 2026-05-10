package com.systemdesign.gateway.model;

import java.util.Optional;

/**
 * Interface for a single filter in the gateway's filter chain.
 *
 * Filters are executed in order. Each filter can:
 *   - Return Optional.empty() to continue to the next filter
 *   - Return an HttpResponse to short-circuit the chain (e.g., 401, 429)
 *
 * Flow: RequestContext → filter1 → filter2 → ... → filterN → upstream call
 *       Any filter can short-circuit by returning a response.
 */
@FunctionalInterface
public interface GatewayFilter {

    /**
     * Processes the request context.
     *
     * @param ctx the request context flowing through the pipeline
     * @return empty to continue the chain, or a response to short-circuit
     */
    Optional<HttpResponse> filter(RequestContext ctx);
}
