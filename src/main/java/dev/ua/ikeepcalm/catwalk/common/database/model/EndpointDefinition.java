package dev.ua.ikeepcalm.catwalk.common.database.model;

import io.javalin.openapi.HttpMethod;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class EndpointDefinition {
    private String path;
    private List<HttpMethod> methods;
    private String summary;
    private String description;
    private List<String> tags;
    private boolean requiresAuth;
    private Map<String, Object> parameters;
    private Map<String, Object> requestBody;
    private Map<String, Object> responses;
}
