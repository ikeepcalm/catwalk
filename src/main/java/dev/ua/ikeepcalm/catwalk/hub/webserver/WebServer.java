package dev.ua.ikeepcalm.catwalk.hub.webserver;

import dev.ua.ikeepcalm.catwalk.CatWalkMain;
import dev.ua.ikeepcalm.catwalk.common.database.model.EndpointDefinition;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.RequestLogger;
import dev.ua.ikeepcalm.catwalk.common.utils.json.GsonJsonMapper;
import dev.ua.ikeepcalm.catwalk.hub.webserver.CustomOpenApiGenerator.RouteType;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.servlet.JavalinServletContext;
import io.javalin.openapi.HttpMethod;
import io.javalin.router.Endpoint;
import io.javalin.router.ParsedEndpoint;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsHandlerType;
import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class WebServer {

    public static final int MIN_KEY_LENGTH = 16;

    private static final String BEARER = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/", "/health");
    private static final List<HandlerType> ROUTED_METHODS = List.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);
    private static final int MAX_FAILED_AUTH_PER_MINUTE = 10;
    private static final String API_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

    private static final String ATTR_START_TIME = "catwalk-request-start";
    private static final String ATTR_AUTH_INFO = "catwalk-auth-info";

    private final CatWalkMain main;
    @Getter
    private final Javalin javalin;
    @Getter
    private final CustomOpenApiGenerator openApiGenerator;
    private final Map<HandlerType, List<ParsedEndpoint>> routes = new ConcurrentHashMap<>();
    private final RateLimiter routeLimiter = new RateLimiter();
    private final RateLimiter failedAuthLimiter = new RateLimiter();

    @Getter
    private final boolean authEnabled;
    @Getter
    private final String authKey;
    @Getter
    private final String serverUrl;
    private final List<ApiKey> apiKeys;
    private final boolean debug;
    private final List<String> blockedPaths;
    private final List<String> whitelistedPaths;
    private final List<String> corsOrigins;
    private final List<String> trustedProxies;
    private final boolean requestLoggingEnabled;
    private final boolean swaggerDisabled;
    private final int port;

    public WebServer(CatWalkMain main, FileConfiguration config) {
        this.main = main;
        this.debug = config.getBoolean("debug");
        this.authEnabled = config.getBoolean("useKeyAuth");
        this.authKey = config.getString("key");
        this.port = config.getInt("port");
        this.serverUrl = config.getString("server-url");
        this.blockedPaths = config.getStringList("blocked-paths");
        this.whitelistedPaths = config.getStringList("whitelisted-paths");
        this.corsOrigins = config.getStringList("corsOrigins");
        this.trustedProxies = config.getStringList("trusted-proxies");
        this.requestLoggingEnabled = config.getBoolean("request-logging.enabled");
        this.swaggerDisabled = config.getBoolean("disable-swagger");
        this.apiKeys = loadApiKeys(config);
        this.openApiGenerator = new CustomOpenApiGenerator(main);

        this.javalin = Javalin.create(cfg -> configure(cfg, config));
        registerDocumentationRoutes();
    }

    private List<ApiKey> loadApiKeys(FileConfiguration config) {
        List<ApiKey> keys = new ArrayList<>();
        if (authKey == null || authKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("'key' in config.yml must be at least " + MIN_KEY_LENGTH + " characters long");
        }
        keys.add(new ApiKey("master", authKey, Set.of(), true));

        ConfigurationSection scoped = config.getConfigurationSection("scoped-keys");
        if (scoped != null) {
            for (String name : scoped.getKeys(false)) {
                String key = scoped.getString(name + ".key");
                List<String> scopes = scoped.getStringList(name + ".scopes");
                if (key == null || key.length() < MIN_KEY_LENGTH || scopes.isEmpty()) {
                    throw new IllegalStateException("scoped-keys." + name + " needs a key of at least " + MIN_KEY_LENGTH + " characters and at least one scope");
                }
                keys.add(new ApiKey(name, key, Set.copyOf(scopes), false));
            }
        }
        return keys;
    }

    private void configure(JavalinConfig config, FileConfiguration bukkitConfig) {
        config.jsonMapper(new GsonJsonMapper());
        config.http.defaultContentType = "application/json";
        config.startup.showJavalinBanner = false;

        configureTls(config, bukkitConfig);
        configureCors(config);

        config.routes.before(this::rejectMethodOverride);
        config.routes.before(WebServer::addSecurityHeaders);
        if (requestLoggingEnabled) {
            config.routes.before(ctx -> ctx.attribute(ATTR_START_TIME, System.currentTimeMillis()));
            config.routes.after(this::logRequest);
        }
        if (debug) {
            config.routes.before(ctx -> CatWalkLogger.debug("%s %s", ctx.method(), ctx.path()));
        }
        config.routes.beforeMatched(this::authorize);
        ROUTED_METHODS.forEach(method -> config.routes.addHttpHandler(method, "*", this::dispatch));
    }

    private void rejectMethodOverride(Context ctx) {
        if (ctx.header("X-HTTP-Method-Override") != null) {
            throw new BadRequestResponse("X-HTTP-Method-Override is not supported");
        }
    }

    private static void addSecurityHeaders(Context ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Content-Security-Policy", API_CSP);
    }

    private void dispatch(Context ctx) throws Exception {
        String uri = requestUri(ctx);
        ParsedEndpoint route = findRoute(ctx.method(), uri);
        if (route == null) {
            throw new NotFoundResponse("Endpoint " + ctx.method().name() + " " + uri + " not found");
        }
        route.handle((JavalinServletContext) ctx, uri);
    }

    private ParsedEndpoint findRoute(HandlerType method, String uri) {
        return routes.getOrDefault(method, List.of()).stream()
                .filter(candidate -> candidate.matches(uri))
                .findFirst()
                .orElse(null);
    }

    private static String requestUri(Context ctx) {
        return ctx.path().substring(ctx.req().getContextPath().length());
    }

    private void authorize(Context ctx) {
        String uri = requestUri(ctx);
        ParsedEndpoint route = findRoute(ctx.method(), uri);
        RouteAccess access = route != null ? route.endpoint.metadata(RouteAccess.class) : RouteAccess.ADDON;
        String ip = clientIp(ctx);

        if ((authEnabled && !isPublicPath(ctx.path())) || access.isRequiresAuth()) {
            if (failedAuthLimiter.isExceeded(ip, MAX_FAILED_AUTH_PER_MINUTE)) {
                throw new HttpResponseException(HttpStatus.TOO_MANY_REQUESTS.getCode(), "Too many failed authentication attempts, try again later");
            }
            ApiKey key = matchKey(ctx.header("Authorization"));
            if (key == null) {
                failedAuthLimiter.record(ip);
                ctx.attribute(ATTR_AUTH_INFO, ctx.header("Authorization") == null ? "missing" : "invalid");
                throw new UnauthorizedResponse("Authentication required. Use Bearer token authentication.");
            }
            ctx.attribute(ATTR_AUTH_INFO, "key:" + key.getName());
            if (!key.allows(access)) {
                throw new ForbiddenResponse("This key is not allowed to use this endpoint");
            }
        } else {
            ctx.attribute(ATTR_AUTH_INFO, authEnabled ? "not-required" : "disabled");
        }

        if (access.getRateLimitPerMinute() > 0 && route != null
                && !routeLimiter.tryAcquire(ip + "|" + ctx.method().name() + " " + route.endpoint.path, access.getRateLimitPerMinute())) {
            throw new HttpResponseException(HttpStatus.TOO_MANY_REQUESTS.getCode(), "Rate limit exceeded");
        }
        if (access.isLogRequests()) {
            CatWalkLogger.info("%s %s from %s (%s)", ctx.method().name(), ctx.path(), ip, ctx.<String>attribute(ATTR_AUTH_INFO));
        }
    }

    private ApiKey matchKey(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length());
        return apiKeys.stream().filter(key -> key.matches(token)).findFirst().orElse(null);
    }

    public boolean requiresAuthentication(String path) {
        return authEnabled && !isPublicPath(path);
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.contains(path) || whitelistedPaths.stream().anyMatch(pattern -> matchesPattern(pattern, path));
    }

    static boolean matchesPattern(String pattern, String path) {
        if (pattern.endsWith("*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.contains("{")) {
            return path.matches(pattern.replaceAll("\\{[^/]+}", "[^/]+"));
        }
        return path.equals(pattern);
    }

    private void logRequest(Context ctx) {
        Long startTime = ctx.attribute(ATTR_START_TIME);
        String authInfo = ctx.attribute(ATTR_AUTH_INFO);
        RequestLogger.log(String.format(
                "%s ip=%s method=%-6s path=%s status=%d auth=%s duration_ms=%d user_agent=\"%s\"",
                Instant.now(), clientIp(ctx), ctx.method(), ctx.path(), ctx.statusCode(),
                authInfo != null ? authInfo : "n/a",
                startTime != null ? System.currentTimeMillis() - startTime : -1,
                ctx.userAgent() != null ? ctx.userAgent().replace("\"", "'") : "-"
        ));
    }

    private String clientIp(Context ctx) {
        String remote = ctx.req().getRemoteAddr();
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && trustedProxies.contains(remote)) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return remote;
    }

    private void configureCors(JavalinConfig config) {
        config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
            if (corsOrigins.contains("*")) {
                rule.anyHost();
                return;
            }
            corsOrigins.forEach(rule::allowHost);
        }));
    }

    private void configureTls(JavalinConfig config, FileConfiguration bukkitConfig) {
        if (!bukkitConfig.getBoolean("tls.enabled")) {
            return;
        }
        File keystore = new File(main.getDataFolder(), bukkitConfig.getString("tls.keystore"));
        if (!keystore.exists()) {
            throw new IllegalStateException("TLS is enabled but " + keystore.getAbsolutePath() + " doesn't exist, refusing to serve plain HTTP");
        }
        config.registerPlugin(new SslPlugin(ssl -> {
            ssl.keystoreFromPath(keystore.getAbsolutePath(), bukkitConfig.getString("tls.keystorePassword"));
            ssl.http2 = false;
            ssl.insecure = false;
            ssl.secure = true;
            ssl.securePort = port;
            ssl.sniHostCheck = bukkitConfig.getBoolean("tls.sni");
        }));
    }

    private void registerDocumentationRoutes() {
        addCoreRoute(HandlerType.GET, "/health", ctx -> ctx.json(Map.of("status", "healthy", "timestamp", System.currentTimeMillis())));
        addCoreRoute(HandlerType.GET, "/", ctx -> ctx.html(indexHtml()));

        if (swaggerDisabled) {
            return;
        }
        addCoreRoute(HandlerType.GET, "/openapi.json", ctx -> ctx.contentType("application/json").result(openApiGenerator.generateOpenApiSpec()));
        addCoreRoute(HandlerType.GET, "/openapi", ctx -> ctx.redirect("/openapi.json"));
        addCoreRoute(HandlerType.GET, "/swagger", ctx -> ctx.header("Content-Security-Policy", SWAGGER_CSP).html(SWAGGER_HTML));
        addCoreRoute(HandlerType.GET, "/swagger.html", ctx -> ctx.redirect("/swagger"));
        addCoreRoute(HandlerType.GET, "/redoc", ctx -> ctx.header("Content-Security-Policy", REDOC_CSP).html(REDOC_HTML));
    }

    private String indexHtml() {
        String networkLinks = main.isHubMode()
                ? "<h2>Network</h2><ul><li><a href=\"/v1/network/status\">Network status</a></li><li><a href=\"/v1/network/servers\">Network servers</a></li></ul>"
                : "";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>CatWalk API Server</title>
                    <style>
                        body { font-family: Arial, sans-serif; margin: 40px; }
                        a { color: #3498db; text-decoration: none; }
                        ul { list-style-type: none; padding: 0; }
                        li { margin: 10px 0; }
                    </style>
                </head>
                <body>
                    <h1>CatWalk REST API Server</h1>
                    <h2>Documentation</h2>
                    <ul>
                        <li><a href="/swagger">Swagger UI</a></li>
                        <li><a href="/redoc">ReDoc</a></li>
                        <li><a href="/openapi.json">OpenAPI specification</a></li>
                    </ul>
                    %s
                </body>
                </html>
                """.formatted(networkLinks);
    }

    public void get(String path, Handler handler) {
        addRoute(HandlerType.GET, path, handler);
    }

    public void post(String path, Handler handler) {
        addRoute(HandlerType.POST, path, handler);
    }

    public void put(String path, Handler handler) {
        addRoute(HandlerType.PUT, path, handler);
    }

    public void delete(String path, Handler handler) {
        addRoute(HandlerType.DELETE, path, handler);
    }

    public void addRoute(HandlerType method, String path, Handler handler) {
        addRoute(method, path, handler, RouteAccess.ADDON);
    }

    public void addCoreRoute(HandlerType method, String path, Handler handler) {
        addRoute(method, path, handler, RouteAccess.CORE);
    }

    public void addRoute(HandlerType method, String path, Handler handler, RouteAccess access) {
        if (blockedPaths.contains(path)) {
            CatWalkLogger.debug("Not adding route %s %s because it is blocked in the config", method, path);
            return;
        }
        List<ParsedEndpoint> methodRoutes = routes.computeIfAbsent(method, m -> new CopyOnWriteArrayList<>());
        synchronized (methodRoutes) {
            ParsedEndpoint existing = methodRoutes.stream().filter(route -> route.endpoint.path.equals(path)).findFirst().orElse(null);
            if (existing != null) {
                if (existing.endpoint.metadata(RouteAccess.class).isCore() && !access.isCore()) {
                    CatWalkLogger.warn("Refusing to replace core route %s %s", method.name(), path);
                    return;
                }
                CatWalkLogger.warn("Route %s %s was registered twice, the newest handler replaces the old one", method.name(), path);
                methodRoutes.remove(existing);
            }
            methodRoutes.add(new ParsedEndpoint(new Endpoint(method, path, handler, Set.of(access)), javalin.unsafe.router));
        }
        openApiGenerator.registerStaticRoute(method, path);
    }

    public void describeRoute(HttpMethod method, String path, RouteType routeType, String pluginName, EndpointDefinition endpoint) {
        openApiGenerator.describeRoute(toHandlerType(method), path, routeType, pluginName, endpoint.isRequiresAuth(), endpoint);
    }

    public static HandlerType toHandlerType(HttpMethod method) {
        return HandlerType.findOrCreate(method.name());
    }

    public void ws(String path, Consumer<WsConfig> wsConfig) {
        // shortcut: Javalin 7 has no public API for websockets added after start, upgrade if it ever stops exposing unsafe.internalRouter
        javalin.unsafe.internalRouter.addWsHandler(WsHandlerType.WEBSOCKET, path, wsConfig);
    }

    public void start() {
        javalin.start(port);
    }

    public void stop() {
        javalin.stop();
    }

    private static String scriptHash(String script) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SWAGGER_SCRIPT = """
            window.onload = () => SwaggerUIBundle({
                url: '/openapi.json',
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                layout: 'StandaloneLayout',
                persistAuthorization: false,
                tryItOutEnabled: true
            });
            """;

    private static final String SWAGGER_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>CatWalk API Documentation</title>
                <meta charset="utf-8"/>
                <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.17.14/swagger-ui.css"
                      integrity="sha384-wxLW6kwyHktdDGr6Pv1zgm/VGJh99lfUbzSn6HNHBENZlCN7W602k9VkGdxuFvPn" crossorigin="anonymous"/>
            </head>
            <body>
                <div id="swagger-ui"></div>
                <script src="https://unpkg.com/swagger-ui-dist@5.17.14/swagger-ui-bundle.js"
                        integrity="sha384-wmyclcVGX/WhUkdkATwhaK1X1JtiNrr2EoYJ+diV3vj4v6OC5yCeSu+yW13SYJep" crossorigin="anonymous"></script>
                <script src="https://unpkg.com/swagger-ui-dist@5.17.14/swagger-ui-standalone-preset.js"
                        integrity="sha384-2YH8WDRaj7V2OqU/trsmzSagmk/E2SutiCsGkdgoQwC9pNUJV1u/141DHB6jgs8t" crossorigin="anonymous"></script>
                <script>%s</script>
            </body>
            </html>
            """.formatted(SWAGGER_SCRIPT);

    private static final String SWAGGER_CSP = "default-src 'none'; script-src https://unpkg.com " + scriptHash(SWAGGER_SCRIPT)
            + "; style-src https://unpkg.com 'unsafe-inline'; img-src 'self' data: https://unpkg.com; font-src https://unpkg.com data:; connect-src 'self'; frame-ancestors 'none'";

    private static final String REDOC_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>CatWalk API Documentation</title>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>body { margin: 0; padding: 0; }</style>
            </head>
            <body>
                <redoc spec-url="/openapi.json"></redoc>
                <script src="https://cdn.jsdelivr.net/npm/redoc@2.1.5/bundles/redoc.standalone.js"
                        integrity="sha384-0GrsyTQc9Oqd8h+b2dbc4XdR2T/DYpy0tLNNstyx+LBMUyiBbcWPbEs9aRmUcaxD" crossorigin="anonymous"></script>
            </body>
            </html>
            """;

    private static final String REDOC_CSP = "default-src 'none'; script-src https://cdn.jsdelivr.net; style-src 'unsafe-inline'; "
            + "img-src 'self' data: https:; font-src data: https:; connect-src 'self'; worker-src blob:; frame-ancestors 'none'";
}
