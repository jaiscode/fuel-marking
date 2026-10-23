package org.jai.fuelmarking.sync;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumerPropertiesTest {
    @Test
    void securitySettingsAreAppliedButCannotWeakenTheDeliveryGuarantees() {
        Properties p = MasterDataConsumer.buildProperties("kafka:9093", "site-1", Map.of(
                "security.protocol", "SASL_SSL", "sasl.mechanism", "SCRAM-SHA-512",
                "enable.auto.commit", "true",            // a misconfiguration that would acknowledge records before they are applied
                "auto.offset.reset", "latest",           // would make a new site miss existing master data
                "group.id", "someone-elses-group"));
        assertEquals("SASL_SSL", p.get("security.protocol"));
        assertEquals("SCRAM-SHA-512", p.get("sasl.mechanism"));
        assertEquals("kafka:9093", p.get("bootstrap.servers"));
        assertEquals("false", p.get("enable.auto.commit"));
        assertEquals("earliest", p.get("auto.offset.reset"));
        assertEquals("fuelmarking-site-site-1", p.get("group.id"));
    }
}
