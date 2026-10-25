package dev.ua.ikeepcalm.catwalk.common.database;

import lombok.Builder;
import lombok.Data;
import org.bukkit.configuration.file.FileConfiguration;

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
    private long connectionTimeout = 30000;
    @Builder.Default
    private long idleTimeout = 600000;
    @Builder.Default
    private long maxLifetime = 1800000;

    public static DatabaseConfig fromBukkitConfig(FileConfiguration config) {
        return DatabaseConfig.builder()
                .host(config.getString("database.host"))
                .port(config.getInt("database.port"))
                .database(config.getString("database.name"))
                .username(config.getString("database.username"))
                .password(config.getString("database.password"))
                .poolSize(config.getInt("database.pool-size"))
                .minIdle(config.getInt("database.min-idle"))
                .connectionTimeout(config.getLong("database.connection-timeout"))
                .idleTimeout(config.getLong("database.idle-timeout"))
                .maxLifetime(config.getLong("database.max-lifetime"))
                .build();
    }
}
