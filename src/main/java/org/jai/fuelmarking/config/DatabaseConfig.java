// src/main/java/org/jai/fuelmarking/config/DatabaseConfig.java
package org.jai.fuelmarking.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private static HikariDataSource dataSource;

    static {
        initializeDatabase();
    }

    private static void initializeDatabase() {
        // Configure HikariCP using YAML configuration
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(YamlConfigurationLoader.Database.getUrl());
        config.setUsername(YamlConfigurationLoader.Database.getUsername());
        config.setPassword(YamlConfigurationLoader.Database.getPassword());
        config.setDriverClassName(YamlConfigurationLoader.Database.getDriverClassName());

        // Pool settings
        config.setMaximumPoolSize(YamlConfigurationLoader.Database.getMaximumPoolSize());
        config.setMinimumIdle(YamlConfigurationLoader.Database.getMinimumIdle());
        config.setConnectionTimeout(YamlConfigurationLoader.Database.getConnectionTimeout());
        config.setIdleTimeout(YamlConfigurationLoader.Database.getIdleTimeout());
        config.setMaxLifetime(YamlConfigurationLoader.Database.getMaxLifetime());

        // Leak detection
        long leakDetectionThreshold = YamlConfigurationLoader.Database.getLeakDetectionThreshold();
        if (leakDetectionThreshold > 0) {
            config.setLeakDetectionThreshold(leakDetectionThreshold);
        }

        // PostgreSQL specific optimizations
        config.addDataSourceProperty("cachePrepStmts",
                YamlConfigurationLoader.getBoolean("database.properties.cache-prep-stmts", true));
        config.addDataSourceProperty("prepStmtCacheSize",
                YamlConfigurationLoader.getInt("database.properties.prep-stmt-cache-size", 250));
        config.addDataSourceProperty("prepStmtCacheSqlLimit",
                YamlConfigurationLoader.getInt("database.properties.prep-stmt-cache-sql-limit", 2048));
        config.addDataSourceProperty("useServerPrepStmts",
                YamlConfigurationLoader.getBoolean("database.properties.use-server-prep-stmts", true));

        dataSource = new HikariDataSource(config);

        logger.info("Database connection pool initialized with URL: {}",
                YamlConfigurationLoader.Database.getUrl());

        // Run Flyway migration
        runMigrations();
    }

    private static void runMigrations() {
        try {
            logger.info("Starting database migration...");

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(YamlConfigurationLoader.getString("flyway.locations.0", "classpath:db/migration"))
                    .baselineOnMigrate(YamlConfigurationLoader.getBoolean("flyway.baseline-on-migrate", true))
                    .validateOnMigrate(YamlConfigurationLoader.getBoolean("flyway.validate-on-migrate", true))
                    .outOfOrder(YamlConfigurationLoader.getBoolean("flyway.out-of-order", false))
                    .cleanDisabled(YamlConfigurationLoader.getBoolean("flyway.clean-disabled", true))
                    .load();

            flyway.migrate();

            logger.info("Database migration completed successfully");
        } catch (Exception e) {
            logger.error("Database migration failed", e);
            throw new RuntimeException("Failed to migrate database", e);
        }
    }

    public static DataSource getDataSource() {
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }

    // Health check method
    public static boolean isHealthy() {
        try (Connection connection = getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            logger.error("Database health check failed", e);
            return false;
        }
    }
}