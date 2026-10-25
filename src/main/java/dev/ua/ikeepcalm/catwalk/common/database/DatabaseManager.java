package dev.ua.ikeepcalm.catwalk.common.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;
import lombok.Getter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseManager {

    private final HikariDataSource dataSource;
    private final ExecutorService asyncExecutor;

    public DatabaseManager(DatabaseConfig config) {
        CatWalkLogger.info("Connecting to database %s:%d/%s", config.getHost(), config.getPort(), config.getDatabase());
        this.dataSource = new HikariDataSource(hikariConfig(config));
        this.asyncExecutor = Executors.newFixedThreadPool(config.getPoolSize());

        try {
            SchemaInitializer schemaInitializer = new SchemaInitializer(this);
            if (!schemaInitializer.isSchemaInitialized()) {
                schemaInitializer.initializeSchema();
            }
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    private static HikariConfig hikariConfig(DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("CatWalk");
        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s", config.getHost(), config.getPort(), config.getDatabase()));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(config.getPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());
        hikariConfig.setValidationTimeout(5000);
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        return hikariConfig;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public <T> T executeQuery(String sql, ParameterSetter parameterSetter, ResultMapper<T> mapper) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (parameterSetter != null) {
                parameterSetter.setParameters(stmt);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return mapper.map(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Query failed: " + describe(e, sql), e);
        }
    }

    public int executeUpdate(String sql, ParameterSetter parameterSetter) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (parameterSetter != null) {
                parameterSetter.setParameters(stmt);
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Update failed: " + describe(e, sql), e);
        }
    }

    public <T> CompletableFuture<T> executeQueryAsync(String sql, ParameterSetter parameterSetter, ResultMapper<T> mapper) {
        return CompletableFuture.supplyAsync(() -> executeQuery(sql, parameterSetter, mapper), asyncExecutor);
    }

    public CompletableFuture<Integer> executeUpdateAsync(String sql, ParameterSetter parameterSetter) {
        return CompletableFuture.supplyAsync(() -> executeUpdate(sql, parameterSetter), asyncExecutor);
    }

    public void shutdown() {
        asyncExecutor.shutdown();
        dataSource.close();
    }

    private static String describe(SQLException e, String sql) {
        String shortSql = sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
        return String.format("[%s/%d] %s, SQL: %s", e.getSQLState(), e.getErrorCode(), e.getMessage(), shortSql.strip());
    }

    @FunctionalInterface
    public interface ParameterSetter {
        void setParameters(PreparedStatement stmt) throws SQLException;
    }

    @FunctionalInterface
    public interface ResultMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @Getter
    public static class DatabaseException extends RuntimeException {
        private final String sqlState;

        public DatabaseException(String message, SQLException cause) {
            super(message, cause);
            this.sqlState = cause.getSQLState();
        }
    }
}
