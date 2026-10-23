// src/main/java/org/jai/fuelmarking/config/YamlConfigurationLoader.java
package org.jai.fuelmarking.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

public class YamlConfigurationLoader {
    private static final Logger logger = LoggerFactory.getLogger(YamlConfigurationLoader.class);
    private static JsonNode config;
    
    static {
        loadConfiguration();
    }
    
    /** Name of the optional local settings file looked for next to the application (and in the working directory). */
    static final String LOCAL_FILE = "application-local.yml";

    private static void loadConfiguration() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream input = YamlConfigurationLoader.class
                .getClassLoader()
                .getResourceAsStream("application.yml")) {

            if (input == null) {
                throw new RuntimeException("application.yml file not found in classpath");
            }
            config = mapper.readTree(input);
            logger.info("Configuration loaded successfully from application.yml");
        } catch (IOException e) {
            logger.error("Failed to load application.yml", e);
            throw new RuntimeException("Failed to load application.yml", e);
        }

        Path local = findLocalFile();
        if (local != null) {
            try {
                JsonNode overrides = mapper.readTree(local.toFile());
                if (overrides != null && overrides.isObject()) merge((ObjectNode) config, (ObjectNode) overrides);
                warnIfReadableByOthers(local);
                logger.info("Local settings applied from {}", local.toAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Cannot read the settings file " + local.toAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * The settings file that adjusts the built-in application.yml without editing the jar: -Dfuelmarking.config=PATH or the
     * FUEL_MARKING_CONFIG environment variable (must exist), else application-local.yml in the working directory, else next to
     * the jar. Values in it win over the built-in file; -D options and environment variables still win over both.
     */
    /** The path of the local settings file in use, or null. For diagnostics. */
    public static String localSettingsFile() {
        Path p = findLocalFile();
        return p == null ? null : p.toAbsolutePath().toString();
    }

    private static Path findLocalFile() {
        String explicit = System.getProperty("fuelmarking.config");
        if (explicit == null || explicit.isBlank()) explicit = System.getenv("FUEL_MARKING_CONFIG");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit.trim());
            if (!Files.isRegularFile(p)) throw new RuntimeException("The settings file " + p.toAbsolutePath() + " (fuelmarking.config / FUEL_MARKING_CONFIG) does not exist");
            return p;
        }
        Path inWorkingDir = Path.of(LOCAL_FILE);
        if (Files.isRegularFile(inWorkingDir)) return inWorkingDir;
        try {
            var source = YamlConfigurationLoader.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                Path dir = Files.isDirectory(location) ? null : location.getParent();   // a jar's folder; none when running from classes
                if (dir != null && Files.isRegularFile(dir.resolve(LOCAL_FILE))) return dir.resolve(LOCAL_FILE);
            }
        } catch (Exception ignored) {
            // no readable code source: nothing to look for beside it
        }
        return null;
    }

    /** Objects merge field by field; any other value (text, number, list) replaces what was there. */
    static void merge(ObjectNode base, ObjectNode overrides) {
        overrides.fields().forEachRemaining(e -> {
            JsonNode existing = base.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) merge((ObjectNode) existing, (ObjectNode) e.getValue());
            else base.set(e.getKey(), e.getValue());
        });
    }

    /** The file usually holds passwords (Kafka, API key): say so if other users on this machine can read it (POSIX systems). */
    private static void warnIfReadableByOthers(Path file) {
        try {
            var perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.GROUP_READ))
                logger.warn("{} can be read by other users of this machine and may hold passwords; restrict it (chmod 600).", file.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system (Windows): protect the file with its folder's permissions instead
        }
    }

    /** Re-reads the configuration (tests change the settings file or system properties and need a fresh view). */
    public static synchronized void reload() { loadConfiguration(); }

    public static String getString(String path) {
        return getString(path, null);
    }
    
    /** -Dkey=value or the KEY_AS_ENV_VAR beats application.yml, for settings of every type. */
    private static String override(String path) {
        String value = System.getProperty(path);
        if (value == null) {
            String env = System.getenv(path.toUpperCase().replace('.', '_').replace('-', '_'));
            if (env != null && !env.isBlank()) value = env;
        }
        return value;
    }

    public static String getString(String path, String defaultValue) {
        String o = override(path);
        if (o != null) return o;
        JsonNode node = getNode(path);
        return node != null && !node.isNull() ? node.asText() : defaultValue;
    }

    public static int getInt(String path) {
        return getInt(path, 0);
    }

    public static int getInt(String path, int defaultValue) {
        String o = override(path);
        if (o != null) return parse(path, o, Integer::parseInt);
        JsonNode node = getNode(path);
        return node != null && !node.isNull() ? node.asInt() : defaultValue;
    }

    public static long getLong(String path) {
        return getLong(path, 0L);
    }

    public static long getLong(String path, long defaultValue) {
        String o = override(path);
        if (o != null) return parse(path, o, Long::parseLong);
        JsonNode node = getNode(path);
        return node != null && !node.isNull() ? node.asLong() : defaultValue;
    }

    public static double getDouble(String path) {
        return getDouble(path, 0.0);
    }

    public static double getDouble(String path, double defaultValue) {
        String o = override(path);
        if (o != null) return parse(path, o, Double::parseDouble);
        JsonNode node = getNode(path);
        return node != null && !node.isNull() ? node.asDouble() : defaultValue;
    }

    public static boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    public static boolean getBoolean(String path, boolean defaultValue) {
        String o = override(path);
        if (o != null) return Boolean.parseBoolean(o.trim());
        JsonNode node = getNode(path);
        return node != null && !node.isNull() ? node.asBoolean() : defaultValue;
    }

    public static BigDecimal getBigDecimal(String path) {
        return getBigDecimal(path, BigDecimal.ZERO);
    }

    public static BigDecimal getBigDecimal(String path, BigDecimal defaultValue) {
        String o = override(path);
        if (o != null) return parse(path, o, BigDecimal::new);
        JsonNode node = getNode(path);
        if (node != null && !node.isNull()) {
            return new BigDecimal(node.asText());
        }
        return defaultValue;
    }

    /** A bad override must fail loudly, naming the setting, rather than quietly falling back to the file. */
    private static <T> T parse(String path, String value, java.util.function.Function<String, T> parser) {
        try {
            return parser.apply(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Setting " + path + " must be a number, but was '" + value + "'");
        }
    }

    private static JsonNode getNode(String path) {
        if (config == null) {
            return null;
        }
        
        String[] parts = path.split("\\.");
        JsonNode current = config;
        
        for (String part : parts) {
            current = current.isArray() && part.matches("\\d+") ? current.get(Integer.parseInt(part)) : current.get(part);
            if (current == null) {
                return null;
            }
        }
        
        return current;
    }
    
    // Convenience methods for common configuration paths
    public static class Database {
        public static String getUrl() {
            return getString("database.url", "jdbc:postgresql://localhost:5432/fuel_marking");
        }
        
        public static String getUsername() {
            return getString("database.username", "postgres");
        }
        
        public static String getPassword() {
            return getString("database.password", "");
        }
        
        public static String getDriverClassName() {
            return getString("database.driver-class-name", "org.postgresql.Driver");
        }
        
        public static int getMaximumPoolSize() {
            return getInt("database.pool.maximum-pool-size", 10);
        }
        
        public static int getMinimumIdle() {
            return getInt("database.pool.minimum-idle", 5);
        }
        
        public static long getConnectionTimeout() {
            return getLong("database.pool.connection-timeout", 30000);
        }
        
        public static long getIdleTimeout() {
            return getLong("database.pool.idle-timeout", 600000);
        }
        
        public static long getMaxLifetime() {
            return getLong("database.pool.max-lifetime", 1800000);
        }
        
        public static long getLeakDetectionThreshold() {
            return getLong("database.pool.leak-detection-threshold", 0);
        }
    }
    
    public static class FuelMarking {
        public static double getRumDosagePer1000L() {
            return getDouble("fuel-marking.rum-dosage-per-1000l", 10.0);
        }
        
        
        /** Allowed difference between the weighed dose and the required dose, as a percentage of the required dose. */
        public static double getDoseTolerancePercent() {
            return getDouble("fuel-marking.dose-tolerance-percent", 2.0);
        }



        public static double getMinDeclaredVolume() {
            return getDouble("fuel-marking.validation.min-declared-volume", 100.0);
        }
        
        public static double getMaxDeclaredVolume() {
            return getDouble("fuel-marking.validation.max-declared-volume", 50000.0);
        }
        
        public static double getMinCompartmentCapacity() {
            return getDouble("fuel-marking.validation.min-compartment-capacity", 50.0);
        }
        
        public static double getMaxCompartmentCapacity() {
            return getDouble("fuel-marking.validation.max-compartment-capacity", 10000.0);
        }
    }
    
    public static class Sync {
        public static boolean isEnabled() { return Boolean.parseBoolean(getString("sync.enabled", "false")); }
        public static String getSiteId() { return getString("sync.site-id", ""); }
        public static String getApiKey() { return getString("sync.api-key", ""); }
        public static String getBaseUrl() { return getString("sync.base-url", "http://localhost:8080"); }
        public static int getIntervalSeconds() { return Math.max(1, Integer.parseInt(getString("sync.interval-seconds", "15"))); }
        public static String getKafkaBootstrapServers() { return getString("sync.kafka.bootstrap-servers", "localhost:9092"); }
        /**
         * Extra Kafka client settings (TLS, SASL, ...), merged from lowest to highest priority:
         * the {@code sync.kafka.properties} map in application.yml, environment variables named
         * {@code SYNC_KAFKA_PROPERTIES_SECURITY_PROTOCOL} (-> security.protocol), and {@code -Dsync.kafka.properties.security.protocol=...}.
         */
        public static java.util.Map<String, String> getKafkaProperties() {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            JsonNode node = getNode("sync.kafka.properties");
            if (node != null && node.isObject()) node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            String envPrefix = "SYNC_KAFKA_PROPERTIES_";
            System.getenv().forEach((k, v) -> {
                if (k.startsWith(envPrefix) && !v.isBlank()) out.put(k.substring(envPrefix.length()).toLowerCase().replace('_', '.'), v);
            });
            String propPrefix = "sync.kafka.properties.";
            System.getProperties().forEach((k, v) -> {
                if (k instanceof String key && key.startsWith(propPrefix)) out.put(key.substring(propPrefix.length()), String.valueOf(v));
            });
            return out;
        }

        public static String getMasterDataTopic() { return getString("sync.kafka.master-data-topic", "fuelmarking.master-data"); }
    }

    /** Hardware settings. The scale is a simulator until a real device is added. */
    public static class HardwareConfig {
        /** simulator | tcp | serial */
        public static String getScaleDriver() { return getString("hardware.scale.driver", "simulator").trim().toLowerCase(); }
        public static boolean isSimulatorPanelEnabled() { return Boolean.parseBoolean(getString("hardware.scale.simulator-panel", "true")); }
        public static String getScaleTcpHost() { return getString("hardware.scale.tcp.host", "localhost"); }
        public static int getScaleTcpPort() { return Integer.parseInt(getString("hardware.scale.tcp.port", "5025")); }
        /** How long to wait for the display to settle before giving up on a weighing. */
        public static int getStableWaitSeconds() { return Math.max(1, Integer.parseInt(getString("hardware.scale.stable-wait-seconds", "5"))); }
    }

    public static class Application {
        public static String getName() {
            return getString("application.name", "Fuel Marking System");
        }
        
        public static String getVersion() {
            return getString("application.version", "1.0.0");
        }
        
        public static String getDescription() {
            return getString("application.description", "JavaFX application for fuel marking operations");
        }
    }
    
    public static class UI {
        public static int getAutoRefreshInterval() {
            return getInt("fuel-marking.ui.auto-refresh-interval", 30);
        }
        
        public static boolean getShowCompletedRecords() {
            return getBoolean("fuel-marking.ui.show-completed-records", true);
        }
        
        /** Seconds without any input before the session is locked (signed out). 0 disables. */
        public static int getIdleLockSeconds() {
            return Math.max(0, Integer.parseInt(getString("fuel-marking.ui.idle-lock-seconds", "600")));
        }

        public static int getRecordsPerPage() {
            return getInt("fuel-marking.ui.records-per-page", 50);
        }
    }
}
