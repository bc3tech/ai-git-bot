package org.remus.giteabot.review.state;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewStateMigrationTest {

    private static final String URL = "jdbc:h2:mem:review-state-migration-test;DB_CLOSE_DELAY=-1";

    @Test
    void v58CreatesThePairedReviewStateTablesAndIndexes() throws Exception {
        flyway("57").migrate();
        flyway("58").migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(tableExists(connection, "REVIEW_FINDING")).isTrue();
            assertThat(tableExists(connection, "REVIEW_REVISION")).isTrue();
            assertThat(tableExists(connection, "REVIEW_RUN_LEASE")).isTrue();
            statement.execute("INSERT INTO review_run_lease (bot_id, repo_owner, repo_name, pr_number, lease_token) "
                    + "VALUES (1, 'owner', 'repo', 2, 0)");
            statement.execute("INSERT INTO review_revision (bot_id, repo_owner, repo_name, pr_number, workflow_key, "
                    + "reviewed_at, degraded) VALUES (1, 'owner', 'repo', 2, 'review', CURRENT_TIMESTAMP, FALSE)");
            assertThat(tableCount(connection, "REVIEW_RUN_LEASE")).isEqualTo(1);
            assertThat(tableCount(connection, "REVIEW_REVISION")).isEqualTo(1);
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("filesystem:src/main/resources/db/migration/h2")
                .target(target)
                .load();
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }

    private static long tableCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
