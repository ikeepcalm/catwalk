package dev.ua.ikeepcalm.catwalk.database;

import dev.ua.ikeepcalm.catwalk.common.database.DatabaseManager;
import dev.ua.ikeepcalm.catwalk.common.database.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SchemaInitializer SQL parsing logic.
 * These tests don't require a database connection.
 */
class SchemaInitializerUnitTest {
    
    private SchemaInitializer schemaInitializer;
    
    @BeforeEach
    void setUp() {
        // Create SchemaInitializer with null DatabaseManager for testing static methods
        schemaInitializer = new SchemaInitializer(null);
    }
    
    @Test
    void testParseSQLStatements() throws Exception {
        String testSQL = """
            -- Test SQL content
            CREATE TABLE IF NOT EXISTS test_table (
                id INT PRIMARY KEY,
                name VARCHAR(100)
            );
            
            INSERT INTO test_table (id, name) VALUES (1, 'test');
            
            -- Enable event scheduler
            SET @@global.event_scheduler = 1;
            
            DELIMITER //
            CREATE EVENT test_event
            ON SCHEDULE EVERY 1 HOUR
            DO
            BEGIN
                DELETE FROM test_table WHERE id = 999;
            END //
            DELIMITER ;
            """;
        
        // Use reflection to access private method
        Method parseMethod = SchemaInitializer.class.getDeclaredMethod("parseSQLStatements", String.class);
        parseMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) parseMethod.invoke(schemaInitializer, testSQL);
        
        assertNotNull(statements);
        assertFalse(statements.isEmpty());
        
        // Check that statements are properly parsed
        boolean foundCreateTable = false;
        boolean foundInsert = false;
        boolean foundSetVariable = false;
        boolean foundCreateEvent = false;
        
        for (String statement : statements) {
            String trimmed = statement.trim().toUpperCase();
            if (trimmed.startsWith("CREATE TABLE")) {
                foundCreateTable = true;
            } else if (trimmed.startsWith("INSERT INTO")) {
                foundInsert = true;
            } else if (trimmed.contains("@@GLOBAL.EVENT_SCHEDULER")) {
                foundSetVariable = true;
            } else if (trimmed.startsWith("CREATE EVENT")) {
                foundCreateEvent = true;
            }
        }
        
