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
        initializeSchema();
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

    private void initializeSchema() {
        try {
            SchemaInitializer schemaInitializer = new SchemaInitializer(this);
            if (!schemaInitializer.isSchemaInitialized()) {
                log.info("[DatabaseManager] Database schema not found, initializing...");
                schemaInitializer.initializeSchema();
            } else {
                log.info("[DatabaseManager] Database schema already initialized");
            }
        } catch (Exception e) {
            log.error("[DatabaseManager] Failed to initialize database schema: {}", e.getMessage(), e);
            throw new RuntimeException("Database schema initialization failed", e);
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
            String errorDetails = formatSQLError(e, sql);
            log.error("[DatabaseManager] Query execution failed: {}", errorDetails, e);
            throw new DatabaseException("Query execution failed: " + errorDetails, e);
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
            String errorDetails = formatSQLError(e, sql);
            log.error("[DatabaseManager] Update execution failed: {}", errorDetails, e);
            throw new DatabaseException("Update execution failed: " + errorDetails, e);
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
            String errorDetails = formatSQLError(e, "Transaction");
            log.error("[DatabaseManager] Transaction execution failed: {}", errorDetails, e);
            throw new DatabaseException("Transaction execution failed: " + errorDetails, e);
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
            String errorDetails = formatSQLError(e, sql);
            log.error("[DatabaseManager] Batch execution failed: {}", errorDetails, e);
            throw new DatabaseException("Batch execution failed: " + errorDetails, e);
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

    /**
     * Format SQL error details for better debugging
     */
    private String formatSQLError(SQLException e, String sql) {
        StringBuilder details = new StringBuilder();
        details.append("SQL State: ").append(e.getSQLState()).append(", ");
        details.append("Error Code: ").append(e.getErrorCode()).append(", ");
        details.append("Message: ").append(e.getMessage());
        
        if (sql != null && !sql.isEmpty()) {
            // Truncate SQL for readability
            String truncatedSQL = sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
            details.append(", SQL: ").append(truncatedSQL);
        }
        
        return details.toString();
    }

    /**
     * Check if an SQLException indicates a recoverable error
     */
    private boolean isRecoverableError(SQLException e) {
        String sqlState = e.getSQLState();
        int errorCode = e.getErrorCode();
        
        // Connection errors that might be temporary
        if (sqlState != null) {
            return sqlState.startsWith("08")    // Connection exception
                || sqlState.startsWith("40")    // Transaction rollback
                || sqlState.equals("HY000");    // General error that might be temporary
        }
        
        // MariaDB/MySQL specific error codes for temporary issues
        return errorCode == 1205    // Lock wait timeout
            || errorCode == 1213    // Deadlock
            || errorCode == 2006    // MySQL server has gone away
            || errorCode == 2013;   // Lost connection to MySQL server
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
        private final String sqlState;
        private final int errorCode;
        private final boolean recoverable;
        
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
            
            if (cause instanceof SQLException sqlException) {
                this.sqlState = sqlException.getSQLState();
                this.errorCode = sqlException.getErrorCode();
                this.recoverable = isRecoverableFromSQLException(sqlException);
            } else {
                this.sqlState = null;
                this.errorCode = 0;
                this.recoverable = false;
            }
        }
        
        public String getSqlState() {
            return sqlState;
        }
        
        public int getErrorCode() {
            return errorCode;
        }
        
        public boolean isRecoverable() {
            return recoverable;
        }
        
        private boolean isRecoverableFromSQLException(SQLException e) {
            String sqlState = e.getSQLState();
            int errorCode = e.getErrorCode();
            
            if (sqlState != null) {
                return sqlState.startsWith("08")    // Connection exception
                    || sqlState.startsWith("40")    // Transaction rollback
                    || sqlState.equals("HY000");    // General error that might be temporary
            }
            
            return errorCode == 1205    // Lock wait timeout
                || errorCode == 1213    // Deadlock
                || errorCode == 2006    // MySQL server has gone away
                || errorCode == 2013;   // Lost connection to MySQL server
        }
    }
}