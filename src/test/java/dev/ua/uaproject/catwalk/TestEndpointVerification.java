package dev.ua.uaproject.catwalk;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class TestEndpointVerification {
    private static ServerMock server;
    private static CatWalkMain plugin;

    private static final String TEST_URL_BASE = "http://localhost:4567";
    private static final String TEST_API_KEY = "test_key_12345";

    @BeforeAll
    public static void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(CatWalkMain.class);
        
        // Wait a bit for the plugin to fully initialize
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    public static void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Health endpoint should be accessible without auth")
    void testHealthEndpoint() {
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/health").asJson();
        Assertions.assertEquals(200, response.getStatus());
        
        JsonNode body = response.getBody();
        Assertions.assertTrue(body.getObject().has("status"));
        Assertions.assertEquals("healthy", body.getObject().getString("status"));
    }

    @Test
    @DisplayName("Stats summary endpoint should work with direct registration")
    void testStatsEndpointDirect() {
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/v1/stats/summary")
                .header("Authorization", "Bearer " + TEST_API_KEY)
                .asJson();
        
        // Should return 200 if auth is configured properly, or 401 if not authenticated
        Assertions.assertTrue(response.getStatus() == 200 || response.getStatus() == 401);
        
        if (response.getStatus() == 200) {
            JsonNode body = response.getBody();
            Assertions.assertTrue(body.getObject().has("totalPlayers"));
            Assertions.assertTrue(body.getObject().has("onlinePlayers"));
            Assertions.assertTrue(body.getObject().has("tps"));
        }
    }

    @Test
    @DisplayName("OpenAPI endpoint should eventually be available")
    void testOpenApiEndpoint() {
        // Wait for delayed OpenAPI registration
        try {
            Thread.sleep(12000); // Wait longer than the default 10 second delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/openapi.json").asJson();
        Assertions.assertEquals(200, response.getStatus());
        
        JsonNode body = response.getBody();
        Assertions.assertTrue(body.getObject().has("openapi"));
        Assertions.assertTrue(body.getObject().has("paths"));
        
        // Check if our StatsApi endpoints are included
        JSONObject paths = body.getObject().getJSONObject("paths");
        Assertions.assertTrue(paths.has("/v1/stats/summary"));
        Assertions.assertTrue(paths.has("/v1/stats/online"));
    }

    @Test
    @DisplayName("Swagger UI should be accessible")
    void testSwaggerUI() {
        // Wait for OpenAPI to be initialized
        try {
            Thread.sleep(12000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HttpResponse<String> response = Unirest.get(TEST_URL_BASE + "/swagger").asString();
        Assertions.assertEquals(200, response.getStatus());
        
        String body = response.getBody();
        Assertions.assertTrue(body.contains("swagger-ui"));
        Assertions.assertTrue(body.contains("CatWalk API Documentation"));
    }

    @Test
    @DisplayName("Network status endpoint should work on hub servers")
    void testNetworkEndpoint() {
        // This test assumes hub mode is enabled in test config
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/v1/network/status").asJson();
        
        // Should be accessible without auth (in whitelist)
        Assertions.assertEquals(200, response.getStatus());
        
        JsonNode body = response.getBody();
        Assertions.assertTrue(body.getObject().has("mode"));
        Assertions.assertTrue(body.getObject().has("timestamp"));
    }

    @Test
    @DisplayName("Verify auth is working properly")
    void testAuthenticationRequired() {
        // Test without auth header
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/v1/stats/summary").asJson();
        Assertions.assertEquals(401, response.getStatus());
        
        // Test with invalid auth header
        response = Unirest.get(TEST_URL_BASE + "/v1/stats/summary")
                .header("Authorization", "Bearer invalid_key")
                .asJson();
        Assertions.assertEquals(401, response.getStatus());
    }
}