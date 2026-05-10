package com.groupon.sda.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enables SQLite WAL mode after Spring has run {@code schema.sql}. WAL gives us read
 * concurrency (writers don't block readers and vice versa), which matters because the
 * API request threads read through the same Hikari pool the consumer writes through.
 *
 * <p>{@code journal_mode=WAL} is persistent at the <i>file</i> level — once flipped on
 * a database file, every subsequent connection inherits it. So this runner only needs
 * to execute the pragma once at startup.
 *
 * <p>{@code synchronous=NORMAL} is per-<i>connection</i> and is set on every checkout
 * via {@code spring.datasource.hikari.connection-init-sql} in {@code application.yml}.
 * It is intentionally <b>not</b> repeated here.
 */
@Component
@Order(0)   // runs before GraphRestoreRunner (Order(10))
public class SqlitePragmaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqlitePragmaInitializer.class);

    private final JdbcTemplate jdbc;

    @Autowired
    public SqlitePragmaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void enableWal() {
        String mode = jdbc.queryForObject("PRAGMA journal_mode=WAL", String.class);
        log.info("SQLite journal_mode set to {}", mode);
    }
}
