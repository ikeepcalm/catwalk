package dev.ua.uaproject.catwalk.hub.webserver;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.common.utils.json.GsonJsonMapper;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.bundled.RouteOverviewPlugin;
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
    private static final String[] noAuthPaths = new String[]{"/", "/swagger", "/openapi", "/redoc", "/plugins", "/health"};

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
        this.openApiGenerator = new CustomOpenApiGenerator(main);
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

        config.registerPlugin(new RouteOverviewPlugin((configuration -> {
            configuration.path = "/overview";
        })));
    }

    private void setupAuthentication() {
        this.javalin.options("/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "*");
            ctx.header("Access-Control-Allow-Credentials", "true");
            ctx.status(200);
        });

        this.javalin.beforeMatched(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Credentials", "true");

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
        // Custom OpenAPI endpoint using our generator
        javalin.get("/openapi.json", ctx -> {
            try {
                String spec = openApiGenerator.generateOpenApiSpec();
                ctx.contentType("application/json").result(spec);
                log.info("[WebServer] Generated custom OpenAPI spec with " + openApiGenerator.getRouteCount() + " routes");
            } catch (Exception e) {
                log.severe("[WebServer] Failed to generate OpenAPI spec: " + e.getMessage());
                e.printStackTrace();
                ctx.status(500).json(java.util.Map.of("error", "Failed to generate OpenAPI specification"));
            }
        });

        javalin.get("/openapi", ctx -> {
            ctx.redirect("/openapi.json");
        });

        if (!disableSwagger) {
            setupSwaggerRoutes();
        }
    }

    private void setupSwaggerRoutes() {
        // Serve Swagger UI manually
        javalin.get("/swagger", ctx -> {
            ctx.html(generateSwaggerHtml());
        });

        javalin.get("/swagger.html", ctx -> {
            ctx.redirect("/swagger");
        });

        // Serve ReDoc manually
        javalin.get("/redoc", ctx -> {
            ctx.html(generateRedocHtml());
        });

        log.info("[WebServer] Custom Swagger UI available at /swagger");
        log.info("[WebServer] Custom ReDoc available at /redoc");
    }

    private String generateSwaggerHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>CatWalk API Documentation</title>
                    <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@4.15.5/swagger-ui.css" />
                    <style>
                        html { box-sizing: border-box; overflow: -moz-scrollbars-vertical; overflow-y: scroll; }
                        *, *:before, *:after { box-sizing: inherit; }
                        body { margin:0; background: #fafafa; }
                    </style>
                </head>
                <body>
                    <div id="swagger-ui"></div>
                    <script src="https://unpkg.com/swagger-ui-dist@4.15.5/swagger-ui-bundle.js"></script>
                    <script src="https://unpkg.com/swagger-ui-dist@4.15.5/swagger-ui-standalone-preset.js"></script>
                    <script>
                        window.onload = function() {
                            const ui = SwaggerUIBundle({
                                url: '/openapi.json',
                                dom_id: '#swagger-ui',
                                deepLinking: true,
                                presets: [
                                    SwaggerUIBundle.presets.apis,
                                    SwaggerUIStandalonePreset
                                ],
                                plugins: [
                                    SwaggerUIBundle.plugins.DownloadUrl
                                ],
                                layout: "StandaloneLayout"
                            });
                        };
                    </script>
                </body>
                </html>
                """;
    }

    private String generateRedocHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>CatWalk API Documentation - ReDoc</title>
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
                """;
    }

    /**
     * Register a handler instance for OpenAPI scanning
     */
    public void registerHandlerInstance(Object handlerInstance, String pluginName) {
        openApiGenerator.registerHandlerInstance(handlerInstance, pluginName);
    }

    /**
     * Register a proxy route with OpenAPI documentation
     */
    public void registerProxyRoute(HandlerType method, String path, Handler handler, String summary, String description, String[] tags) {
        addRoute(method, path, handler);
        openApiGenerator.registerProxyRoute(method, path, summary, description, tags);
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

    //TODO: Setup!!!
    private void configureCors(JavalinConfig config) {
        config.bundledPlugins.enableCors(cors -> cors.addRule(corsConfig -> {
            if (corsOrigin.contains("*")) {
                log.info("Enabling CORS for *");
                corsConfig.reflectClientOrigin = true;
//                corsConfig.anyHost();
            } else {
                corsOrigin.forEach(origin -> {
                    log.info(String.format("Enabling CORS for %s", origin));
                    corsConfig.allowHost(origin);
                });
            }

            corsConfig.allowCredentials = true;
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
        if (!(blockedPaths.contains(route) || blockedPaths.contains("/" + route))) {
            this.javalin.addHttpHandler(httpMethod, route, handler);

            openApiGenerator.registerStaticRoute(httpMethod, route, handler);
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