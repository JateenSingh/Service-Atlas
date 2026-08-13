package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An HTTP endpoint a service exposes, catalogued from {@code conf/routes} (FR-3.4). Used for edge
 * labelling and the inspector panel; never for edge creation.
 *
 * @param method HTTP verb as written in the routes file
 * @param path   route path, including Play's {@code :param} and {@code *wildcard} segments
 * @param handler the controller method the route points at
 * @param deprecated whether this endpoint is marked as deprecated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Endpoint(String method, String path, String handler, Boolean deprecated) {

    public Endpoint(String method, String path, String handler) {
        this(method, path, handler, null);
    }

    public String signature() {
        return method + " " + path;
    }

    public boolean isDeprecated() {
        return deprecated != null && deprecated;
    }
}
