package dev.ua.uaproject.catwalk.hub.webserver;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.utils.json.GsonJsonMapper;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.openapi.OpenApi;
import io.javalin.websocket.WsConfig;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class WebServer {

    public static final String X_CATWALK_COOKIE = "x-catwalk-key";
    public static final String X_CATWALK_BEARER = "Bearer ";
    private static final String[] noAuthPaths = new String[]{"/swagger", "/openapi", "/redoc", "/plugins", "/health"};

    private final Logger log;
    @Getter
    private final Javalin javalin;

    private final boolean isDebug;

    private final List<String> blockedPaths;
    private final List<String> whitelistedPaths;

    private final boolean isAuthEnabled;
    private final boolean disableSwagger;
    private final boolean tlsEnabled;
    private final boolean sni;
    private final String keyStorePath;
    private final String keyStorePassword;
    private final String authKey;
    private final List<String> corsOrigin;
    private final int securePort;
    private final CatWalkMain main;

    @Getter
    private final CustomOpenApiGenerator openApiGenerator;

    public WebServer(CatWalkMain main, FileConfiguration bukkitConfig, Logger logger) {
        this.main = main;
        this.log = logger;
        this.isDebug = bukkitConfig.getBoolean("debug", false);
        this.blockedPaths = bukkitConfig.getStringList("blocked-paths");
        this.whitelistedPaths = bukkitConfig.getStringList("whitelisted-paths");
        this.isAuthEnabled = bukkitConfig.getBoolean("useKeyAuth", true);
        this.disableSwagger = bukkitConfig.getBoolean("disable-swagger", false);
        this.tlsEnabled = bukkitConfig.getBoolean("tls.enabled", false);
        this.sni = bukkitConfig.getBoolean("tls.sni", false);
        this.keyStorePath = bukkitConfig.getString("tls.keystore", "keystore.jks");
        this.keyStorePassword = bukkitConfig.getString("tls.keystorePassword", "");
        this.authKey = bukkitConfig.getString("key", "change_me");
        this.corsOrigin = bukkitConfig.getStringList("corsOrigins");
        this.securePort = bukkitConfig.getInt("port", 4567);

        // Initialize custom OpenAPI generator
        this.openApiGenerator = new CustomOpenApiGenerator(main);

        // Create Javalin instance
        this.javalin = Javalin.create(this::configureJavalin);

        setupAuthentication();
        setupOpenApiEndpoints();

        if (bukkitConfig.getBoolean("debug")) {
            this.javalin.before(ctx -> log.info(ctx.req().getPathInfo()));
        }
    }

    private void configureJavalin(JavalinConfig config) {
        config.jsonMapper(new GsonJsonMapper());
        config.http.defaultContentType = "application/json";
        config.showJavalinBanner = false;

        configureTLS(config);
        configureCors(config);

        if (isAuthEnabled && "change_me".equals(authKey)) {
            log.warning("AUTH KEY IS SET TO DEFAULT \"change_me\"");
            log.warning("CHANGE THE key IN THE config.yml FILE");
            log.warning("FAILURE TO CHANGE THE KEY MAY RESULT IN SERVER COMPROMISE");
        }
    }

    private void setupAuthentication() {
        this.javalin.beforeMatched(ctx -> {
            if (!isAuthEnabled) {
                return;
            }

            if (isNoAuthPath(ctx.req().getPathInfo())) {
                return;
            }

            String authHeader = ctx.header("Authorization");

            if (authHeader != null && authHeader.startsWith(X_CATWALK_BEARER)) {
                String token = authHeader.substring(7);
                if (Objects.equals(token, authKey)) {
                    if (isDebug) {
                        log.info("Auth successful via Bearer token for: " + ctx.req().getPathInfo());
                    }
                    return;
                } else {
                    log.warning("Invalid Bearer token provided: " + token.substring(0, Math.min(token.length(), 5)) + "...");
                }
            }

            // Check for auth cookie
            String authCookie = ctx.cookie(X_CATWALK_COOKIE);
            if (authCookie != null && Objects.equals(authCookie, authKey)) {
                if (isDebug) {
                    log.info("Auth successful via cookie for: " + ctx.req().getPathInfo());
                }
                return;
            }

            throw new UnauthorizedResponse("Authentication required. Use Bearer token authentication.");
        });
    }

    private void setupOpenApiEndpoints() {
        // Custom OpenAPI endpoint
        javalin.get("/openapi.json", ctx -> {
            try {
                String spec = openApiGenerator.generateOpenApiSpec();
                ctx.contentType("application/json").result(spec);
                log.info("[WebServer] Generated OpenAPI spec with " + openApiGenerator.getRouteCount() + " routes");
            } catch (Exception e) {
                log.severe("[WebServer] Failed to generate OpenAPI spec: " + e.getMessage());
                e.printStackTrace();
                ctx.status(500).json(java.util.Map.of("error", "Failed to generate OpenAPI specification"));
            }
        });

        // Redirect /openapi to /openapi.json
        javalin.get("/openapi", ctx -> {
            ctx.redirect("/openapi.json");
        });

        if (!disableSwagger) {
            setupSwaggerRoutes();
        }
    }

    private void setupSwaggerRoutes() {
        // Custom Swagger UI
        javalin.get("/swagger", ctx -> {
            ctx.html("""
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>CatWalk API Documentation</title>
                        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui.css" />
                        <style>
                            .topbar { background-color: #1b1b1b !important; padding: 10px 0; }
                            .topbar-wrapper .link img {
                                content: url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNDAiIGhlaWdodD0iNDAiIHZpZXdCb3g9IjAgMCAyNTYgMjU2IiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxwYXRoIGQ9Ik0xMzYuODUxIDE4Ni4zODdjLTEuODc3IDAtMy41My0xLjEzNS00LjIyLTIuODg4TDk5LjE4NyAxMDguNzRjLS45LTIuMjkuMTU2LTQuODggMi4zNjYtNS44OTEgMi4yMTItLjkyNiA0Ljc2NC4wNjQgNS43NjQgMi4yNzUuMDEuMDI1LjAyLjA1MS4wMy4wNzZsMzIuNTcyIDczLjE3NCAzNC4zMjUtNzcuMzk3YTQuNTUgNC41NSAwIDAgMSA1Ljg0My0yLjI4OCA0LjU1IDQuNTUgMCAwIDEgMi4zMjMgNS43ODNsLTQwLjg3IDkxLjk2NGEzLjY1IDMuNjUgMCAwIDEtNC42ODkgMi4wNTh2LS4xMDl6IiBmaWxsPSIjZmZmIi8+PC9zdmc+');
                                height: 40px; width: 40px;
                            }
                            #api-key-controls { padding-left: 150px; display: flex; align-items: center; }
                            #auth-status { color: white; font-size: 16px; margin-right: 10px; display: flex; align-items: center; }
                            #auth-status .lock-icon { margin-right: 5px; }
                            .auth-buttons { display: flex; flex-direction: row; gap: 8px; }
                            #api-key-controls button { background-color: #4990e2; color: white; border: none; border-radius: 4px; padding: 8px 15px; cursor: pointer; font-weight: bold; white-space: nowrap; }
                            #api-key-controls button:hover { background-color: #3672b9; }
                            .swagger-ui .topbar .topbar-wrapper { padding-right: 350px; }
                            .custom-info { background: #f8f9fa; padding: 15px; margin: 20px; border-radius: 5px; border-left: 4px solid #007bff; }
                        </style>
                    </head>
                    <body>
                    <div class="custom-info">
                        <strong>CatWalk API Documentation</strong><br>
                        📊 Routes registered: <span id="route-count">Loading...</span><br>
                        🔄 Generated: <span id="generation-time">
                    """
                    + new java.util.Date() +
                    """
                    </span><br>
                    🚀 Server:
                    """
                    + main.getServerId() +
                    """
                            (
                            """
                    + (main.isHubMode() ? "Hub" : "Backend") +
                    """
                    )
                    </div>
                    <div id="swagger-ui"></div>
                    <script src="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-bundle.js"></script>
                    <script src="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-standalone-preset.js"></script>
                    <script>
                        window.onload = function() {
                            // Update route count
                            fetch('/openapi.json')
                                .then(response => response.json())
                                .then(spec => {
                                    const pathCount = Object.keys(spec.paths || {}).length;
                                    document.getElementById('route-count').textContent = pathCount;
                                })
                                .catch(err => {
                                    document.getElementById('route-count').textContent = 'Error loading';
                                });
                    
                            window.ui = SwaggerUIBundle({
                                urls: [
                                    { name: "CatWalk API", url: "/openapi.json" },
                                    { name: "Graylist API", url: "/plugins/graylist/openapi.json" },
                                    { name: "COS API", url: "/plugins/cos/openapi.json" },
                                    { name: "Towny API", url: "/plugins/towny/openapi.json" }
                                ],
                                dom_id: '#swagger-ui',
                                deepLinking: true,
                                persistAuthorization: true,
                                displayOperationId: true,
                                defaultModelsExpandDepth: 3,
                                defaultModelExpandDepth: 3,
                                filter: true,
                                tryItOutEnabled: true,
                                validatorUrl: null,
                                presets: [
                                    SwaggerUIBundle.presets.apis,
                                    SwaggerUIStandalonePreset
                                ],
                                plugins: [
                                    SwaggerUIBundle.plugins.DownloadUrl
                                ],
                                layout: "StandaloneLayout",
                                requestInterceptor: (request) => {
                                    const authToken = localStorage.getItem('bearerToken');
                                    if (authToken) {
                                        request.headers = request.headers || {};
                                        request.headers['Authorization'] = `Bearer ${authToken}`;
                                    }
                                    return request;
                                },
                                onComplete: function() {
                                    setupCustomUI();
                                }
                            });
                        };
                    
                        function setupCustomUI() {
                            setTimeout(() => {
                                const header = document.querySelector('.topbar-wrapper');
                                if (!header || document.getElementById('api-key-controls')) return;
                    
                                const controlsContainer = document.createElement('div');
                                controlsContainer.id = 'api-key-controls';
                    
                                const authStatus = document.createElement('span');
                                authStatus.id = 'auth-status';
                                const lockIcon = document.createElement('span');
                                lockIcon.className = 'lock-icon';
                                lockIcon.innerHTML = localStorage.getItem('bearerToken') ? '🔓' : '🔒';
                                authStatus.appendChild(lockIcon);
                                authStatus.appendChild(document.createTextNode(
                                    localStorage.getItem('bearerToken') ? 'Authenticated' : 'Not authenticated'
                                ));
                                controlsContainer.appendChild(authStatus);
                    
                                const buttonContainer = document.createElement('div');
                                buttonContainer.className = 'auth-buttons';
                    
                                const authBtn = document.createElement('button');
                                authBtn.textContent = 'Set API Key';
                                authBtn.onclick = () => {
                                    const token = prompt('Enter your API key:');
                                    if (token) {
                                        localStorage.setItem('bearerToken', token);
                                        location.reload();
                                    }
                                };
                                buttonContainer.appendChild(authBtn);
                    
                                const clearBtn = document.createElement('button');
                                clearBtn.textContent = 'Clear Key';
                                clearBtn.onclick = () => {
                                    localStorage.removeItem('bearerToken');
                                    location.reload();
                                };
                                buttonContainer.appendChild(clearBtn);
                    
                                controlsContainer.appendChild(buttonContainer);
                                header.appendChild(controlsContainer);
                            }, 100);
                        }
                    </script>
                    </body>
                    </html>
                    """);
        });

        // Redirect /swagger.html to /swagger
        javalin.get("/swagger.html", ctx -> {
            ctx.redirect("/swagger");
        });

        // Simple ReDoc implementation
        javalin.get("/redoc", ctx -> {
            ctx.html("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>CatWalk API Documentation</title>
                        <meta charset="utf-8"/>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <link href="https://fonts.googleapis.com/css?family=Montserrat:300,400,700|Roboto:300,400,700" rel="stylesheet">
                        <style>
                            body { margin: 0; padding: 0; }
                        </style>
                    </head>
                    <body>
                        <redoc spec-url='/openapi.json'></redoc>
                        <script src="https://cdn.jsdelivr.net/npm/redoc@2.0.0/bundles/redoc.standalone.js"></script>
                    </body>
                    </html>
                    """);
        });

        log.info("[WebServer] Swagger UI available at /swagger");
        log.info("[WebServer] ReDoc available at /redoc");
    }

    /**
     * Register a route and track it for OpenAPI documentation
     */
    public void registerRoute(HandlerType method, String path, Handler handler, OpenApi openApiAnnotation) {
        // Add route to Javalin
        this.addRoute(method, path, handler);

        // Track route for OpenAPI generation
        if (openApiAnnotation != null) {
            openApiGenerator.registerRoute(method, path, handler, openApiAnnotation);
        }
    }

    /**
     * Register a handler instance for OpenAPI scanning
     */
    public void registerHandlerInstance(Object handlerInstance, String pluginName) {
        openApiGenerator.registerHandlerInstance(handlerInstance, pluginName);
    }

    private boolean isNoAuthPath(String requestPath) {
        if (Arrays.stream(noAuthPaths).anyMatch(requestPath::startsWith)) {
            return true;
        }

        if (whitelistedPaths != null) {
            for (String pattern : whitelistedPaths) {
                if (pattern.endsWith("*")) {
                    String prefix = pattern.substring(0, pattern.length() - 1);
                    if (requestPath.startsWith(prefix)) {
                        return true;
                    }
                } else if (pattern.contains("{") && pattern.contains("}")) {
                    String regex = pattern.replaceAll("\\{[^/]+}", "[^/]+");
                    if (requestPath.matches(regex)) {
                        return true;
                    }
                } else if (requestPath.equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void configureCors(JavalinConfig config) {
        config.bundledPlugins.enableCors(cors -> cors.addRule(corsConfig -> {
            if (corsOrigin.contains("*")) {
                log.info("Enabling CORS for *");
                corsConfig.anyHost();
            } else {
                corsOrigin.forEach(origin -> {
                    log.info(String.format("Enabling CORS for %s", origin));
                    corsConfig.allowHost(origin);
                });
            }
        }));
    }

    private void configureTLS(JavalinConfig config) {
        if (!tlsEnabled) {
            log.warning("TLS is not enabled.");
            return;
        }
        try {
            final String fullKeystorePath = main.getDataFolder().getAbsolutePath() + File.separator + keyStorePath;

            if (Files.exists(Paths.get(fullKeystorePath))) {
                SslPlugin plugin = new SslPlugin(conf -> {
                    conf.keystoreFromPath(fullKeystorePath, keyStorePassword);
                    conf.http2 = false;
                    conf.insecure = false;
                    conf.secure = true;
                    conf.securePort = securePort;
                    conf.sniHostCheck = sni;
                });

                config.registerPlugin(plugin);
                log.info("TLS is enabled.");
            } else {
                log.warning(String.format("TLS is enabled but %s doesn't exist. TLS disabled.", fullKeystorePath));
            }
        } catch (Exception e) {
            log.severe("Error while enabling TLS: " + e.getMessage());
            log.warning("TLS is not enabled.");
        }
    }

    public void get(String route, Handler handler) {
        this.addRoute(HandlerType.GET, route, handler);
    }

    public void post(String route, Handler handler) {
        this.addRoute(HandlerType.POST, route, handler);
    }

    public void put(String route, Handler handler) {
        this.addRoute(HandlerType.PUT, route, handler);
    }

    public void delete(String route, Handler handler) {
        this.addRoute(HandlerType.DELETE, route, handler);
    }

    public void addRoute(HandlerType httpMethod, String route, Handler handler) {
        // Checks to see if passed route is blocked in the config.
        // Note: The second check is for any blocked routes that start with a /
        if (!(blockedPaths.contains(route) || blockedPaths.contains("/" + route))) {
            this.javalin.addHttpHandler(httpMethod, route, handler);
        } else if (isDebug) {
            log.info(String.format("Not adding Route '%s' because it is blocked in the config.", route));
        }
    }

    public void ws(String route, Consumer<WsConfig> wsConfig) {
        this.javalin.ws(route, wsConfig);
    }

    public void start(int port) {
        this.javalin.start(port);
    }

    public void stop() {
        this.javalin.stop();
    }
}