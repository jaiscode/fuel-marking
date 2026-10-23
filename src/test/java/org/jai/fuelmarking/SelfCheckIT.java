package org.jai.fuelmarking;

import com.sun.net.httpserver.HttpServer;
import org.jai.fuelmarking.config.YamlConfigurationLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** --selfcheck: what an installer runs first on a new machine (Windows in particular) to find out what is wrong. */
class SelfCheckIT {
    @TempDir Path dir;
    String output;
    int exit;

    @AfterEach
    void restore() {
        System.clearProperty("fuelmarking.config");
        YamlConfigurationLoader.reload();
    }

    void run(String localYaml) throws Exception {
        if (localYaml != null) {
            Path f = dir.resolve("application-local.yml");
            Files.writeString(f, localYaml);
            System.setProperty("fuelmarking.config", f.toString());
        }
        YamlConfigurationLoader.reload();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        exit = SelfCheck.run(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        output = buffer.toString(StandardCharsets.UTF_8);
        System.out.println(output);
    }

    @Test
    void aHealthyStandaloneInstallationPasses() throws Exception {
        run(null);
        assertEquals(0, exit, output);
        assertTrue(output.contains("[ OK ] Java") && output.contains("[ OK ] Database") && output.contains("[ OK ] JavaFX"), output);
        assertTrue(output.contains("[INFO] Central") && output.contains("switched off"), "sync is off by default");
        assertTrue(output.contains("[INFO] Scale") && output.contains("simulator"), output);
        assertTrue(output.contains("RESULT: nothing failed"), output);
        assertTrue(output.contains("migrations applied") || output.contains("no schema yet"), "it reports the schema state: " + output);
    }

    @Test
    void aDatabaseThatCannotBeUsedIsReportedWithoutLeakingThePassword() throws Exception {
        // (the test PostgreSQL trusts every password, so a missing database stands in for "the login was refused")
        String url = System.getProperty("database.url");
        System.setProperty("database.url", url.replaceAll("/[^/]+$", "/no_such_database"));
        System.setProperty("database.password", "LeakCheck-987-not-the-password");
        try {
            run(null);
        } finally {
            System.setProperty("database.url", url);
            System.setProperty("database.password", "x");
        }
        assertEquals(1, exit, output);
        assertTrue(output.contains("[FAIL] Database") && output.contains("application-local.yml"), output);
        assertFalse(output.contains("LeakCheck-987"), "the password must never be printed: " + output);
        assertTrue(output.contains("[ OK ] Java") && output.contains("[ OK ] JavaFX"), "the other checks still run after a failure");
    }

    @Test
    void anUnreachableDatabaseServerIsReported() throws Exception {
        String url = System.getProperty("database.url");
        System.clearProperty("database.url");      // an explicit -D option would (correctly) beat the settings file
        try {
            run("database:\n  url: \"jdbc:postgresql://127.0.0.1:1/fuel_marking\"\n");
        } finally {
            System.setProperty("database.url", url);
        }
        assertEquals(1, exit, output);
        assertTrue(output.contains("[FAIL] Database") && output.contains("127.0.0.1:1"), output);
    }

    @Test
    void anUnreachableCentralSystemAndKafkaAreReportedWithoutLeakingTheKey() throws Exception {
        run("""
                sync:
                  enabled: true
                  site-id: "site-123"
                  api-key: "KEY-SECRET-123"
                  base-url: "http://127.0.0.1:1"
                  kafka:
                    bootstrap-servers: "127.0.0.1:1"
                    properties:
                      sasl.jaas.config: 'org.apache.kafka.common.security.scram.ScramLoginModule required username="u" password="KAFKA-SECRET-456";'
                """);
        assertEquals(1, exit, output);
        assertTrue(output.contains("[FAIL] Central") && output.contains("cannot be reached"), output);
        assertTrue(output.contains("[FAIL] Kafka") && output.contains("127.0.0.1:1"), output);
        assertFalse(output.contains("KEY-SECRET-123") || output.contains("KAFKA-SECRET-456"), "keys and passwords are never printed: " + output);
    }

    @Test
    void syncEnabledWithoutSiteIdOrKeyIsCalledOut() throws Exception {
        run("sync:\n  enabled: true\n  kafka:\n    bootstrap-servers: \"127.0.0.1:1\"\n");
        assertTrue(output.contains("[FAIL] Central") && output.contains("sync.site-id is empty"), output);
        run("sync:\n  enabled: true\n  site-id: \"s1\"\n  kafka:\n    bootstrap-servers: \"127.0.0.1:1\"\n");
        assertTrue(output.contains("sync.api-key is empty"), output);
    }

    @Test
    void theCentralSystemsVerdictOnTheCredentialsIsShown() throws Exception {
        for (int status : new int[]{200, 401}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/sync/pull", ex -> { byte[] b = "{}".getBytes(); ex.sendResponseHeaders(status, b.length); ex.getResponseBody().write(b); ex.close(); });
            server.start();
            try {
                run("sync:\n  enabled: true\n  site-id: \"s1\"\n  api-key: \"k\"\n  base-url: \"http://127.0.0.1:" + server.getAddress().getPort() + "\"\n  kafka:\n    bootstrap-servers: \"127.0.0.1:1\"\n");
                if (status == 200) assertTrue(output.contains("[ OK ] Central") && output.contains("accepts this site's credentials"), output);
                else assertTrue(output.contains("[FAIL] Central") && output.contains("REJECTS"), output);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void anUnreachableNetworkScaleIsAWarningNotAFailure() throws Exception {
        run("hardware:\n  scale:\n    driver: tcp\n    tcp:\n      host: 127.0.0.1\n      port: 1\n");
        assertTrue(output.contains("[WARN] Scale"), output);
        assertEquals(0, exit, "a missing scale must not make the installation look broken: " + output);
    }

    @Test
    void theSettingsFileInUseIsNamed() throws Exception {
        run("fuel-marking:\n  dose-tolerance-percent: 2.0\n");
        assertTrue(output.contains("[ OK ] Settings") && output.contains("application-local.yml"), output);
    }
}
