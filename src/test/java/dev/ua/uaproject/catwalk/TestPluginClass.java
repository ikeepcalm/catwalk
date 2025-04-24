package dev.ua.uaproject.catwalk;


import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class TestPluginClass {
    private static ServerMock server;
    private static CatWalkMain plugin;

    private static final String TEST_URL_BASE = "http://localhost:4567";

    @BeforeAll
    public static void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(CatWalkMain.class);
    }

    @AfterAll
    public static void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Verify that auth is on")
    void verifyTestEnvironment() {
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/v1/players/all").asJson();
        Assertions.assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("swagger endpoint loads")
    void verifySwaggerUI() {
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/swagger").asJson();
        Assertions.assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("swagger-docs endpoint loads")
    void verifySwaggerDocs() {
        HttpResponse<JsonNode> response = Unirest.get(TEST_URL_BASE + "/swagger-docs").asJson();
        Assertions.assertEquals(200, response.getStatus());
    }
}
