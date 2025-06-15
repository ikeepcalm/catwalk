package dev.ua.uaproject.catwalk.webserver;

import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.utils.json.GsonJsonMapper;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
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
    private static final String[] noAuthPaths = new String[]{"/swagger", "/openapi", "/redoc", "/plugins"};

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

    @Getter
    private OpenApiPlugin openApiPlugin;

    public WebServer(CatWalkMain main, FileConfiguration bukkitConfig, Logger logger) {
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

        this.javalin = Javalin.create(config -> configureJavalin(config, main));

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
                        log.info("[CatWalk] Auth successful via Bearer token for: " + ctx.req().getPathInfo());
                    }
                    return;
                } else {
                    log.warning("[CatWalk] Invalid Bearer token provided: " + token.substring(0, Math.min(token.length(), 5)) + "...");
                }
            }

            // Check for auth cookie
            String authCookie = ctx.cookie(X_CATWALK_COOKIE);
            if (authCookie != null && Objects.equals(authCookie, authKey)) {
                if (isDebug) {
                    log.info("[CatWalk] Auth successful via cookie for: " + ctx.req().getPathInfo());
                }
                return;
            }

            // Auth failed, log detailed message
            log.warning("[CatWalk] Unauthorized request: " + ctx.req().getPathInfo() +
                    " - Authorization header: " + (authHeader != null ? "present" : "missing"));
            throw new UnauthorizedResponse("Authentication required. Use Bearer token authentication.");
        });

        javalin.get("/swagger", ctx -> ctx.redirect("/swagger.html"));

        if (bukkitConfig.getBoolean("debug")) {
            this.javalin.before(ctx -> log.info(ctx.req().getPathInfo()));
        }
    }

    private void configureJavalin(JavalinConfig config, CatWalkMain main) {
        config.jsonMapper(new GsonJsonMapper());
        config.http.defaultContentType = "application/json";
        config.showJavalinBanner = false;

        configureTLS(config, main);
        configureCors(config);

        if (isAuthEnabled && "change_me".equals(authKey)) {
            log.warning("[CatWalk] AUTH KEY IS SET TO DEFAULT \"change_me\"");
            log.warning("[CatWalk] CHANGE THE key IN THE config.yml FILE");
            log.warning("[CatWalk] FAILURE TO CHANGE THE KEY MAY RESULT IN SERVER COMPROMISE");
        }

        if (!disableSwagger) {
            this.openApiPlugin = new OpenApiPlugin(configuration -> {
                configuration.withDocumentationPath("/openapi.json");
                configuration.withPrettyOutput(true);

                // Configure the OpenAPI security scheme for Bearer authentication
                configuration.withDefinitionConfiguration((version, openApiDefinition) -> {
                    // Basic API info
                    openApiDefinition.withInfo(openApiInfo -> {
                        openApiInfo.description("Catwalk API");
                        openApiInfo.version("1.0.0");
                        openApiInfo.title("Catwalk");
                        openApiInfo.contact("@ikeepcalm");
                    });

                    // Add security schemes if authentication is enabled
//                    if (isAuthEnabled) {
//                        // Apply the security requirement globally
//                        openApiDefinition.withSecurity(SecurityComponentConfiguration::withBearerAuth);
//
//                        // Define the security scheme
//                        openApiDefinition.withSecurity(securityComponentConfiguration -> {
//                            securityComponentConfiguration.withSecurityScheme("bearerAuth", new BearerAuth());
//                        });
//                    }
                });
            });

            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/public";
                staticFiles.hostedPath = "/";
                staticFiles.location = Location.CLASSPATH;
            });
            config.registerPlugin(openApiPlugin);

            // Register Swagger and Redoc plugin
            config.registerPlugin(new SwaggerPlugin(configuration -> {
                configuration.setUiPath("/plainswagger");
            }));
            config.registerPlugin(new ReDocPlugin());

            log.info("[CatWalk] Swagger UI enabled at /swagger");
            log.info("[CatWalk] ReDoc UI enabled at /redoc");
        }
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
                log.info("[CatWalk] Enabling CORS for *");
                corsConfig.anyHost();
            } else {
                corsOrigin.forEach(origin -> {
                    log.info(String.format("[CatWalk] Enabling CORS for %s", origin));
                    corsConfig.allowHost(origin);
                });
            }
        }));
    }

    private void configureTLS(JavalinConfig config, CatWalkMain main) {
        if (!tlsEnabled) {
            log.warning("[CatWalk] TLS is not enabled.");
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
                log.info("[CatWalk] TLS is enabled.");
            } else {
                log.warning(String.format("[CatWalk] TLS is enabled but %s doesn't exist. TLS disabled.", fullKeystorePath));
            }
        } catch (Exception e) {
            log.severe("[CatWalk] Error while enabling TLS: " + e.getMessage());
            log.warning("[CatWalk] TLS is not enabled.");
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