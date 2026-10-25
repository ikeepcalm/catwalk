package dev.ua.ikeepcalm.catwalk.webserver;

import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.utils.OpenApiSchemas;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonSingleton;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiAnnotationsKt;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiSchemasTest {

    static class Player {
        private String name;
        private int level;
    }

    static class Handlers {
        @OpenApi(path = "/players/{name}", methods = HttpMethod.GET, tags = "Players",
                pathParams = @OpenApiParam(name = "name"),
                responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = Player.class)))
        public void player() {
        }

        @OpenApi(path = "/a", methods = HttpMethod.POST)
        @OpenApi(path = "/b", methods = HttpMethod.POST)
        public void repeated() {
        }
    }

    @Test
    void readsEveryAnnotationWithoutLeakingPlaceholders() {
        List<EndpointDefinition> endpoints = OpenApiSchemas.endpoints(new Handlers());
        assertEquals(3, endpoints.size());

        EndpointDefinition player = endpoints.stream().filter(e -> e.getPath().equals("/players/{name}")).findFirst().orElseThrow();
        String operation = GsonSingleton.getInstance().toJson(OpenApiSchemas.operation(player));

        assertFalse(operation.contains(OpenApiAnnotationsKt.NULL_STRING));
        assertFalse(operation.contains("AUTODETECT"));
        assertTrue(operation.contains("\"application/json\""));
        assertTrue(operation.contains("\"level\":{\"type\":\"integer\"}"));
        assertTrue(operation.contains("\"in\":\"path\""));
    }
}
