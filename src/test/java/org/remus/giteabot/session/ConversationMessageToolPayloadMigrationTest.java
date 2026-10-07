package org.remus.giteabot.session;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration gate for the native tool-call payload columns of
 * {@code conversation_messages}: a follow-up run replays the assistant
 * {@code tool_calls} payload together with the {@code tool_call_id} each tool row
 * answers, and without the columns installed that replay silently degrades to
 * today's drop-the-exchange behaviour.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled) that migrates to the latest version and asserts the columns exist.
 * It deliberately pins no version number, so it cannot rot when the next
 * migration is added.</p>
 */
class ConversationMessageToolPayloadMigrationTest {

    private static final String URL = "jdbc:h2:mem:conversation-message-tool-payload;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    @Test
    void conversationMessagesCarryTheNativeToolPayloadColumns() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertThat(columnNames(connection))
                    .contains("TOOL_CALL_ID", "TOOL_CALLS");
        }
    }

    private static Set<String> columnNames(Connection connection) throws Exception {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'CONVERSATION_MESSAGES'")) {
            while (result.next()) {
                columns.add(result.getString(1));
            }
        }
        return columns;
    }
}
