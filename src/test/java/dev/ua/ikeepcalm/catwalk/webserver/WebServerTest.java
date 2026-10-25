package dev.ua.ikeepcalm.catwalk.webserver;

import dev.ua.ikeepcalm.catwalk.hub.webserver.RouteAccess;
import dev.ua.ikeepcalm.catwalk.hub.webserver.WebServer;
import io.javalin.http.HandlerType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebServerTest {

    private static final String KEY = "test-master-key-123456";
    private static final String SCOPED_KEY = "test-scoped-key-123456";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static WebServer webServer;

    @BeforeAll
    static void start() {
        webServer = new WebServer(null, config(KEY));
        webServer.get("/players/{name}", ctx -> ctx.result("player " + ctx.pathParam("name")));
        webServer.get("/public/ping", ctx -> ctx.result("pong"));
        webServer.get("/blocked", ctx -> ctx.result("should not exist"));
        webServer.addRoute(HandlerType.GET, "/scoped", ctx -> ctx.result("scoped"), new RouteAccess(true, Set.of("players.read"), 0, false, false));
        webServer.addRoute(HandlerType.GET, "/limited", ctx -> ctx.result("ok"), new RouteAccess(false, Set.of(), 2, false, false));
        webServer.addRoute(HandlerType.POST, "/wipe", ctx -> ctx.result("wiped"));
        webServer.start();
        webServer.get("/late", ctx -> ctx.result("first"));
        webServer.get("/late", ctx -> ctx.result("second"));
        webServer.get("/health", ctx -> ctx.result("hijacked"));
    }

    private static YamlConfiguration config(String key) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("port", 0);
        config.set("server-url", "http://localhost");
        config.set("useKeyAuth", true);
        config.set("key", key);
        config.set("scoped-keys.bot.key", SCOPED_KEY);
        config.set("scoped-keys.bot.scopes", List.of("players.read"));
        config.set("corsOrigins", List.of("*"));
        config.set("whitelisted-paths", List.of("/public/*"));
        config.set("blocked-paths", List.of("/blocked"));
        config.set("disable-swagger", true);
        return config;
    }

    @AfterAll
    static void stop() {
        webServer.stop();
    }

    @Test
    void routesWithPathParamsRequireTheKey() throws Exception {
        assertEquals(401, get("/players/steve", null).statusCode());
        HttpResponse<String> response = get("/players/steve", KEY);
        assertEquals(200, response.statusCode());
        assertEquals("player steve", response.body());
    }

    @Test
    void whitelistedRoutesArePublic() throws Exception {
        assertEquals("pong", get("/public/ping", null).body());
    }

    @Test
    void routesAddedAfterStartReplaceEarlierOnesButNotCoreRoutes() throws Exception {
        assertEquals("second", get("/late", KEY).body());
        assertEquals(200, get("/health", null).statusCode());
        assertEquals(true, get("/health", null).body().contains("healthy"));
    }

    @Test
    void unknownAndBlockedRoutesAreNotFound() throws Exception {
        assertEquals(404, get("/missing", KEY).statusCode());
        assertEquals(404, get("/blocked", KEY).statusCode());
    }

    @Test
    void scopedKeysOnlyReachRoutesWithTheirScopes() throws Exception {
        assertEquals(200, get("/scoped", SCOPED_KEY).statusCode());
        assertEquals(200, get("/scoped", KEY).statusCode());
        assertEquals(403, get("/players/steve", SCOPED_KEY).statusCode());
    }

    @Test
    void rateLimitedRoutesReturn429() throws Exception {
        assertEquals(200, get("/limited", KEY).statusCode());
        assertEquals(200, get("/limited", KEY).statusCode());
        assertEquals(429, get("/limited", KEY).statusCode());
    }

    @Test
    void methodOverrideIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/wipe"))
                .header("Authorization", "Bearer " + KEY)
                .header("X-HTTP-Method-Override", "POST")
                .GET().build();
        assertEquals(400, CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/players/steve", KEY);
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
    }

    @Test
    void repeatedFailedLoginsLockTheClientOut() throws Exception {
        WebServer isolated = new WebServer(null, config(KEY));
        isolated.get("/players/{name}", ctx -> ctx.result("ok"));
        isolated.start();
        try {
            URI target = URI.create("http://localhost:" + isolated.getJavalin().port() + "/players/steve");
            for (int i = 0; i < 10; i++) {
                assertEquals(401, CLIENT.send(HttpRequest.newBuilder(target).header("Authorization", "Bearer wrong").build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(429, CLIENT.send(HttpRequest.newBuilder(target).header("Authorization", "Bearer " + KEY).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            isolated.stop();
        }
    }

    @Test
    void weakKeysAreRefused() {
        assertThrows(IllegalStateException.class, () -> new WebServer(null, config("short")));
    }

    private static HttpResponse<String> get(String path, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + webServer.getJavalin().port() + path);
    }
}
