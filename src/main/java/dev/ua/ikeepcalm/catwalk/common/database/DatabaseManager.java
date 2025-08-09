package dev.ua.ikeepcalm.catwalk.common.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class DatabaseManager {

    @Getter
    private HikariDataSource dataSource;
    private final ExecutorService asyncExecutor;

    public DatabaseManager(DatabaseConfig config) {
        this.asyncExecutor = Executors.newFixedThreadPool(config.getPoolSize());
        initializeDataSource(config);
        validateConnection();
    }

    private void initializeDataSource(DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();

        // Connection settings
        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s",
                config.getHost(), config.getPort(), config.getDatabase()));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());

        // Connection pool settings
        hikariConfig.setMaximumPoolSize(config.getPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());

        // Performance settings
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

        // Connection validation
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setValidationTimeout(5000);

        this.dataSource = new HikariDataSource(hikariConfig);

        log.info("[DatabaseManager] Initialized connection pool to {}:{}/{}",
                config.getHost(), config.getPort(), config.getDatabase());
    }

    private void validateConnection() {
        try (Connection connection = getConnection()) {
            if (connection.isValid(5)) {
                log.info("[DatabaseManager] Database connection validated successfully");
            } else {
                throw new RuntimeException("Database connection validation failed");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to validate database connection", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // Synchronous database operations
    public <T> T executeQuery(String sql, ParameterSetter parameterSetter, ResultMapper<T> mapper) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (parameterSetter != null) {
                parameterSetter.setParameters(stmt);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                return mapper.map(rs);
            }
        } catch (SQLException e) {
            log.error("[DatabaseManager] Query execution failed: {}", sql, e);
            throw new DatabaseException("Query execution failed", e);
        }
    }

    public int executeUpdate(String sql, ParameterSetter parameterSetter) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (parameterSetter != null) {
                parameterSetter.setParameters(stmt);
            }

            return stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[DatabaseManager] Update execution failed: {}", sql, e);
            throw new DatabaseException("Update execution failed", e);
        }
    }

    // Asynchronous database operations
    public <T> CompletableFuture<T> executeQueryAsync(String sql, ParameterSetter parameterSetter, ResultMapper<T> mapper) {
        return CompletableFuture.supplyAsync(() -> executeQuery(sql, parameterSetter, mapper), asyncExecutor);
    }

    public CompletableFuture<Integer> executeUpdateAsync(String sql, ParameterSetter parameterSetter) {
        return CompletableFuture.supplyAsync(() -> executeUpdate(sql, parameterSetter), asyncExecutor);
    }

    // Transaction support
    public <T> T executeInTransaction(TransactionCallback<T> callback) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = callback.execute(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("[DatabaseManager] Transaction execution failed", e);
            throw new DatabaseException("Transaction execution failed", e);
        }
    }

    public CompletableFuture<Void> executeInTransactionAsync(TransactionVoidCallback callback) {
        return CompletableFuture.runAsync(() -> {
            executeInTransaction(conn -> {
                callback.execute(conn);
                return null;
            });
        }, asyncExecutor);
    }

    // Batch operations
    public void executeBatch(String sql, BatchParameterSetter batchSetter) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            batchSetter.setParameters(stmt);
            stmt.executeBatch();

        } catch (SQLException e) {
            log.error("[DatabaseManager] Batch execution failed: {}", sql, e);
            throw new DatabaseException("Batch execution failed", e);
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[DatabaseManager] Connection pool shut down");
        }

        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
            log.info("[DatabaseManager] Async executor shut down");
        }
    }

    // Functional interfaces for database operations
    @FunctionalInterface
    public interface ParameterSetter {
        void setParameters(PreparedStatement stmt) throws SQLException;
    }

    @FunctionalInterface
    public interface ResultMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T execute(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionVoidCallback {
        void execute(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface BatchParameterSetter {
        void setParameters(PreparedStatement stmt) throws SQLException;
    }

    // Custom exception for database operations
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}