package org.jai.fuelmarking;

import org.jai.fuelmarking.config.YamlConfigurationLoader.Sync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSettingsTest {
    @AfterEach
    void clear() {
        System.clearProperty("sync.kafka.properties.security.protocol");
        System.clearProperty("sync.kafka.properties.enable.auto.commit");
    }

    @Test
    void securitySettingsCanBeSuppliedAsSystemProperties() {
        System.setProperty("sync.kafka.properties.security.protocol", "SASL_SSL");
        System.setProperty("sync.kafka.properties.sasl.mechanism", "SCRAM-SHA-512");
        try {
            Map<String, String> p = Sync.getKafkaProperties();
            assertEquals("SASL_SSL", p.get("security.protocol"));
            assertEquals("SCRAM-SHA-512", p.get("sasl.mechanism"));
        } finally {
            System.clearProperty("sync.kafka.properties.sasl.mechanism");
        }
    }

    @Test
    void noSettingsMeansPlainDefaults() {
        assertTrue(Sync.getKafkaProperties().keySet().stream().noneMatch(k -> k.startsWith("sasl.") || k.equals("security.protocol")));
    }
}
