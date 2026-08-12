package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An HTTP endpoint a service exposes, catalogued from {@code conf/routes} (FR-3.4). Used for edge
 * labelling and the inspector panel; never for edge creation.
 *
 * @param method HTTP verb as written in the routes file
 * @param path   route path, including Play's {@code :param} and {@code *wildcard} segments
 * @param handler the controller method the route points at
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Endpoint(String method, String path, String handler) {

    public String signature() {
        return method + " " + path;
    }
}