        assertTrue(foundCreateTable, "Should find CREATE TABLE statement");
        assertTrue(foundInsert, "Should find INSERT statement");
        assertTrue(foundSetVariable, "Should find SET variable statement");
        assertTrue(foundCreateEvent, "Should find CREATE EVENT statement");
    }
    
    @Test
    void testIsOptionalStatement() throws Exception {
        // Use reflection to access private method
        Method isOptionalMethod = SchemaInitializer.class.getDeclaredMethod("isOptionalStatement", String.class);
        isOptionalMethod.setAccessible(true);
        
        // Test optional statements
        assertTrue((Boolean) isOptionalMethod.invoke(schemaInitializer, "SET @@global.event_scheduler = 1"));
        assertTrue((Boolean) isOptionalMethod.invoke(schemaInitializer, "CREATE EVENT test_event ON SCHEDULE EVERY 1 HOUR"));
        assertTrue((Boolean) isOptionalMethod.invoke(schemaInitializer, "SET @@GLOBAL.SQL_MODE = 'TRADITIONAL'"));
        
        // Test required statements
        assertFalse((Boolean) isOptionalMethod.invoke(schemaInitializer, "CREATE TABLE test (id INT)"));
        assertFalse((Boolean) isOptionalMethod.invoke(schemaInitializer, "INSERT INTO test VALUES (1)"));
        assertFalse((Boolean) isOptionalMethod.invoke(schemaInitializer, "SELECT * FROM test"));
    }
    
    @Test
    void testGetStatementDescription() throws Exception {
        // Use reflection to access private method
        Method getDescMethod = SchemaInitializer.class.getDeclaredMethod("getStatementDescription", String.class);
        getDescMethod.setAccessible(true);
        
        // Test CREATE TABLE
        String desc1 = (String) getDescMethod.invoke(schemaInitializer, "CREATE TABLE IF NOT EXISTS servers");
        assertTrue(desc1.contains("CREATE TABLE"));
        assertTrue(desc1.contains("servers"));
        
        // Test CREATE EVENT
        String desc2 = (String) getDescMethod.invoke(schemaInitializer, "CREATE EVENT IF NOT EXISTS cleanup_event");
        assertTrue(desc2.contains("CREATE EVENT"));
        assertTrue(desc2.contains("cleanup_event"));
        
        // Test SET statement
        String desc3 = (String) getDescMethod.invoke(schemaInitializer, "SET @@global.event_scheduler = 1");
        assertTrue(desc3.contains("SET EVENT_SCHEDULER"));
        
        // Test long statement truncation
        String longStatement = "SELECT * FROM very_long_table_name WHERE column1 = 'value' AND column2 = 'another_value' AND column3 = 'yet_another_value'";
        String desc4 = (String) getDescMethod.invoke(schemaInitializer, longStatement);
        assertTrue(desc4.length() <= 53); // 50 chars + "..."
        assertTrue(desc4.endsWith("..."));
    }
    
    @Test
    void testLoadSchemaFromResources() throws Exception {
        // Use reflection to access private method
        Method loadMethod = SchemaInitializer.class.getDeclaredMethod("loadSchemaFromResources");
        loadMethod.setAccessible(true);
        
        String schemaContent = (String) loadMethod.invoke(schemaInitializer);
        
        // The actual scheme.sql file should be loaded
        assertNotNull(schemaContent);
        assertFalse(schemaContent.trim().isEmpty());
        
        // Should contain expected content
        assertTrue(schemaContent.contains("CREATE TABLE"));
        assertTrue(schemaContent.contains("servers"));
        assertTrue(schemaContent.contains("server_addons"));
    }
    
    @Test
    void testSQLStatementParsing_ComplexCase() throws Exception {
        String complexSQL = """
            -- Complex test case with various statement types
            CREATE TABLE test1 (id INT);
            
            /* Multi-line comment
               with various content */
            CREATE TABLE test2 (
                id INT PRIMARY KEY,
                data JSON
            );
            
            DELIMITER //
            CREATE PROCEDURE test_proc()
            BEGIN
                DECLARE done INT DEFAULT FALSE;
                SELECT COUNT(*) FROM test1;
            END //
            DELIMITER ;
            
            INSERT INTO test1 VALUES (1), (2), (3);
            """;
        
        Method parseMethod = SchemaInitializer.class.getDeclaredMethod("parseSQLStatements", String.class);
        parseMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) parseMethod.invoke(schemaInitializer, complexSQL);
        
        assertNotNull(statements);
        assertTrue(statements.size() >= 3); // Should have at least CREATE TABLE, CREATE PROCEDURE, INSERT
        
        // Verify statements are correctly separated
        boolean foundCreateTable1 = false;
        boolean foundCreateTable2 = false;
        boolean foundCreateProcedure = false;
        boolean foundInsert = false;
        
        for (String statement : statements) {
            String trimmed = statement.trim().toUpperCase();
            if (trimmed.contains("CREATE TABLE TEST1")) {
                foundCreateTable1 = true;
            } else if (trimmed.contains("CREATE TABLE TEST2")) {
                foundCreateTable2 = true;
            } else if (trimmed.contains("CREATE PROCEDURE")) {
                foundCreateProcedure = true;
            } else if (trimmed.contains("INSERT INTO TEST1")) {
                foundInsert = true;
            }
        }
        
        assertTrue(foundCreateTable1, "Should find CREATE TABLE test1");
        assertTrue(foundCreateTable2, "Should find CREATE TABLE test2");
        assertTrue(foundCreateProcedure, "Should find CREATE PROCEDURE");
        assertTrue(foundInsert, "Should find INSERT statement");
    }
}