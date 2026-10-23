package org.jai.fuelmarking.config;

import org.jai.fuelmarking.config.YamlConfigurationLoader.FuelMarking;
import org.jai.fuelmarking.config.YamlConfigurationLoader.Sync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The settings file that sits next to the jar - how a site (especially a Windows one) is configured without editing the jar. */
class LocalSettingsFileTest {
    @TempDir Path dir;
    private String originalDatabaseUrl;

    @BeforeEach
    void setAside() {
        // The suite is usually run with -Ddatabase.url=... which (by design) beats any settings file. Take it out of the way for
        // these tests and put back exactly what was there, so the tests that run after this one still find their database.
        originalDatabaseUrl = System.getProperty("database.url");
        System.clearProperty("database.url");
    }

    @AfterEach
    void restore() {
        System.clearProperty("fuelmarking.config");
        if (originalDatabaseUrl != null) System.setProperty("database.url", originalDatabaseUrl);
        else System.clearProperty("database.url");
        YamlConfigurationLoader.reload();     // back to the built-in settings for every other test
    }

    Path write(String name, String yaml) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, yaml);
        return f;
    }

    void use(Path f) {
        System.setProperty("fuelmarking.config", f.toString());
        YamlConfigurationLoader.reload();
    }

    @Test
    void valuesInTheFileWinAndEverythingElseStaysAsBuiltIn() throws Exception {
        use(write("application-local.yml", """
                database:
                  url: "jdbc:postgresql://localhost:5432/site_db"
                fuel-marking:
                  dose-tolerance-percent: 3.5
                sync:
                  enabled: true
                  site-id: "11111111-2222-3333-4444-555555555555"
                """));
        assertEquals("jdbc:postgresql://localhost:5432/site_db", YamlConfigurationLoader.getString("database.url"));
        assertEquals(3.5, FuelMarking.getDoseTolerancePercent());
        assertTrue(Sync.isEnabled());
        assertEquals("11111111-2222-3333-4444-555555555555", Sync.getSiteId());
        // untouched settings, including siblings inside a section that the file only partly overrides
        assertEquals("postgres", YamlConfigurationLoader.getString("database.username"));
        assertEquals(10, YamlConfigurationLoader.getInt("database.pool.maximum-pool-size"));
        assertEquals(10.0, FuelMarking.getRumDosagePer1000L());
        assertEquals("fuelmarking.master-data", Sync.getMasterDataTopic());
    }

    @Test
    void theSecuredKafkaLoginAndAWindowsStylePathSurviveExactly() throws Exception {
        String jaas = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"site-abc\" password=\"s3cr3t;with;semicolons\";";
        use(write("application-local.yml", """
                sync:
                  kafka:
                    bootstrap-servers: "kafka.example.com:9094"
                    properties:
                      security.protocol: SASL_SSL
                      sasl.mechanism: SCRAM-SHA-512
                      sasl.jaas.config: 'org.apache.kafka.common.security.scram.ScramLoginModule required username="site-abc" password="s3cr3t;with;semicolons";'
                      ssl.truststore.type: PKCS12
                      ssl.truststore.location: 'C:\\Program Files\\Fuel Marking\\certs\\truststore.p12'
                      ssl.truststore.password: "p@ss word"
                """));
        Map<String, String> p = Sync.getKafkaProperties();
        assertEquals("kafka.example.com:9094", Sync.getKafkaBootstrapServers());
        assertEquals("SASL_SSL", p.get("security.protocol"));
        assertEquals(jaas, p.get("sasl.jaas.config"), "quotes and semicolons arrive untouched, with no shell quoting involved");
        assertEquals("C:\\Program Files\\Fuel Marking\\certs\\truststore.p12", p.get("ssl.truststore.location"), "backslashes and spaces survive");
        assertEquals("p@ss word", p.get("ssl.truststore.password"));
    }

    @Test
    void aListInTheFileReplacesTheBuiltInList() throws Exception {
        use(write("application-local.yml", "flyway:\n  locations:\n    - \"classpath:db/migration\"\n    - \"classpath:db/extra\"\n"));
        assertEquals("classpath:db/migration", YamlConfigurationLoader.getString("flyway.locations.0", null));
        assertEquals("classpath:db/extra", YamlConfigurationLoader.getString("flyway.locations.1", null));
    }

    @Test
    void aSingleOptionStillBeatsTheFile() throws Exception {
        use(write("application-local.yml", "database:\n  url: \"jdbc:postgresql://localhost:5432/from_file\"\n"));
        System.setProperty("database.url", "jdbc:postgresql://localhost:5432/from_option");
        assertEquals("jdbc:postgresql://localhost:5432/from_option", YamlConfigurationLoader.getString("database.url"));
    }

    @Test
    void anEmptyOrCommentOnlyFileChangesNothing() throws Exception {
        use(write("application-local.yml", "# nothing here yet\n"));
        assertEquals(2.0, FuelMarking.getDoseTolerancePercent());
    }

    @Test
    void aMissingExplicitFileIsReportedByNameRatherThanIgnored() {
        System.setProperty("fuelmarking.config", dir.resolve("typo-in-the-name.yml").toString());
        RuntimeException e = assertThrows(RuntimeException.class, YamlConfigurationLoader::reload);
        assertTrue(e.getMessage().contains("typo-in-the-name.yml") && e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void aBrokenFileIsReportedWithItsLocation() throws Exception {
        System.setProperty("fuelmarking.config", write("application-local.yml", "sync: [unclosed\n  enabled: ::\n").toString());
        RuntimeException e = assertThrows(RuntimeException.class, YamlConfigurationLoader::reload);
        assertTrue(e.getMessage().contains("application-local.yml"), e.getMessage());
    }

    @Test
    void mergeIsRecursiveAndReplacesNonObjects() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var base = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("a:\n  b: 1\n  c: 2\nlist: [1, 2]\nkeep: x\n");
        var over = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("a:\n  b: 9\n  d: 4\nlist: [7]\n");
        YamlConfigurationLoader.merge(base, over);
        assertEquals("{\"a\":{\"b\":9,\"c\":2,\"d\":4},\"list\":[7],\"keep\":\"x\"}", base.toString());
    }
}
