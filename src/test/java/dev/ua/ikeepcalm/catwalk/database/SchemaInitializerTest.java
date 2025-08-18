package dev.ua.ikeepcalm.catwalk.database;

import dev.ua.ikeepcalm.catwalk.common.database.DatabaseConfig;
import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for SchemaInitializer functionality.
 * Note: This test requires a running MariaDB/MySQL database for integration testing.
 * Disabled by default - enable for manual testing with a real database.
 */
@Disabled("Integration test - requires database connection")
class SchemaInitializerTest {
    
    private DatabaseManager databaseManager;
    private SchemaInitializer schemaInitializer;
    
    @BeforeEach
    void setUp() {
        // Setup test database configuration
        DatabaseConfig testConfig = DatabaseConfig.builder()
                .host("localhost")
                .port(3306)
                .database("catwalk_test")
                .username("test_user")
                .password("test_password")
                .poolSize(2)
                .minIdle(1)
                .build();
        
        try {
            this.databaseManager = new DatabaseManager(testConfig);
            this.schemaInitializer = new SchemaInitializer(databaseManager);
        } catch (Exception e) {
            // Skip test if database connection fails
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Database connection failed: " + e.getMessage());
        }
    }
    
    @Test
    void testSchemaInitialization() {
        // Test that schema can be initialized
        assertDoesNotThrow(() -> schemaInitializer.initializeSchema());
        
        // Test that schema is detected as initialized
        assertTrue(schemaInitializer.isSchemaInitialized());
        
        // Verify core tables exist
        assertTableExists("servers");
        assertTableExists("server_addons");
        assertTableExists("request_queue");
        assertTableExists("response_queue");
        assertTableExists("network_metrics");
        assertTableExists("addon_configs");
    }
    
    @Test
    void testSchemaReinitialization() {
        // Initialize schema first
        schemaInitializer.initializeSchema();
        assertTrue(schemaInitializer.isSchemaInitialized());
        
        // Reinitialize should not fail
        assertDoesNotThrow(() -> schemaInitializer.reinitializeSchema());
        assertTrue(schemaInitializer.isSchemaInitialized());
    }
    
    @Test
    void testTableStructure() {
        schemaInitializer.initializeSchema();
        
        // Test servers table structure
        List<String> serverColumns = getTableColumns("servers");
        assertTrue(serverColumns.contains("server_id"));
        assertTrue(serverColumns.contains("server_name"));
        assertTrue(serverColumns.contains("server_type"));
        assertTrue(serverColumns.contains("host"));
        assertTrue(serverColumns.contains("port"));
        
        // Test server_addons table structure
        List<String> addonColumns = getTableColumns("server_addons");
        assertTrue(addonColumns.contains("server_id"));
        assertTrue(addonColumns.contains("addon_name"));
        assertTrue(addonColumns.contains("endpoints"));
        assertTrue(addonColumns.contains("openapi_spec"));
    }
    
    @Test
    void testBasicCRUDOperations() {
        schemaInitializer.initializeSchema();
        
        // Test inserting a server record
        int insertedRows = databaseManager.executeUpdate(
            "INSERT INTO servers (server_id, server_name, server_type, host, port) VALUES (?, ?, ?, ?, ?)",
            stmt -> {
                stmt.setString(1, "test-server-1");
                stmt.setString(2, "Test Server");
                stmt.setString(3, "backend");
                stmt.setString(4, "localhost");
                stmt.setInt(5, 4567);
            }
        );
        
        assertEquals(1, insertedRows);
        
        // Test querying the inserted record
        String serverName = databaseManager.executeQuery(
            "SELECT server_name FROM servers WHERE server_id = ?",
            stmt -> stmt.setString(1, "test-server-1"),
            rs -> {
                if (rs.next()) {
                    return rs.getString("server_name");
                }
                return null;
            }
        );
        
        assertEquals("Test Server", serverName);
        
        // Cleanup
        databaseManager.executeUpdate(
            "DELETE FROM servers WHERE server_id = ?",
            stmt -> stmt.setString(1, "test-server-1")
        );
    }
    
    private void assertTableExists(String tableName) {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TABLES LIKE '" + tableName + "'")) {
            
            assertTrue(rs.next(), "Table " + tableName + " should exist");
            
        } catch (Exception e) {
            fail("Failed to check if table " + tableName + " exists: " + e.getMessage());
        }
    }
    
    private List<String> getTableColumns(String tableName) {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM " + tableName)) {
            
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString("Field"));
            }
            return columns;
            
        } catch (Exception e) {
            fail("Failed to get columns for table " + tableName + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }
}