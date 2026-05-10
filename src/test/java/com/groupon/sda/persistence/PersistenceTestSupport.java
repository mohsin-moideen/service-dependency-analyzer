package com.groupon.sda.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.UUID;

/**
 * Test helper: a fresh in-memory SQLite database with the production {@code schema.sql}
 * applied, served by a real Hikari connection pool.
 *
 * <p>Why a pool and not {@link org.springframework.jdbc.datasource.SingleConnectionDataSource}?
 * Concurrent tests (e.g., the consumer's "16 threads same event id" test) issue
 * overlapping SELECTs and a transactional write. JDBC connections are not thread-safe;
 * sharing one across threads leaves it in a bad state when the writer flips
 * {@code autoCommit=false}. A real pool gives each thread its own connection — exactly
 * the production wiring — and the bug disappears.
 *
 * <p>Each call to {@link #freshDatabase()} returns an isolated database via a unique
 * shared-cache name in {@code mode=memory}.
 */
public final class PersistenceTestSupport {

    private PersistenceTestSupport() {
    }

    public static JdbcTemplate freshDatabase() {
        String dbName = "sda-test-" + UUID.randomUUID();
        String url = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        // Keep at least one connection alive for the whole test — without it, an idle
        // pool would let SQLite drop the in-memory database between operations.
        cfg.setConnectionInitSql("PRAGMA synchronous=NORMAL");
        HikariDataSource ds = new HikariDataSource(cfg);

        try (var conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new RuntimeException("schema.sql failed: " + e.getMessage(), e);
        }
        return new JdbcTemplate(ds);
    }
}
