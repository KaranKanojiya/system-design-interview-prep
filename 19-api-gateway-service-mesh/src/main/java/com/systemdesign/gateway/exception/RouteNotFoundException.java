package com.systemdesign.gateway.exception;

/**
 * Thrown when no route matches the incoming request path.
 *
 * Flow: HttpRequest → RequestRouter.match() → empty → RouteNotFoundException → 404
 */
public class RouteNotFoundException extends GatewayException {

    private final String path; // the unmatched request path

    public RouteNotFoundException(String path) {
        super("No route found for path: " + path);
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String getMessage() {
        return "No route found for path: " + path;
    }
}
