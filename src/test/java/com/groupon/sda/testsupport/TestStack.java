package com.groupon.sda.testsupport;

import com.groupon.sda.graph.ServiceGraph;
import com.groupon.sda.ingest.consumer.EventConsumer;
import com.groupon.sda.ingest.dedup.InMemorySetCache;
import com.groupon.sda.ingest.dedup.SeenEventsCache;
import com.groupon.sda.persistence.EdgeRepository;
import com.groupon.sda.persistence.EdgeSampleRepository;
import com.groupon.sda.persistence.ProcessedEventsRepository;
import com.groupon.sda.persistence.ServiceRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A minimal end-to-end test stack: SQLite file, the production schema, all four
 * repositories, an in-memory {@link ServiceGraph}, and an {@link EventConsumer}
 * wired the same way Spring would wire it.
 *
 * <p>Unlike {@link com.groupon.sda.persistence.PersistenceTestSupport#freshDatabase},
 * this helper takes an explicit DB file path so tests that exercise restart
 * semantics can build two stacks against the same file. It also runs the same
 * {@code services} / {@code edges} / {@code edge_samples} restore logic that
 * {@code GraphRestoreRunner} runs on Spring boot, so a stack opened on a
 * pre-populated DB file rebuilds the in-memory graph before any events flow.
 *
 * <p>Close with {@link #close()} to release the Hikari pool. The file itself is
 * not deleted — callers manage lifecycle (typically {@code @TempDir}).
 */
public final class TestStack implements AutoCloseable {

    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final SeenEventsCache cache;
    public final ProcessedEventsRepository processed;
    public final ServiceRepository services;
    public final EdgeRepository edges;
    public final EdgeSampleRepository edgeSamples;
    public final ServiceGraph graph;
    public final EventConsumer consumer;
    public final Clock clock;
    public final Duration sampleAgeCap;

    private TestStack(HikariDataSource ds, Clock clock, Duration sampleAgeCap) {
        this.dataSource = ds;
        this.clock = clock;
        this.sampleAgeCap = sampleAgeCap;
        this.jdbc = new JdbcTemplate(ds);
        this.cache = new InMemorySetCache();
        this.processed = new ProcessedEventsRepository(jdbc);
        this.services = new ServiceRepository(jdbc);
        this.edges = new EdgeRepository(jdbc);
        this.edgeSamples = new EdgeSampleRepository(jdbc);
        this.graph = new ServiceGraph(1024, sampleAgeCap, clock);
        this.consumer = new EventConsumer(
                cache, processed, services, edges, edgeSamples, graph, clock,
                new DataSourceTransactionManager(ds));
    }

    /**
     * Open a stack against {@code dbFile}. If the file is empty/new, the schema is
     * applied. If it already has rows from a previous open, those rows are
     * replayed into the in-memory graph (services → edges → recent edge_samples)
     * before this method returns.
     */
    public static TestStack open(Path dbFile, Clock clock, Duration sampleAgeCap) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setAutoCommit(true);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000");
        HikariDataSource ds = new HikariDataSource(cfg);

        try (var conn = ds.getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            ds.close();
            throw new RuntimeException("schema.sql failed: " + e.getMessage(), e);
        }

        TestStack stack = new TestStack(ds, clock, sampleAgeCap);
        stack.restoreFromDb();
        return stack;
    }

    /**
     * Replay persisted state into {@link #graph}. Mirrors the production
     * {@code GraphRestoreRunner.restore()} logic so a freshly opened stack reaches
     * the same in-memory state production would after a real restart.
     */
    private void restoreFromDb() {
        for (ServiceRepository.ServiceRow row : services.findAll()) {
            graph.applyServiceMetadata(row.id(), row.team(), row.tier(), row.region());
            if (row.lastHeartbeat() != null) {
                graph.applyHeartbeat(row.id(), row.lastHeartbeat());
            }
        }
        for (EdgeRepository.EdgeRow row : edges.findAll()) {
            graph.upsertEdgeForRestore(row.source(), row.target(),
                    row.rollingAvgLatencyMs(), row.sampleCount(), row.lastObservedTs());
        }
        Instant cutoff = clock.instant().minus(sampleAgeCap);
        for (EdgeSampleRepository.SampleRow row : edgeSamples.findRecent(cutoff.toEpochMilli())) {
            graph.restoreEdgeSample(row.source(), row.target(), row.sample());
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
