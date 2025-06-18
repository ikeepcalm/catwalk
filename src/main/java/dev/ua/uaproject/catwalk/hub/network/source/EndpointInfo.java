package dev.ua.uaproject.catwalk.hub.network.source;

import io.javalin.openapi.HttpMethod;
import lombok.Data;

@Data
public class EndpointInfo {
    private String path;
    private HttpMethod[] methods;
    private String summary;
    private String[] tags;
    private boolean requiresAuth;
}