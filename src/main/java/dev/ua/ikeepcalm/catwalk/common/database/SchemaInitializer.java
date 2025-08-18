package dev.ua.ikeepcalm.catwalk.common.database;

import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles automatic database schema initialization from SQL files.
 */
public class SchemaInitializer {
    
    private final DatabaseManager databaseManager;
    
    public SchemaInitializer(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * Initialize the database schema from the schema.sql file in resources.
     * This method will create tables if they don't exist and handle SQL statements
     * that may fail due to permissions (like event scheduler setup).
     */
    public void initializeSchema() {
        CatWalkLogger.info("[SchemaInitializer] Starting database schema initialization...");
        
        try {
            // Check database version compatibility
            checkDatabaseCompatibility();
            
            String schemaSQL = loadSchemaFromResources();
            if (schemaSQL == null || schemaSQL.trim().isEmpty()) {
                CatWalkLogger.warn("[SchemaInitializer] No schema.sql found or file is empty");
                return;
            }
            
            List<String> sqlStatements = parseSQLStatements(schemaSQL);
            
            if (sqlStatements.isEmpty()) {
                CatWalkLogger.warn("[SchemaInitializer] No valid SQL statements found in schema");
                return;
            }
            
            executeSchemaStatements(sqlStatements);
            
            CatWalkLogger.success("[SchemaInitializer] Database schema initialized successfully");
            
        } catch (Exception e) {
            CatWalkLogger.error("[SchemaInitializer] Failed to initialize database schema: %s", e, e.getMessage());
            
            // Provide helpful guidance based on the error
            if (e.getMessage().contains("syntax error") || e.getMessage().contains("SQL syntax")) {
                CatWalkLogger.error("This appears to be a SQL syntax compatibility issue.");
                CatWalkLogger.error("Please ensure you're using MariaDB 10.1+ or MySQL 5.7+");
            } else if (e.getMessage().contains("Access denied") || e.getMessage().contains("permission")) {
                CatWalkLogger.error("This appears to be a database permission issue.");
                CatWalkLogger.error("Ensure your database user has CREATE, ALTER, INDEX permissions");
            } else if (e.getMessage().contains("TIMESTAMP column with CURRENT_TIMESTAMP") || 
                       e.getMessage().contains("Incorrect table definition")) {
                CatWalkLogger.error("This appears to be a TIMESTAMP column limitation in older MySQL/MariaDB.");
                CatWalkLogger.error("Please ensure you're using MySQL 5.6.5+ or MariaDB 10.0+");
                CatWalkLogger.error("Or update your schema to use DATETIME instead of multiple TIMESTAMP columns");
            } else if (e.getMessage().contains("Invalid default value") && e.getMessage().contains("created_at")) {
                CatWalkLogger.error("This appears to be a DATETIME DEFAULT CURRENT_TIMESTAMP compatibility issue.");
                CatWalkLogger.error("Your MariaDB version doesn't support DEFAULT CURRENT_TIMESTAMP for DATETIME columns.");
                CatWalkLogger.error("Please ensure you're using MariaDB 10.0+ or MySQL 5.6+");
            }
            
            throw new RuntimeException("Database schema initialization failed", e);
        }
    }
    
    /**
     * Check database version compatibility
     */
    private void checkDatabaseCompatibility() {
        try {
            String version = databaseManager.executeQuery(
                "SELECT VERSION()",
                null,
                rs -> {
                    rs.next();
                    return rs.getString(1);
                }
            );
            
            CatWalkLogger.info("[SchemaInitializer] Database version: %s", version);
            
            // Check for known compatibility issues
            if (version.toLowerCase().contains("mariadb")) {
                // Extract MariaDB version
                String[] parts = version.split("-");
                if (parts.length > 0) {
                    String versionNumber = parts[0];
                    CatWalkLogger.debug("[SchemaInitializer] Detected MariaDB version: %s", versionNumber);
                }
            } else if (version.toLowerCase().contains("mysql")) {
                CatWalkLogger.debug("[SchemaInitializer] Detected MySQL version: %s", version);
            }
            
        } catch (Exception e) {
            CatWalkLogger.warn("[SchemaInitializer] Could not determine database version: %s", e.getMessage());
            // Continue anyway - version check is not critical
        }
    }
    
    /**
     * Load the schema.sql file from the resources folder.
     */
    private String loadSchemaFromResources() throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("scheme.sql")) {
            if (inputStream == null) {
                CatWalkLogger.warn("[SchemaInitializer] scheme.sql not found in resources");
                return null;
            }
            
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            return content.toString();
        }
    }
    
    /**
     * Parse the SQL content into individual statements, handling delimiters and comments.
     */
    private List<String> parseSQLStatements(String sqlContent) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        
        String[] lines = sqlContent.split("\n");
        String delimiter = ";";
        boolean inDelimiterBlock = false;
        boolean inMultiLineComment = false;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // Handle multi-line comments
            if (trimmedLine.startsWith("/*")) {
                inMultiLineComment = true;
                if (trimmedLine.endsWith("*/")) {
                    inMultiLineComment = false;
                }
                continue;
            }
            if (inMultiLineComment) {
                if (trimmedLine.endsWith("*/")) {
                    inMultiLineComment = false;
                }
                continue;
            }
            
            // Skip empty lines and single-line comments
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("--")) {
                continue;
            }
            
            // Handle DELIMITER statements
            if (trimmedLine.toUpperCase().startsWith("DELIMITER")) {
                if (trimmedLine.contains("//")) {
                    delimiter = "//";
                    inDelimiterBlock = true;
                } else if (trimmedLine.contains(";")) {
                    delimiter = ";";
                    inDelimiterBlock = false;
                }
                continue;
            }
            
            // Add line to current statement with proper spacing
            if (currentStatement.length() > 0) {
                currentStatement.append(" ");
            }
            currentStatement.append(trimmedLine);
            
            // Check if statement ends with current delimiter
            if (trimmedLine.endsWith(delimiter)) {
                String statement = currentStatement.toString().trim();
                
                // Remove the delimiter from the end
                if (statement.endsWith(delimiter)) {
                    statement = statement.substring(0, statement.length() - delimiter.length()).trim();
                }
                
                if (!statement.isEmpty()) {
                    statements.add(statement);
                    CatWalkLogger.debug("[SchemaInitializer] Parsed statement: %s", 
                        statement.length() > 80 ? statement.substring(0, 80) + "..." : statement);
                }
                currentStatement.setLength(0);
            }
        }
        
        // Add any remaining statement
        String remaining = currentStatement.toString().trim();
        if (!remaining.isEmpty()) {
            // Remove delimiter if present
            if (remaining.endsWith(delimiter)) {
                remaining = remaining.substring(0, remaining.length() - delimiter.length()).trim();
            }
            if (!remaining.isEmpty()) {
                statements.add(remaining);
                CatWalkLogger.debug("[SchemaInitializer] Parsed final statement: %s", 
                    remaining.length() > 80 ? remaining.substring(0, 80) + "..." : remaining);
            }
        }
        
        CatWalkLogger.info("[SchemaInitializer] Parsed %d SQL statements from schema", statements.size());
        return statements;
    }
    
    /**
     * Execute the schema statements with proper error handling for statements that may fail.
     */
    private void executeSchemaStatements(List<String> statements) {
        try (Connection connection = databaseManager.getConnection()) {
            for (String sql : statements) {
                executeStatementWithErrorHandling(connection, sql);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute schema statements", e);
        }
    }
    
    /**
     * Execute a single SQL statement with appropriate error handling.
     * Some statements (like event scheduler setup) may fail due to permissions,
     * which should not stop the entire initialization process.
     */
    private void executeStatementWithErrorHandling(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            String trimmedSQL = sql.trim().toUpperCase();
            
            // Check if this is a potentially problematic statement
            boolean isOptionalStatement = isOptionalStatement(trimmedSQL);
            
            statement.execute(sql);
            
            if (isOptionalStatement) {
                CatWalkLogger.success("[SchemaInitializer] Optional statement executed successfully: %s", 
                    getStatementDescription(trimmedSQL));
            } else {
                CatWalkLogger.debug("[SchemaInitializer] Executed: %s", getStatementDescription(trimmedSQL));
            }
            
        } catch (SQLException e) {
            String trimmedSQL = sql.trim().toUpperCase();
            boolean isOptionalStatement = isOptionalStatement(trimmedSQL);
            
            if (isOptionalStatement) {
                CatWalkLogger.warn("[SchemaInitializer] Optional statement failed (this is usually okay): %s - %s", 
                    getStatementDescription(trimmedSQL), e.getMessage());
            } else {
                CatWalkLogger.error("[SchemaInitializer] Critical statement failed: %s - %s", 
                    getStatementDescription(trimmedSQL), e.getMessage());
                throw new RuntimeException("Critical database statement failed: " + getStatementDescription(trimmedSQL), e);
            }
        }
    }
    
    /**
     * Check if a statement is optional and can fail without breaking the initialization.
     */
    private boolean isOptionalStatement(String trimmedSQL) {
        return trimmedSQL.contains("@@GLOBAL.EVENT_SCHEDULER") ||
               trimmedSQL.startsWith("CREATE EVENT") ||
               trimmedSQL.startsWith("SET @@") ||
               trimmedSQL.contains("EVENT_SCHEDULER");
    }
    
    /**
     * Get a human-readable description of the SQL statement type.
     */
    private String getStatementDescription(String trimmedSQL) {
        if (trimmedSQL.startsWith("CREATE TABLE")) {
            // Extract table name
            String[] parts = trimmedSQL.split("\\s+");
            if (parts.length >= 3) {
                String tableName = parts[2];
                if (tableName.toUpperCase().startsWith("IF NOT EXISTS")) {
                    tableName = parts.length >= 6 ? parts[5] : "unknown";
                }
                return "CREATE TABLE " + tableName;
            }
            return "CREATE TABLE";
        } else if (trimmedSQL.startsWith("CREATE EVENT")) {
            String[] parts = trimmedSQL.split("\\s+");
            if (parts.length >= 3) {
                String eventName = parts[2];
                if (eventName.toUpperCase().startsWith("IF NOT EXISTS")) {
                    eventName = parts.length >= 6 ? parts[5] : "unknown";
                }
                return "CREATE EVENT " + eventName;
            }
            return "CREATE EVENT";
        } else if (trimmedSQL.contains("@@GLOBAL.EVENT_SCHEDULER")) {
            return "SET EVENT_SCHEDULER";
        } else if (trimmedSQL.startsWith("SET @@")) {
            return "SET GLOBAL VARIABLE";
        } else {
            // Truncate long statements for logging
            String description = trimmedSQL.length() > 50 ? 
                trimmedSQL.substring(0, 50) + "..." : trimmedSQL;
            return description;
        }
    }
    
    /**
     * Check if the database schema appears to be already initialized.
     * This is a basic check to see if core tables exist.
     */
    public boolean isSchemaInitialized() {
        try {
            // Try to query a core table to see if it exists
            databaseManager.executeQuery(
                "SELECT COUNT(*) FROM servers LIMIT 1",
                null,
                rs -> {
                    rs.next();
                    return rs.getInt(1);
                }
            );
            CatWalkLogger.debug("[SchemaInitializer] Schema validation successful - tables exist");
            return true;
        } catch (Exception e) {
            // If the query fails, the table probably doesn't exist
            CatWalkLogger.debug("[SchemaInitializer] Schema validation failed: %s - assuming tables need creation", e.getMessage());
            return false;
        }
    }
    
    /**
     * Force schema re-initialization (useful for testing or recovery).
     */
    public void reinitializeSchema() {
        CatWalkLogger.info("[SchemaInitializer] Force re-initializing database schema...");
        initializeSchema();
    }
}