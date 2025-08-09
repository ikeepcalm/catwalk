package dev.ua.ikeepcalm.catwalk.common.database;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DatabaseConfig {
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    @Builder.Default
    private int poolSize = 10;

    @Builder.Default
    private int minIdle = 2;

    @Builder.Default
    private long connectionTimeout = 30000; // 30 seconds

    @Builder.Default
    private long idleTimeout = 600000; // 10 minutes

    @Builder.Default
    private long maxLifetime = 1800000; // 30 minutes

    public static DatabaseConfig fromBukkitConfig(org.bukkit.configuration.file.FileConfiguration config) {
        return DatabaseConfig.builder()
                .host(config.getString("database.host", "localhost"))
                .port(config.getInt("database.port", 3306))
                .database(config.getString("database.name", "catwalk_network"))
                .username(config.getString("database.username", "catwalk"))
                .password(config.getString("database.password", ""))
                .poolSize(config.getInt("database.pool-size", 10))
                .minIdle(config.getInt("database.min-idle", 2))
                .connectionTimeout(config.getLong("database.connection-timeout", 30000))
                .idleTimeout(config.getLong("database.idle-timeout", 600000))
                .maxLifetime(config.getLong("database.max-lifetime", 1800000))
                .build();
    }
}