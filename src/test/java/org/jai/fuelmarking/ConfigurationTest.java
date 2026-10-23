package org.jai.fuelmarking;

import org.jai.fuelmarking.config.YamlConfigurationLoader;
import org.jai.fuelmarking.config.YamlConfigurationLoader.FuelMarking;
import org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig;
import org.jai.fuelmarking.config.YamlConfigurationLoader.Sync;
import org.jai.fuelmarking.config.YamlConfigurationLoader.UI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The settings must really be in application.yml (the one file the app reads), not just defaults compiled into the code:
 * an earlier version ran for a long time on defaults because the real settings sat in a file nothing loaded.
 */
class ConfigurationTest {
    @Test
    void everySettingTheAppReadsIsWrittenDownInApplicationYml() {
        for (String key : List.of(
                "database.url", "database.username", "database.driver-class-name", "database.pool.maximum-pool-size",
                "database.pool.connection-timeout", "database.pool.leak-detection-threshold",
                "flyway.locations", "flyway.baseline-on-migrate", "flyway.validate-on-migrate", "flyway.clean-disabled",
                "fuel-marking.rum-dosage-per-1000l", "fuel-marking.dose-tolerance-percent",
                "fuel-marking.validation.min-declared-volume",
                "fuel-marking.validation.max-declared-volume", "fuel-marking.ui.idle-lock-seconds", "fuel-marking.ui.records-per-page",
                "sync.enabled", "sync.base-url", "sync.interval-seconds", "sync.kafka.bootstrap-servers", "sync.kafka.master-data-topic",
                "hardware.scale.driver", "hardware.scale.simulator-panel", "hardware.scale.stable-wait-seconds", "hardware.scale.tcp.host", "hardware.scale.tcp.port"))
            assertNotNull(YamlConfigurationLoader.getString(key, null), key + " is missing from application.yml (the code would silently use a default)");
    }

    @Test
    void theAgreedBusinessRulesAreWhatTheFileSays() {
        assertEquals(10.0, FuelMarking.getRumDosagePer1000L(), "10 g of rum per 1000 L");
        assertEquals(2.0, FuelMarking.getDoseTolerancePercent(), "2% dose tolerance");
        assertEquals(100.0, FuelMarking.getMinDeclaredVolume());
        assertEquals(50000.0, FuelMarking.getMaxDeclaredVolume());
        assertEquals(600, UI.getIdleLockSeconds());
    }

    @Test
    void safeDefaultsForSyncAndHardware() {
        assertFalse(Sync.isEnabled(), "sync stays off until a site id and key are configured");
        assertEquals("simulator", HardwareConfig.getScaleDriver());
        assertEquals(5, HardwareConfig.getStableWaitSeconds());
        assertEquals("fuelmarking.master-data", Sync.getMasterDataTopic());
    }

    @Test
    void listItemsCanBeAddressedByIndex() {
        assertEquals("classpath:db/migration", YamlConfigurationLoader.getString("flyway.locations.0", null));
    }

    @Test
    void anOverrideWorksForNumbersAndFlagsAsWellAsText() {
        System.setProperty("database.pool.maximum-pool-size", "3");
        System.setProperty("flyway.clean-disabled", "false");
        System.setProperty("hardware.scale.driver", "TCP");
        try {
            assertEquals(3, YamlConfigurationLoader.getInt("database.pool.maximum-pool-size"));
            assertFalse(YamlConfigurationLoader.getBoolean("flyway.clean-disabled", true));
            assertEquals("tcp", HardwareConfig.getScaleDriver());
            System.setProperty("fuel-marking.dose-tolerance-percent", "two percent");
            IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, FuelMarking::getDoseTolerancePercent);
            assertTrue(bad.getMessage().contains("fuel-marking.dose-tolerance-percent") && bad.getMessage().contains("two percent"),
                    "a mistyped override must be reported, not silently ignored: " + bad.getMessage());
        } finally {
            System.clearProperty("database.pool.maximum-pool-size");
            System.clearProperty("flyway.clean-disabled");
            System.clearProperty("hardware.scale.driver");
            System.clearProperty("fuel-marking.dose-tolerance-percent");
        }
        assertEquals(10, YamlConfigurationLoader.getInt("database.pool.maximum-pool-size"));
        assertTrue(YamlConfigurationLoader.getBoolean("flyway.clean-disabled", false));
    }

    @Test
    void anOverrideBeatsTheFile() {
        System.setProperty("fuel-marking.dose-tolerance-percent", "5.0");
        try {
            assertEquals(5.0, FuelMarking.getDoseTolerancePercent());
        } finally {
            System.clearProperty("fuel-marking.dose-tolerance-percent");
        }
        assertEquals(2.0, FuelMarking.getDoseTolerancePercent());
    }
}
