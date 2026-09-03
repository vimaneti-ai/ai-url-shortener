package com.example.URLShortener.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesAllMigrationsToEmptyPostgresDatabase() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);

        assertThat(successfulMigrations).isEqualTo(4);
        assertThat(tableExists("urls")).isTrue();
        assertThat(tableExists("click_events")).isTrue();
        assertThat(columnExists("urls", "active")).isTrue();
        assertThat(columnExists("click_events", "country")).isTrue();
    }

    @Test
    void postgresEnforcesUniqueShortCodesAndForeignKeys() {
        jdbcTemplate.update(
                "INSERT INTO urls (long_url, short_url, active) VALUES (?, ?, true)",
                "https://example.com/one", "unique1");

        assertThatThrownBySql(() -> jdbcTemplate.update(
                "INSERT INTO urls (long_url, short_url, active) VALUES (?, ?, true)",
                "https://example.com/two", "unique1"));

        assertThatThrownBySql(() -> jdbcTemplate.update(
                "INSERT INTO click_events (short_url, clicked_at) VALUES (?, CURRENT_TIMESTAMP)",
                "unknown"));
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count == 1;
    }

    private void assertThatThrownBySql(Runnable operation) {
        org.assertj.core.api.Assertions.assertThatThrownBy(operation::run)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
