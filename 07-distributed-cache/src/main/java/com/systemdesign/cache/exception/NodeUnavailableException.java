package com.systemdesign.cache.exception;

/**
 * NodeUnavailableException — Thrown when the target cache node is down or unreachable.
 *
 * In a distributed cache, nodes can fail at any time (hardware failure, network partition,
 * deployment rolling restart). This exception signals that the routing layer couldn't find
 * a healthy node to handle the request.
 */
public class NodeUnavailableException extends CacheException {

    private final String nodeId;

    public NodeUnavailableException(String nodeId) {
        super("Cache node unavailable: '" + nodeId + "'");
        this.nodeId = nodeId;
    }

    public NodeUnavailableException(String nodeId, String message) {
        super("Cache node '" + nodeId + "' unavailable: " + message);
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
