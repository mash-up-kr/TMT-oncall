package com.tmt.oncall.support;

import com.tmt.oncall.store.OncallStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 테스트용 SQLite 저장소. 운영과 같은 {@code schema.sql}을 태워 스키마가 어긋나지 않게 한다. */
public final class TestStore {

    private final OncallStore store;
    private final JdbcClient jdbc;

    private TestStore(OncallStore store, JdbcClient jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public static TestStore create() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + databaseFile());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        return new TestStore(new OncallStore(jdbc), jdbc);
    }

    public OncallStore store() {
        return store;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    private static Path databaseFile() {
        try {
            return Files.createTempDirectory("oncall-test-store").resolve("state.db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
