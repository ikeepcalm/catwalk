package dev.ua.ikeepcalm.catwalk.hub.webserver;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
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
                            <li>📄 <a href="/openapi.json">OpenAPI Specification</a> - Raw API spec</li>
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