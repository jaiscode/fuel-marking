package org.jai.fuelmarking;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attacks the secured broker from outside (scripts/e2e-kafka-secure.sh): everything that must be refused is tried, and
 * the legitimate access is shown to still work. Skipped unless the script supplies the broker and the accounts.
 */
class KafkaSecurityStep {
    static final String TOPIC = "fuelmarking.master-data";
    static String bootstrap, trust, trustPass;
    static String adminUser, adminPass, backendUser, backendPass, siteAUser, siteAPass, siteAId, siteBUser, siteBPass, siteBId;

    static String p(String k) { return System.getProperty("e2e.ksec." + k); }

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(p("bootstrap") != null, "not a secure-Kafka end-to-end run");
        bootstrap = p("bootstrap"); trust = p("truststore"); trustPass = p("storepass");
        adminUser = p("admin.user"); adminPass = p("admin.pass"); backendUser = p("backend.user"); backendPass = p("backend.pass");
        siteAUser = p("a.user"); siteAPass = p("a.pass"); siteAId = p("a.id"); siteBUser = p("b.user"); siteBPass = p("b.pass"); siteBId = p("b.id");
    }

    /** Client settings for one account over TLS + SCRAM; short timeouts so refusals show up quickly. */
    static Properties client(String user, String pass) {
        Properties c = base();
        c.put("security.protocol", "SASL_SSL");
        c.put("sasl.mechanism", "SCRAM-SHA-512");
        c.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + user + "\" password=\"" + pass + "\";");
        c.put("ssl.truststore.type", "PKCS12");
        c.put("ssl.truststore.location", trust);
        c.put("ssl.truststore.password", trustPass);
        return c;
    }

    static Properties base() {
        Properties c = new Properties();
        c.put("bootstrap.servers", bootstrap);
        c.put("request.timeout.ms", "6000");
        c.put("default.api.timeout.ms", "10000");
        c.put("max.block.ms", "10000");
        c.put("socket.connection.setup.timeout.ms", "4000");
        c.put("socket.connection.setup.timeout.max.ms", "4000");
        return c;
    }

    static Throwable listFails(Properties props) {
        try (AdminClient a = AdminClient.create(props)) {
            a.listTopics().names().get();
            fail("the broker answered a request it should have refused");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static KafkaConsumer<String, String> consumer(Properties base, String group) {
        base.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        base.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        base.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        base.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(base);
    }

    /** Polls until it either reads something or fails; returns the exception it failed with, or null. */
    static Throwable pollOutcome(KafkaConsumer<String, String> c) {
        try {
            long end = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500));
            return null;
        } catch (RuntimeException e) {
            return e;
        } finally {
            c.close(Duration.ofMillis(200));
        }
    }

    // ---------------------------------------------------------------- who can do what

    @Test
    void aSiteReadsTheTopicWithItsOwnGroup() {
        try (KafkaConsumer<String, String> c = consumer(client(siteAUser, siteAPass), "fuelmarking-site-" + siteAId)) {
            c.subscribe(List.of(TOPIC));
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500));   // no exception = allowed
        }
    }

    @Test
    void aSiteCannotUseAnotherSitesGroup() {
        Throwable t = pollOutcome(subscribed(client(siteAUser, siteAPass), "fuelmarking-site-" + siteBId));
        assertInstanceOf(GroupAuthorizationException.class, t, "site A must not act as site B's consumer group: " + t);
    }

    @Test
    void aSiteCannotWriteToTheTopic() throws Exception {
        Properties props = client(siteAUser, siteAPass);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // The refusal arrives either from send().get() or - if the producer already noticed it - as an exception from send()
            // itself, depending on timing. Both mean "refused": look at the underlying cause.
            Throwable t = assertThrows(Throwable.class, () -> producer.send(new ProducerRecord<>(TOPIC, "SITE:forged", "{\"type\":\"SITE\",\"op\":\"UPSERT\",\"payload\":{}}")).get());
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // an idempotent producer needs a cluster-level right a site does not have, or the topic's Write right: either way, authorization
            assertInstanceOf(AuthorizationException.class, root, "a site must not be able to inject master data: " + t);
        }
    }

    @Test
    void aSiteCannotSeeOrReadOtherTopics() throws Exception {
        String other = "ksec.other." + System.nanoTime();
        try (AdminClient admin = AdminClient.create(client(adminUser, adminPass))) {
            admin.createTopics(List.of(new NewTopic(other, 1, (short) 1))).all().get();
        }
        try (AdminClient site = AdminClient.create(client(siteAUser, siteAPass))) {
            Set<String> visible = site.listTopics().names().get();
            assertTrue(visible.contains(TOPIC), "the site sees the one topic it may read: " + visible);
            assertFalse(visible.contains(other), "other topics are invisible to a site: " + visible);
        }
        KafkaConsumer<String, String> c = consumer(client(siteAUser, siteAPass), "fuelmarking-site-" + siteAId);
        c.assign(List.of(new TopicPartition(other, 0)));
        Throwable t = pollOutcome(c);
        assertInstanceOf(TopicAuthorizationException.class, t, "reading another topic must be refused: " + t);
    }

    @Test
    void theBackendCanOnlyWriteTheTopic() throws Exception {
        Properties props = client(backendUser, backendPass);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            assertNotNull(producer.send(new ProducerRecord<>(TOPIC, "SITE:acl-check", "{\"type\":\"SITE\",\"op\":\"UPSERT\",\"payload\":{\"id\":\"00000000-0000-0000-0000-000000000000\"}}")).get(), "the backend may write");
        }
        Throwable read = pollOutcome(subscribed(client(backendUser, backendPass), "backend-should-not-read"));
        assertInstanceOf(AuthorizationException.class, read, "the backend must not read the topic back: " + read);
        try (AdminClient admin = AdminClient.create(client(backendUser, backendPass))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> admin.createTopics(List.of(new NewTopic("backend.created", 1, (short) 1))).all().get());
            assertInstanceOf(AuthorizationException.class, e.getCause(), "the backend must not create topics: " + e.getCause());
            e = assertThrows(ExecutionException.class, () -> admin.deleteTopics(List.of(TOPIC)).all().get());
            assertInstanceOf(AuthorizationException.class, e.getCause(), "the backend must not delete the topic: " + e.getCause());
        }
    }

    static KafkaConsumer<String, String> subscribed(Properties props, String group) {
        KafkaConsumer<String, String> c = consumer(props, group);
        c.subscribe(List.of(TOPIC));
        return c;
    }

    // ---------------------------------------------------------------- who is turned away

    @Test
    void aWrongPasswordIsRefused() {
        assertInstanceOf(SaslAuthenticationException.class, listFails(client(siteAUser, "not-the-password")));
    }

    @Test
    void anUnknownAccountIsRefused() {
        assertInstanceOf(SaslAuthenticationException.class, listFails(client("site-nobody", siteAPass)));
    }

    @Test
    void aRevokedSiteCanNoLongerConnect() {
        Assumptions.assumeTrue(p("removed.user") != null, "no revoked account supplied");
        // the account existed (the script created it), was then removed with remove-site.sh, and its old settings file is still in someone's hands
        assertInstanceOf(SaslAuthenticationException.class, listFails(client(p("removed.user"), p("removed.pass"))), "a revoked site must be refused");
    }

    @Test
    void anAccountCannotBorrowAnotherAccountsPassword() {
        assertInstanceOf(SaslAuthenticationException.class, listFails(client(siteAUser, siteBPass)), "site A with site B's password");
    }

    @Test
    void aClientThatDoesNotTrustTheBrokersCertificateRefusesToTalk() {
        Properties props = client(siteAUser, siteAPass);
        props.remove("ssl.truststore.location");
        props.remove("ssl.truststore.password");
        props.remove("ssl.truststore.type");
        Throwable t = listFails(props);
        assertTrue(t instanceof AuthenticationException || String.valueOf(t).toLowerCase().contains("ssl") || String.valueOf(t).toLowerCase().contains("handshake"),
                "an unknown certificate authority must not be trusted: " + t);
    }

    @Test
    void aPlainUnencryptedConnectionGetsNothing() {
        Properties props = base();
        props.put("security.protocol", "PLAINTEXT");
        assertNotNull(listFails(props), "plaintext must not be answered");
    }

    @Test
    void aLoginWithoutEncryptionGetsNothing() {
        Properties props = client(siteAUser, siteAPass);
        props.put("security.protocol", "SASL_PLAINTEXT");
        assertNotNull(listFails(props), "a password over an unencrypted connection must not be accepted");
    }

    @Test
    void weakerLoginMechanismsAreNotOffered() {
        Properties plain = client(siteAUser, siteAPass);
        plain.put("sasl.mechanism", "PLAIN");
        plain.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + siteAUser + "\" password=\"" + siteAPass + "\";");
        assertInstanceOf(AuthenticationException.class, listFails(plain), "PLAIN is not enabled on the broker");

        Properties sha256 = client(siteAUser, siteAPass);
        sha256.put("sasl.mechanism", "SCRAM-SHA-256");
        assertInstanceOf(AuthenticationException.class, listFails(sha256), "only SCRAM-SHA-512 is enabled");
    }
}
