package com.bpms.persistence.jpa;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** PostgreSQL + Liquibase bootstrap for integration tests (plans 25/29). Uses local docker Postgres. */
final class PostgresLiquibaseTestSupport {

    private static final String JDBC_URL = resolveJdbcUrl();
    private static final String USER = env("BPMS_TEST_JDBC_USER", "bpms");
    private static final String PASSWORD = env("BPMS_TEST_JDBC_PASSWORD", "bpms");
    private static volatile boolean migrated;

    private PostgresLiquibaseTestSupport() {}

    static JdbcTemplate jdbc() {
        assumeDatabaseAvailable();
        ensureMigrated();
        return new JdbcTemplate(new DriverManagerDataSource(JDBC_URL, USER, PASSWORD));
    }

    private static void assumeDatabaseAvailable() {
        Assumptions.assumeTrue(canConnect(), "PostgreSQL not reachable at " + JDBC_URL);
    }

    private static boolean canConnect() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureMigrated() {
        if (!migrated) {
            synchronized (PostgresLiquibaseTestSupport.class) {
                if (!migrated) {
                    runLiquibase();
                    migrated = true;
                }
            }
        }
    }

    private static void runLiquibase() {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(),
                    new JdbcConnection(connection));
            liquibase.update("");
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase migration failed in test (" + JDBC_URL + ")", e);
        }
    }

    private static String resolveJdbcUrl() {
        String env = System.getenv("BPMS_TEST_JDBC_URL");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String host = Files.exists(Path.of("/.dockerenv")) ? "host.docker.internal" : "localhost";
        return "jdbc:postgresql://" + host + ":5433/bpms_new";
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }
}
