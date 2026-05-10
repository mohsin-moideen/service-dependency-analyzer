package com.groupon.sda.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test helper: a fresh SQLite database with the production {@code schema.sql} applied,
 * served by a real Hikari connection pool.
 *
 * <p>Why a pool and not {@link org.springframework.jdbc.datasource.SingleConnectionDataSource}?
 * Concurrent tests (e.g., the consumer's "16 threads same event id" test) issue
 * overlapping SELECTs and a transactional write. JDBC connections are not thread-safe;
 * sharing one across threads leaves it in a bad state when the writer flips
 * {@code autoCommit=false}. A real pool gives each thread its own connection — exactly
 * the production wiring — and the bug disappears.
 *
 * <p>Why a temp <i>file</i> and not {@code mode=memory&cache=shared}? The shared-cache
 * mode raises {@code SQLITE_LOCKED_SHAREDCACHE} on concurrent reads of a table held by
 * a writer; that error is not retriable via {@code busy_timeout}. A file-backed DB with
 * WAL mode lets readers and writers coexist, exactly as production does.
 *
 * <p>Each call to {@link #freshDatabase()} returns an isolated database in its own
 * temp file. Files are marked {@code deleteOnExit} so they don't accumulate.
 */
public final class PersistenceTestSupport {

    private PersistenceTestSupport() {
    }

    public static JdbcTemplate freshDatabase() {
        Path dbFile;
        try {
            dbFile = Files.createTempFile("sda-test-", ".db");
            dbFile.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException("could not create temp DB file", e);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setAutoCommit(true);
        // busy_timeout retries SQLITE_BUSY for up to 5s rather than failing immediately —
        // covers the brief contention window between concurrent transactions.
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000");
        HikariDataSource ds = new HikariDataSource(cfg);

        // Enable WAL once on the boot connection — it's a file-level setting that
        // persists for every subsequent connection.
        try (var conn = ds.getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            ds.close();
            throw new RuntimeException("schema.sql failed: " + e.getMessage(), e);
        }
        return new JdbcTemplate(ds);
    }
}
