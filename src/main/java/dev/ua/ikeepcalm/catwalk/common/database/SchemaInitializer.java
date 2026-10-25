package dev.ua.ikeepcalm.catwalk.common.database;

import dev.ua.ikeepcalm.catwalk.common.utils.CatWalkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SchemaInitializer {

    private static final Pattern CREATE_OBJECT = Pattern.compile("^CREATE\\s+(TABLE|EVENT)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);

    private final DatabaseManager databaseManager;

    public SchemaInitializer(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void initializeSchema() {
        List<String> statements = parseSQLStatements(loadSchemaFromResources());
        CatWalkLogger.info("Initializing database schema (%d statements)", statements.size());

        try (Connection connection = databaseManager.getConnection()) {
            for (String sql : statements) {
                execute(connection, sql);
            }
        } catch (SQLException e) {
            throw new DatabaseManager.DatabaseException("Schema initialization failed: " + e.getMessage(), e);
        }
    }

    public boolean isSchemaInitialized() {
        try {
            databaseManager.executeQuery("SELECT 1 FROM servers LIMIT 1", null, rs -> null);
            return true;
        } catch (DatabaseManager.DatabaseException e) {
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            CatWalkLogger.debug("Executed %s", getStatementDescription(sql));
        } catch (SQLException e) {
            if (!isOptionalStatement(sql)) {
                throw e;
            }
            CatWalkLogger.warn("Skipped %s (needs privileges CatWalk may not have, old rows will not be cleaned up automatically): %s",
                    getStatementDescription(sql), e.getMessage());
        }
    }

    private String loadSchemaFromResources() {
        try (InputStream inputStream = SchemaInitializer.class.getClassLoader().getResourceAsStream("scheme.sql")) {
            if (inputStream == null) {
                throw new IllegalStateException("scheme.sql is missing from the plugin jar");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> parseSQLStatements(String sqlContent) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        boolean inComment = false;

        for (String rawLine : sqlContent.split("\n")) {
            String line = rawLine.trim();
            if (inComment || line.startsWith("/*")) {
                inComment = !line.endsWith("*/");
                continue;
            }
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            if (line.toUpperCase().startsWith("DELIMITER")) {
                delimiter = line.substring("DELIMITER".length()).trim();
                continue;
            }

            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(line);

            if (line.endsWith(delimiter)) {
                addStatement(statements, current, delimiter);
            }
        }
        addStatement(statements, current, delimiter);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current, String delimiter) {
        String statement = current.toString().trim();
        if (statement.endsWith(delimiter)) {
            statement = statement.substring(0, statement.length() - delimiter.length()).trim();
        }
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private boolean isOptionalStatement(String sql) {
        String upper = sql.trim().toUpperCase();
        return upper.startsWith("CREATE EVENT") || upper.startsWith("SET @@");
    }

    private String getStatementDescription(String sql) {
        String trimmed = sql.trim();
        Matcher matcher = CREATE_OBJECT.matcher(trimmed);
        if (matcher.find()) {
            return "CREATE " + matcher.group(1).toUpperCase() + " " + matcher.group(2);
        }
        if (trimmed.toUpperCase().contains("@@GLOBAL.EVENT_SCHEDULER")) {
            return "SET EVENT_SCHEDULER";
        }
        return trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed;
    }
}
