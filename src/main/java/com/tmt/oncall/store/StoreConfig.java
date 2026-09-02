package com.tmt.oncall.store;

import com.tmt.oncall.config.OncallProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
class StoreConfig {

    /**
     * SQLite는 동시 쓰기를 허용하지 않으므로 커넥션을 하나로 묶어 직렬화한다.
     * 봇의 쓰기 빈도(분당 수 건)에서는 이 편이 잠금 충돌을 다루는 것보다 단순하다.
     */
    @Bean
    DataSource dataSource(OncallProperties properties) {
        Path path = properties.store().resolvedPath();
        createParentDirectory(path);

        HikariConfig config = new HikariConfig();
        config.setPoolName("oncall-store");
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA journal_mode = WAL");
        return new HikariDataSource(config);
    }

    private static void createParentDirectory(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("상태 저장 디렉터리를 만들 수 없다: " + parent, e);
        }
    }
}
