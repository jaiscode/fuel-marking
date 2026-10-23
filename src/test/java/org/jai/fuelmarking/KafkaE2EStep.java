package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.security.PinHasher;
import org.jai.fuelmarking.service.AuthService;
import org.jai.fuelmarking.sync.MasterDataConsumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One step of the real-broker end-to-end run (scripts/e2e-full.sh): a desktop installation consumes the master-data
 * topic with the real Kafka client and must end up able to sign the administrator-created user in. Skipped otherwise.
 */
class KafkaE2EStep {
    static String prop(String k) { return System.getProperty(k); }

    static long count(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    /** True once the local DB reflects what the script expects for this step. */
    static boolean converged(UUID siteId, UUID userId, String expectName, String pin, boolean inactive) {
        try {
            if (count("SELECT count(*) FROM marking_sites WHERE id = ?", siteId) == 0) return false;
            String n = prop("e2e.kafka.n");
            if (n != null && count("SELECT count(*) FROM registered_jerricans WHERE barcode = ? AND rum_type_code = ? AND container_type_code = 'JERRICAN_METAL' AND capacity_g = 18000",
                    "KJ-" + n, "RUM" + n) == 0) return false;
            if (n != null && count("SELECT count(*) FROM fuel_types WHERE code = ?", "JET" + n) == 0) return false;
            if (count("SELECT count(*) FROM app_users WHERE id = ? AND full_name = ? AND active = ?", userId, expectName, !inactive) == 0) return false;
            String hash = Tx.call(c -> {
                try (var ps = c.prepareStatement("SELECT pin_hash FROM app_users WHERE id = ?")) {
                    ps.setObject(1, userId);
                    try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
                }
            });
            return hash != null && PinHasher.verify(pin, hash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void step() {
        String step = prop("e2e.kafka.step");
        Assumptions.assumeTrue(step != null, "not a Kafka end-to-end run");
        UUID siteId = UUID.fromString(prop("e2e.kafka.site")), userId = UUID.fromString(prop("e2e.kafka.user"));
        String expectName = prop("e2e.kafka.name"), pin = prop("e2e.kafka.pin");
        boolean inactive = "inactive".equals(prop("e2e.kafka.expect"));

        // the desktop's real configuration route: security settings come from SYNC_KAFKA_PROPERTIES_* environment variables / -D options
        try (MasterDataConsumer consumer = MasterDataConsumer.create(prop("e2e.kafka.bootstrap"), prop("e2e.kafka.topic"), prop("e2e.kafka.group"),
                org.jai.fuelmarking.config.YamlConfigurationLoader.Sync.getKafkaProperties())) {
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end && !converged(siteId, userId, expectName, pin, inactive)) consumer.pollOnce(Duration.ofSeconds(2));
        }
        assertTrue(converged(siteId, userId, expectName, pin, inactive), step + ": desktop did not receive the master data in time");

        if ("inactive".equals(prop("e2e.kafka.expect"))) {
            // an administrator deactivated the person: they must not be able to sign in even with the right PIN
            assertEquals(0, count("SELECT count(*) FROM app_users WHERE id = ? AND active", userId));
            assertEquals("Unknown user", assertThrows(AuthService.AuthException.class, () -> new AuthService().authenticate(userId, pin)).getMessage());
            return;
        }
        String n = prop("e2e.kafka.n");
        if (n != null && "initial".equals(step)) {
            // head office registered the jerrican; the warehouse accepts it empty, with the capacity and rum type head office recorded
            var wh = new org.jai.fuelmarking.model.Records.AppUser(userId, "wh", "Warehouse", org.jai.fuelmarking.model.Role.WAREHOUSE_MANAGER, siteId);
            var site = new org.jai.fuelmarking.model.Records.Site(siteId, "KS" + n, "Kafka Site", "FIXED");
            var k = new org.jai.fuelmarking.service.ContainerService().receiveEmptyJerrican(wh, site, "KJ-" + n, "RUM" + n);
            assertEquals("EMPTY", k.status());
            assertEquals(18_000, k.capacityMl(), 0.0);
            assertEquals("JERRICAN_METAL", k.type().code());
            assertEquals(2, k.type().seals());
            assertEquals("RUM" + n, k.rumTypeCode());
            assertThrows(IllegalArgumentException.class, () -> new org.jai.fuelmarking.service.ContainerService().receiveEmptyJerrican(wh, site, "NOT-REGISTERED-" + n, "RUM" + n));
        }
        // the whole chain: administrator sets a PIN in the web app -> backend -> Kafka -> desktop -> the person signs in
        assertEquals(userId, new AuthService().authenticate(userId, pin).id());
        if (prop("e2e.kafka.oldpin") != null) {
            String old = prop("e2e.kafka.oldpin");
            assertThrows(AuthService.AuthException.class, () -> new AuthService().authenticate(userId, old), "the replaced PIN must stop working");
        }
    }
}
