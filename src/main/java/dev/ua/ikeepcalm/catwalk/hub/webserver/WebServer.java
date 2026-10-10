package dev.ua.ikeepcalm.catwalk.hub.webserver;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.utils.RequestLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonJsonMapper;
import dev.ua.ikeepcalm.catwalk.hub.webserver.audit.ApiRequestAuditor;
import dev.ua.ikeepcalm.catwalk.hub.webserver.audit.RouteAudit;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.websocket.WsConfig;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class WebServer {

    public static final String X_CATWALK_COOKIE = "x-catwalk-key";
    public static final String X_CATWALK_BEARER = "Bearer ";
    private static final String[] noAuthPaths = new String[]{"/", "/swagger", "/openapi", "/redoc", "/plugins", "/health"};

    private static final String ATTR_START_TIME = "catwalk-request-start";
    private static final String ATTR_AUTH_INFO = "catwalk-auth-info";

    private final Logger log;
    @Getter
    private final Javalin javalin;

    private final boolean isDebug;

    private final List<String> blockedPaths;
    private final List<String> whitelistedPaths;

    private final boolean isAuthEnabled;

    private final boolean requestLoggingEnabled;

    @Getter
    private final boolean disableSwagger;
    private final boolean tlsEnabled;
    /** True once the SSL plugin was actually registered (the keystore existed and loaded). */
    private boolean tlsActive;
    private final boolean sni;
    private final String keyStorePath;
    private final String keyStorePassword;

    @Getter
    private final String authKey;
    private final List<String> corsOrigin;
    @Getter
    private final String serverUrl;
    private final int securePort;
    private final CatWalkMain main;

    @Getter
    private final CustomOpenApiGenerator openApiGenerator;

    private final ApiRequestAuditor requestAuditor;

    public WebServer(CatWalkMain main, FileConfiguration bukkitConfig, Logger logger) {
        this.main = main;
        this.log = logger;
        this.isDebug = bukkitConfig.getBoolean("debug", false);
        this.blockedPaths = bukkitConfig.getStringList("blocked-paths");
        this.whitelistedPaths = bukkitConfig.getStringList("whitelisted-paths");
        this.isAuthEnabled = bukkitConfig.getBoolean("useKeyAuth", true);
        this.requestLoggingEnabled = bukkitConfig.getBoolean("request-logging.enabled", true);
        this.disableSwagger = bukkitConfig.getBoolean("disable-swagger", false);
        this.tlsEnabled = bukkitConfig.getBoolean("tls.enabled", false);
        this.sni = bukkitConfig.getBoolean("tls.sni", false);
        this.keyStorePath = bukkitConfig.getString("tls.keystore", "keystore.jks");
        this.keyStorePassword = bukkitConfig.getString("tls.keystorePassword", "");
        this.authKey = bukkitConfig.getString("key", "change_me");
        this.corsOrigin = bukkitConfig.getStringList("corsOrigins");
        this.serverUrl = bukkitConfig.getString("server-url", "http://localhost:" + bukkitConfig.getInt("port", 4567));
        this.securePort = bukkitConfig.getInt("port", 4567);
        this.openApiGenerator = new CustomOpenApiGenerator(main);
        this.requestAuditor = new ApiRequestAuditor(main.getAudit(), main.getRequestSummary(), main.getRequestLimiter(),
                main.getRouteAudit(), authKey, X_CATWALK_COOKIE);
        this.javalin = Javalin.create(this::configureJavalin);

        setupRequestLogging();
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

//        config.registerPlugin(new RouteOverviewPlugin((configuration -> {
//            configuration.path = "/overview";
//        })));
    }

    private void setupAuthentication() {
        this.javalin.beforeMatched(ctx -> {
            requestAuditor.onRouteMatched(ctx);

            if (!isAuthEnabled) {
                ctx.attribute(ATTR_AUTH_INFO, "disabled");
                return;
            }

            if (isNoAuthPath(ctx.req().getPathInfo())) {
                ctx.attribute(ATTR_AUTH_INFO, "not-required");
                return;
            }

            String authHeader = ctx.header("Authorization");

            if (authHeader != null && authHeader.startsWith(X_CATWALK_BEARER)) {
                String token = authHeader.substring(7);
                if (Objects.equals(token, authKey)) {
                    ctx.attribute(ATTR_AUTH_INFO, "bearer:" + redact(token));
                    if (isDebug) {
                        log.info("Auth successful via Bearer token for: " + ctx.req().getPathInfo());
                    }
                    return;
                } else {
                    log.warning("Invalid Bearer token provided: " + token.substring(0, Math.min(token.length(), 5)) + "...");
                    ctx.attribute(ATTR_AUTH_INFO, "bearer-invalid:" + redact(token));
                }
            }

            String authCookie = ctx.cookie(X_CATWALK_COOKIE);
            if (authCookie != null && Objects.equals(authCookie, authKey)) {
                ctx.attribute(ATTR_AUTH_INFO, "cookie:" + redact(authCookie));
                if (isDebug) {
                    log.info("Auth successful via cookie for: " + ctx.req().getPathInfo());
                }
                return;
            }

            ctx.attribute(ATTR_AUTH_INFO, authHeader == null && authCookie == null ? "missing" : "invalid");
            throw new UnauthorizedResponse("Authentication required. Use Bearer token authentication.");
        });
    }

    /**
     * Whether a request to the given path is required to present valid authentication.
     * Used by the /catwalk endpoints command to report each route's auth requirement.
     */
    public boolean requiresAuthentication(String path) {
        return isAuthEnabled && !isNoAuthPath(path);
    }

    private static String redact(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "none";
        }
        return secret.substring(0, Math.min(4, secret.length())) + "...";
    }

    private void setupRequestLogging() {
        if (!requestLoggingEnabled && !requestAuditor.isEnabled()) {
            return;
        }

        this.javalin.before(ctx -> {
            ctx.attribute(ATTR_START_TIME, System.currentTimeMillis());
            requestAuditor.onRequestStart(ctx);
        });
        this.javalin.after(this::logRequest);
    }

    private void logRequest(Context ctx) {
        Long startTime = ctx.attribute(ATTR_START_TIME);
        long durationMs = startTime != null ? System.currentTimeMillis() - startTime : -1;
        String rawAuthInfo = ctx.attribute(ATTR_AUTH_INFO);

        try {
            if (requestLoggingEnabled) {
                writeRequestLog(ctx, durationMs, rawAuthInfo == null ? "n/a" : rawAuthInfo);
            }
        } finally {
            requestAuditor.onRequestComplete(ctx, durationMs, rawAuthInfo != null,
                    () -> auditAuthResult(ctx, rawAuthInfo));
        }
    }

    private void writeRequestLog(Context ctx, long durationMs, String authInfo) {
        String line = String.format(
                "%s ip=%s method=%-6s path=%s status=%d auth=%s duration_ms=%d user_agent=\"%s\"",
                Instant.now(), clientIp(ctx), ctx.method(), ctx.path(), ctx.statusCode(),
                authInfo, durationMs, sanitize(ctx.userAgent())
        );

        RequestLogger.log(line);
    }

    /**
     * Maps the auth attribute set in beforeMatched onto the audit vocabulary. A null attribute
     * means no route matched, so the key was never checked and is evaluated here instead.
     */
    private String auditAuthResult(Context ctx, String authInfo) {
        if (authInfo == null) {
            if (!isAuthEnabled) {
                return ApiRequestAuditor.AUTH_DISABLED;
            }
            return isNoAuthPath(ctx.req().getPathInfo())
                    ? ApiRequestAuditor.AUTH_WHITELISTED : requestAuditor.unmatchedAuthResult(ctx);
        }
        if (authInfo.equals("disabled")) {
            return ApiRequestAuditor.AUTH_DISABLED;
        }
        if (authInfo.equals("not-required")) {
            return ApiRequestAuditor.AUTH_WHITELISTED;
        }
        if (authInfo.startsWith("bearer:") || authInfo.startsWith("cookie:")) {
            return ApiRequestAuditor.AUTH_OK;
        }
        return authInfo.equals("missing") ? ApiRequestAuditor.AUTH_MISSING : ApiRequestAuditor.AUTH_INVALID;
    }

    private String clientIp(Context ctx) {
        String forwarded = ApiRequestAuditor.forwardedFor(ctx);
        return forwarded != null ? forwarded : ctx.ip();
    }

    private String sanitize(String value) {
        return value == null ? "-" : value.replace("\"", "'");
    }

    private void setupOpenApiEndpoints() {
        if (!disableSwagger) {
            javalin.get("/openapi.json", ctx -> {
                try {
                    String spec = openApiGenerator.generateOpenApiSpec();
                    ctx.contentType("application/json").result(spec);
                    log.info("[WebServer] Generated custom OpenAPI spec with " + openApiGenerator.getRouteCount() + " routes");
                } catch (Exception e) {
                    log.severe("[WebServer] Failed to generate OpenAPI spec: " + e.getMessage());
                    ctx.status(500).json(java.util.Map.of("error", "Failed to generate OpenAPI specification"));
                }
            });

            javalin.get("/openapi", ctx -> {
                ctx.redirect("/openapi.json");
            });

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

        javalin.get("/", ctx -> {
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

        javalin.get("/health", ctx -> {
            ctx.json(java.util.Map.of(
                    "status", "healthy",
                    "timestamp", System.currentTimeMillis(),
                    "server", main.getServerId(),
                    "mode", main.isHubMode() ? "hub" : "backend"
            ));
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
                                layout: "StandaloneLayout",
                                // Enable authorization persistence
                                persistAuthorization: true,
                                // Enable request/response body editors
                                tryItOutEnabled: true,
                                // Enable default request body for POST methods
                                defaultModelsExpandDepth: 1,
                                defaultModelExpandDepth: 1,
                                // Pre-populate auth from localStorage
                                onComplete: function() {
                                    // Restore saved authorization
                                    const savedAuth = localStorage.getItem('swagger-bearer-auth');
                                    if (savedAuth) {
                                        ui.preauthorizeApiKey('bearerAuth', savedAuth);
                                    }
                                },
                                // Intercept auth changes to save them
                                requestInterceptor: function(request) {
                                    // Save bearer token when it's used
                                    if (request.headers && request.headers.Authorization && request.headers.Authorization.startsWith('Bearer ')) {
                                        const token = request.headers.Authorization.substring(7);
                                        localStorage.setItem('swagger-bearer-auth', token);
                                    }
                                    return request;
                                }
                            });
                
                            // Override the authorize function to save tokens
                            setTimeout(function() {
                                enhanceAuthorizeButton(ui);
                            }, 1000);
                        };
                
                        function enhanceAuthorizeButton(ui) {
                            // Find and enhance the authorize button
                            const observer = new MutationObserver(function(mutations) {
                                const authorizeBtn = document.querySelector('.btn.authorize');
                                if (authorizeBtn && !authorizeBtn.dataset.enhanced) {
                                    authorizeBtn.dataset.enhanced = 'true';
                
                                    // Override click to save auth data
                                    authorizeBtn.addEventListener('click', function() {
                                        setTimeout(function() {
                                            const authModal = document.querySelector('.auth-container');
                                            if (authModal) {
                                                const authInput = authModal.querySelector('input[type="text"], input[type="password"]');
                                                const authorizeModalBtn = authModal.querySelector('.btn.authorize');
                
                                                if (authorizeModalBtn && !authorizeModalBtn.dataset.enhanced) {
                                                    authorizeModalBtn.dataset.enhanced = 'true';
                
                                                    authorizeModalBtn.addEventListener('click', function() {
                                                        setTimeout(function() {
                                                            if (authInput && authInput.value) {
                                                                localStorage.setItem('swagger-bearer-auth', authInput.value);
                                                            }
                                                        }, 100);
                                                    });
                                                }
                
                                                // Pre-fill saved token
                                                const savedToken = localStorage.getItem('swagger-bearer-auth');
                                                if (savedToken && authInput && !authInput.value) {
                                                    authInput.value = savedToken;
                                                }
                                            }
                                        }, 100);
                                    });
                                }
                            });
                
                            observer.observe(document.body, { childList: true, subtree: true });
                        }
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
     * Register a handler instance for OpenAPI scanning and route registration
     */
    public void registerHandlerInstance(Object handlerInstance, String pluginName) {
        openApiGenerator.registerHandlerInstance(handlerInstance, pluginName);
        registerAnnotatedRoutes(handlerInstance);
    }

    /**
     * Register HTTP routes from @OpenApi annotated methods
     */
    private void registerAnnotatedRoutes(Object handlerInstance) {
        Class<?> clazz = handlerInstance.getClass();

        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            io.javalin.openapi.OpenApi openApiAnnotation = method.getAnnotation(io.javalin.openapi.OpenApi.class);
            if (openApiAnnotation == null) continue;

            io.javalin.openapi.HttpMethod[] httpMethods = openApiAnnotation.methods().length > 0 ?
                    openApiAnnotation.methods() :
                    new io.javalin.openapi.HttpMethod[]{io.javalin.openapi.HttpMethod.GET};

            for (io.javalin.openapi.HttpMethod httpMethod : httpMethods) {
                HandlerType handlerType = convertHttpMethodToHandlerType(httpMethod);
                String path = openApiAnnotation.path();

                Handler handler = ctx -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(handlerInstance, ctx);
                    } catch (Exception e) {
                        log.severe("Error invoking handler method " + method.getName() + ": " + e.getMessage());
                        ctx.status(500).result("Internal server error");
                    }
                };

                addRoute(handlerType, path, handler);
                log.info("Registered " + handlerType + " route: " + path);
            }
        }
    }

    /**
     * Convert OpenAPI HttpMethod to Javalin HandlerType
     */
    private HandlerType convertHttpMethodToHandlerType(io.javalin.openapi.HttpMethod httpMethod) {
        return switch (httpMethod) {
            case GET -> HandlerType.GET;
            case POST -> HandlerType.POST;
            case PUT -> HandlerType.PUT;
            case DELETE -> HandlerType.DELETE;
            case PATCH -> HandlerType.PATCH;
            case HEAD -> HandlerType.HEAD;
            case OPTIONS -> HandlerType.OPTIONS;
            default -> HandlerType.GET; // Default to GET for unknown methods
        };
    }

    /**
     * Attributes an already-registered route to the addon that owns it, and records its
     * declared per-route auth requirement, so /catwalk endpoints reports it accurately
     * instead of as an anonymous core route.
     */
    public void tagRoute(io.javalin.openapi.HttpMethod method, String path, String pluginName, boolean requiresAuth) {
        openApiGenerator.tagRoute(convertHttpMethodToHandlerType(method), path, pluginName, requiresAuth);
        RouteAudit routeAudit = main.getRouteAudit();
        routeAudit.registered(new RouteAudit.Descriptor(pluginName, method.name(), path, RouteAudit.KIND_HANDLER),
                isRouteBlocked(path), pluginName);
    }

    /**
     * Register a proxy route with OpenAPI documentation
     */
    public void registerProxyRoute(HandlerType method, String path, Handler handler, String summary, String description, String[] tags, EndpointDefinition detailedEndpoint) {
        addRoute(method, path, handler);
        openApiGenerator.registerProxyRoute(method, path, summary, description, tags, detailedEndpoint);
    }

    public void registerProxyRoute(HandlerType method, String path, Handler handler, String summary, String description, String[] tags) {
        registerProxyRoute(method, path, handler, summary, description, tags, null);
    }

    private boolean isNoAuthPath(String requestPath) {
        // "/" must be an exact match - matching it as a prefix would match every path,
        // since every HTTP path starts with "/", bypassing auth entirely.
        if (requestPath.equals("/")) {
            return true;
        }

        if (Arrays.stream(noAuthPaths).filter(p -> !p.equals("/")).anyMatch(requestPath::startsWith)) {
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
                log.info("Enabling CORS for all origins (*)");
                corsConfig.reflectClientOrigin = true;
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
                tlsActive = true;
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

    /** Whether {@code blocked-paths} prevents {@link #addRoute} from registering {@code route}. */
    public boolean isRouteBlocked(String route) {
        return blockedPaths.contains(route) || blockedPaths.contains("/" + route);
    }

    public void addRoute(HandlerType httpMethod, String route, Handler handler) {
        if (!isRouteBlocked(route)) {
            this.javalin.addHttpHandler(httpMethod, route, handler);

            openApiGenerator.registerStaticRoute(httpMethod, route, handler);
        } else if (isDebug) {
            log.info(String.format("Not adding Route '%s' because it is blocked in the config.", route));
        }
    }

    public void ws(String route, Consumer<WsConfig> wsConfig) {
        this.javalin.ws(route, wsConfig);
    }

    /**
     * Plain-value snapshot of the security-relevant settings for audit rows. Contains counts only,
     * never the key, the keystore password or the path lists themselves.
     */
    public Map<String, Object> auditDescription() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("tls", tlsActive);
        description.put("tls_configured", tlsEnabled);
        description.put("auth", isAuthEnabled);
        description.put("blocked_paths_count", blockedPaths == null ? 0 : blockedPaths.size());
        description.put("whitelisted_paths_count", whitelistedPaths == null ? 0 : whitelistedPaths.size());
        return description;
    }

    public void start(int port) {
        this.javalin.start(port);
    }

    public void stop() {
        this.javalin.stop();
    }
}