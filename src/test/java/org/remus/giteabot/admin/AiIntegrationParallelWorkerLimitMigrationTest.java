package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration gate for the {@code ai_integrations.parallel_worker_limit} column:
 * the entity maps it as a non-null {@code int}, so a database that never
 * received the column fails validation ({@code ddl-auto=validate}) and a
 * database that received it without the default would leave existing
 * integrations unable to persist a job.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled). It migrates to the previous version, persists a legacy
 * integration, then migrates to the latest version and asserts the column was
 * added with the default {@code 0}.</p>
 */
class AiIntegrationParallelWorkerLimitMigrationTest {

    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    private static Flyway flyway(String url, Integer target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(LOCATIONS);
        if (target != null) {
            configuration = configuration.target(target.toString());
        }
        return configuration.load();
    }

    @Test
    void migrationAssignsZeroToExistingIntegrations() throws Exception {
        String url = "jdbc:h2:mem:ai-parallel-limit-upgrade;DB_CLOSE_DELAY=-1";
        flyway(url, 54).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO ai_integrations "
                    + "(name, provider_type, api_url, model, created_at, updated_at) VALUES "
                    + "('legacy', 'anthropic', 'https://api.anthropic.com', 'claude-sonnet-4', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        flyway(url, 55).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT parallel_worker_limit FROM ai_integrations WHERE name = 'legacy'")) {
            assertTrue(result.next(), "the legacy integration must still exist");
            assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void columnIsNotNullableAfterTheMigration() throws Exception {
        String url = "jdbc:h2:mem:ai-parallel-limit-schema;DB_CLOSE_DELAY=-1";
        flyway(url, null).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT is_nullable FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'AI_INTEGRATIONS' "
                             + "AND COLUMN_NAME = 'PARALLEL_WORKER_LIMIT'")) {
            assertTrue(result.next(), "the parallel_worker_limit column must exist");
            assertEquals("NO", result.getString(1));
        }
    }
}
