package dev.ua.uaproject.catwalk.hub.webserver;

import dev.ua.uaproject.catwalk.CatWalkMain;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;

import java.util.function.Consumer;
import java.util.logging.Logger;

public final class WebServerRoutes {

    private WebServerRoutes() {
    }

    public static void addBasicRoutes(CatWalkMain main, Logger log, WebServer webServer) {
        webServer.get("/", ctx -> {
            ctx.html("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>CatWalk API Server</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 40px; }
                            .header { color: #2c3e50; }
                            .info { background: #ecf0f1; padding: 15px; border-radius: 5px; margin: 20px 0; }
                            a { color: #3498db; text-decoration: none; }
                            a:hover { text-decoration: underline; }
                            ul { list-style-type: none; padding: 0; }
                            li { margin: 10px 0; }
                        </style>
                    </head>
                    <body>
                        <h1 class="header">CatWalk REST API Server</h1>
                        <div class="info">
                            <strong>Server Mode:</strong> %s<br>
                            <strong>Server ID:</strong> %s<br>
                            <strong>Version:</strong> %s
                        </div>
                        <h2>API Documentation</h2>
                        <ul>
                            <li>📊 <a href="/swagger">Swagger UI</a> - Interactive API explorer</li>
                            <li>📖 <a href="/overview">Overview</a> - All endpoints</li>
                            <li>📄 <a href="/openapi.json">OpenAPI Specification</a> - Raw API spec</li>
                        </ul>
                        <h2>API Endpoints</h2>
                        <ul>
                            <li>📈 <a href="/v1/stats/summary">Server Stats</a> - Current server statistics</li>
                            <li>👥 <a href="/v1/stats/online">Online Players</a> - Player activity data</li>
                            <li>🏆 <a href="/v1/stats/topplayers">Top Players</a> - Most active players</li>
                            <li>⏰ <a href="/v1/stats/hourly">Hourly Stats</a> - Player distribution by hour</li>
                        </ul>
                        %s
                    </body>
                    </html>
                    """.formatted(
                    main.isHubMode() ? "Hub Gateway" : "Backend Server",
                    main.getServerId(),
                    main.getPluginMeta().getVersion(),
                    main.isHubMode() ?
                            "<h2>Network Endpoints</h2><ul><li>🌐 <a href=\"/v1/network/status\">Network Status</a></li><li>🖥️ <a href=\"/v1/network/servers\">Network Servers</a></li></ul>" :
                            ""
            ));
        });

        webServer.get("/health", ctx -> {
            ctx.json(java.util.Map.of(
                    "status", "healthy",
                    "timestamp", System.currentTimeMillis(),
                    "server", main.getServerId(),
                    "mode", main.isHubMode() ? "hub" : "backend"
            ));
        });

        log.info("Basic routes registered");
    }

    // NEW - Register hub routes with proper OpenAPI
    public static void addHubRoutes(CatWalkMain main, Logger log, WebServer webServer) {
        log.info("Basic routes registered");

        webServer.get("/v1/network/status", ctx -> {
            java.util.Map<String, Object> status = new java.util.HashMap<>();
            status.put("hubServer", main.getServerId());
            status.put("mode", "Hub Gateway");
            status.put("timestamp", System.currentTimeMillis());
            status.put("status", "active");
            ctx.json(status);
        });

        webServer.get("/v1/network/servers", ctx -> {
            java.util.Map<String, Object> servers = new java.util.HashMap<>();
            servers.put("hub", main.getServerId());
            servers.put("totalServers", 1);
            servers.put("activeServers", 1);
            ctx.json(servers);
        });

        log.info("Hub routes registered");
    }

    private record PrefixedRouteBuilder(String prefix, WebServer webServer) {

        public void get(String route, Handler handler) {
            webServer.get(prefix + "/" + route, handler);
        }

        public void post(String route, Handler handler) {
            webServer.post(prefix + "/" + route, handler);
        }

        public void put(String route, Handler handler) {
            webServer.put(prefix + "/" + route, handler);
        }

        public void delete(String route, Handler handler) {
            webServer.delete(prefix + "/" + route, handler);
        }

        public void ws(String route, Consumer<WsConfig> wsConfig) {
            webServer.ws(prefix + "/" + route, wsConfig);
        }
    }
}