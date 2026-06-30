package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that V2 migration applied cleanly: all expected tables, indexes, and RLS
 * policies exist. This is a regression guard — it fails immediately if V2 is malformed.
 */
@DisplayName("V2 migration applied cleanly")
class V2MigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("All Phase 1 tables exist after V2 migration")
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "channel_connection", "flow", "flow_version",
            "contact", "contact_identity",
            "conversation", "message", "conversation_event"
        );
    }

    @Test
    @DisplayName("conversation table has version column for optimistic locking")
    void conversationHasVersionColumn() {
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_name = 'conversation' AND column_name = 'version'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("RLS is enabled on all Phase 1 tenant-owned tables")
    void rlsEnabledOnNewTables() {
        List<String> rlsTables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables "
            + "WHERE schemaname = 'public' "
            + "  AND rowsecurity = true "
            + "  AND tablename IN "
            + "  ('channel_connection','flow','flow_version','contact',"
            + "   'contact_identity','conversation','message','conversation_event')",
            String.class);

        assertThat(rlsTables).containsExactlyInAnyOrder(
            "channel_connection", "flow", "flow_version",
            "contact", "contact_identity",
            "conversation", "message", "conversation_event"
        );
    }

    @Test
    @DisplayName("Unique index on (tenant_id, channel, external_id) exists for contact_identity")
    void contactIdentityUniqueIndexExists() {
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes "
            + "WHERE tablename = 'contact_identity' AND indexname = 'uq_contact_identity'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("JSONB graph column exists on flow_version")
    void flowVersionHasJsonbGraph() {
        String dataType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
            + "WHERE table_name = 'flow_version' AND column_name = 'graph'",
            String.class);
        assertThat(dataType).isEqualTo("jsonb");
    }
}
