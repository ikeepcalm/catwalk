package dev.ua.ikeepcalm.catwalk.common.utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiProperty;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiSchema;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeEventHandler;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import io.javalin.openapi.ContentType;
import io.javalin.openapi.NULL_CLASS;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiAnnotationsKt;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenApiSchemas {

    public static List<EndpointDefinition> endpoints(Object handlerInstance) {
        List<EndpointDefinition> endpoints = new ArrayList<>();
        for (Method method : handlerInstance.getClass().getDeclaredMethods()) {
            for (OpenApi api : method.getAnnotationsByType(OpenApi.class)) {
                endpoints.add(endpoint(api, method.getAnnotation(BridgeEventHandler.class)));
            }
        }
        return endpoints;
    }

    public static EndpointDefinition endpoint(OpenApi api, BridgeEventHandler handler) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (api.queryParams().length > 0) {
            parameters.put("query", Arrays.stream(api.queryParams()).map(p -> parameter(p, "query", p.required())).toList());
        }
        if (api.pathParams().length > 0) {
            parameters.put("path", Arrays.stream(api.pathParams()).map(p -> parameter(p, "path", true)).toList());
        }

        return EndpointDefinition.builder()
                .path(api.path())
                .methods(List.of(api.methods()))
                .summary(text(api.summary()))
                .description(text(api.description()))
                .tags(List.of(api.tags()))
                .requiresAuth(handler == null || handler.requiresAuth())
                .scopes(handler != null ? List.of(handler.scopes()) : List.of())
                .rateLimit(handler != null ? handler.rateLimit() : 0)
                .logRequests(handler != null && handler.logRequests())
                .parameters(parameters)
                .requestBody(api.requestBody().content().length > 0 ? requestBody(api.requestBody()) : null)
                .responses(responses(api.responses()))
                .build();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> operation(EndpointDefinition endpoint) {
        Map<String, Object> operation = new LinkedHashMap<>();
        putIfPresent(operation, "summary", endpoint.getSummary());
        putIfPresent(operation, "description", endpoint.getDescription());
        if (endpoint.getTags() != null && !endpoint.getTags().isEmpty()) {
            operation.put("tags", endpoint.getTags());
        }

        List<Object> parameters = new ArrayList<>();
        if (endpoint.getParameters() != null) {
            parameters.addAll((List<Object>) endpoint.getParameters().getOrDefault("query", List.of()));
            parameters.addAll((List<Object>) endpoint.getParameters().getOrDefault("path", List.of()));
        }
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }

        putIfPresent(operation, "requestBody", endpoint.getRequestBody());
        operation.put("responses", endpoint.getResponses() == null || endpoint.getResponses().isEmpty()
                ? Map.of("200", Map.of("description", "Successful operation"))
                : endpoint.getResponses());
        return operation;
    }

    public static Map<String, Object> schema(Class<?> type) {
        ApiSchema apiSchema = type.getAnnotation(ApiSchema.class);
        if (apiSchema != null) {
            return annotatedSchema(apiSchema);
        }
        if (type.isArray()) {
            return Map.of("type", "array", "items", schema(type.getComponentType()));
        }
        String openApiType = openApiType(type);
        if (!openApiType.equals("object")) {
            return Map.of("type", openApiType);
        }
        return reflectedSchema(type);
    }

    public static String openApiType(Class<?> type) {
        if (type == String.class || type == char.class || type == Character.class) return "string";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class) return "integer";
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || Collection.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    private static Map<String, Object> parameter(OpenApiParam param, String in, boolean required) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", param.name());
        parameter.put("in", in);
        parameter.put("required", required);
        putIfPresent(parameter, "description", text(param.description()));
        parameter.put("schema", Map.of("type", openApiType(param.type())));
        return parameter;
    }

    private static Map<String, Object> requestBody(OpenApiRequestBody body) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        putIfPresent(requestBody, "description", text(body.description()));
        requestBody.put("required", body.required());
        requestBody.put("content", content(body.content()));
        return requestBody;
    }

    private static Map<String, Object> responses(OpenApiResponse[] responses) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (OpenApiResponse response : responses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String description = text(response.description());
            entry.put("description", description != null ? description : "Response " + response.status());
            if (response.content().length > 0) {
                entry.put("content", content(response.content()));
            }
            result.put(response.status(), entry);
        }
        return result;
    }

    private static Map<String, Object> content(OpenApiContent[] items) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (OpenApiContent item : items) {
            Map<String, Object> mediaType = new LinkedHashMap<>();
            mediaType.put("schema", contentSchema(item));
            String example = text(item.example());
            if (example != null) {
                mediaType.put("example", example(example));
            }
            String mimeType = item.mimeType().equals(ContentType.AUTODETECT) ? "application/json" : item.mimeType();
            content.put(mimeType, mediaType);
        }
        return content;
    }

    private static Map<String, Object> contentSchema(OpenApiContent item) {
        try {
            if (item.from() != NULL_CLASS.class) {
                return schema(item.from());
            }
        } catch (TypeNotPresentException e) {
            CatWalkLogger.warn("OpenAPI content class is not available: %s", e.typeName());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", text(item.type()) != null ? item.type() : "object");
        putIfPresent(schema, "format", text(item.format()));
        return schema;
    }

    private static Map<String, Object> annotatedSchema(ApiSchema apiSchema) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        putIfPresent(schema, "description", text(apiSchema.description()));

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ApiProperty property : apiSchema.properties()) {
            Map<String, Object> propertySchema = new LinkedHashMap<>();
            propertySchema.put("type", property.type());
            putIfPresent(propertySchema, "description", text(property.description()));
            putIfPresent(propertySchema, "example", text(property.example()));
            properties.put(property.name(), propertySchema);
            if (property.required()) {
                required.add(property.name());
            }
        }
        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> reflectedSchema(Class<?> type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                    || field.isSynthetic() || field.isAnnotationPresent(JsonIgnore.class)) {
                continue;
            }
            JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
            String name = jsonProperty != null && !jsonProperty.value().isEmpty() ? jsonProperty.value() : field.getName();
            properties.put(name, Map.of("type", openApiType(field.getType())));
            if (field.getType().isPrimitive()) {
                required.add(name);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Object example(String example) {
        try {
            return JsonParser.parseString(example);
        } catch (JsonParseException e) {
            return example;
        }
    }

    private static String text(String value) {
        return value == null || value.isEmpty() || value.equals(OpenApiAnnotationsKt.NULL_STRING) ? null : value;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
