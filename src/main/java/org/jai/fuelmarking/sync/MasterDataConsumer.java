package org.jai.fuelmarking.sync;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jai.fuelmarking.config.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the compacted master-data topic and applies it locally. Offsets are committed only after a record is applied;
 * a record whose dependency has not arrived yet is retried (the consumer seeks back to it) rather than skipped.
 */
public class MasterDataConsumer implements Runnable, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MasterDataConsumer.class);

    private final Consumer<String, String> consumer;
    private final MasterDataApplier applier = new MasterDataApplier();
    private final List<ConsumerRecord<String, String>> parked = new ArrayList<>();
    private final Map<TopicPartition, Long> committed = new HashMap<>();
    private volatile boolean running = true;
    private final java.util.concurrent.atomic.AtomicBoolean loopStarted = new java.util.concurrent.atomic.AtomicBoolean();

    public MasterDataConsumer(Consumer<String, String> consumer) { this.consumer = consumer; }

    public static MasterDataConsumer create(String bootstrapServers, String topic, String siteId) {
        return create(bootstrapServers, topic, siteId, java.util.Map.of());
    }

    /** {@code extra} carries security settings (security.protocol, sasl.*, ssl.*) for a secured broker. */
    public static MasterDataConsumer create(String bootstrapServers, String topic, String siteId, Map<String, String> extra) {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(buildProperties(bootstrapServers, siteId, extra));
        // Always replay the topic from the beginning when (re)joining, ignoring the group's committed position. That position
        // lives in Kafka while the data lives in this machine's database, and the two can disagree: after a reinstall or a
        // restore from an older backup the group is "caught up" but the database is empty. Master data is small (sites, companies,
        // users) and applying it is idempotent, so replaying is cheap and makes the desktop self-healing.
        c.subscribe(List.of(topic), new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
            @Override public void onPartitionsRevoked(java.util.Collection<TopicPartition> partitions) { /* nothing to do */ }
            @Override public void onPartitionsAssigned(java.util.Collection<TopicPartition> partitions) { c.seekToBeginning(partitions); }
        });
        return new MasterDataConsumer(c);
    }

    /** The behaviour-critical settings win over {@code extra}: offsets are committed only after a record is applied. */
    static Properties buildProperties(String bootstrapServers, String siteId, Map<String, String> extra) {
        Properties p = new Properties();
        p.putAll(extra);
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "fuelmarking-site-" + siteId); // one group per site: each replays the full topic
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return p;
    }

    /**
     * Polls once and applies what it can; returns the number of records applied. A record whose dependency has not
     * arrived (a user before its site) is parked and retried, so ordering within the topic never blocks progress.
     * Offsets are committed only up to the earliest parked record, so a restart re-reads (idempotently) anything unfinished.
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> polled = consumer.poll(timeout);
        List<ConsumerRecord<String, String>> work = new ArrayList<>(parked);
        parked.clear();
        Map<TopicPartition, Long> highest = new HashMap<>();
        for (ConsumerRecord<String, String> r : polled) {
            work.add(r);
            highest.merge(new TopicPartition(r.topic(), r.partition()), r.offset(), Math::max);
        }

        int applied = 0;
        boolean progress = true;
        while (progress && !work.isEmpty()) {
            progress = false;
            List<ConsumerRecord<String, String>> waiting = new ArrayList<>();
            for (ConsumerRecord<String, String> r : work) {
                try {
                    Tx.run(c -> { applier.apply(c, r.value()); return null; });
                    applied++;
                    progress = true;
                } catch (MissingDependencyException e) {
                    log.debug("Master data {} waiting for dependency: {}", r.key(), e.getMessage());
                    waiting.add(r);
                } catch (RuntimeException e) {
                    log.error("Skipping unusable master data record {} @{}: {}", r.key(), r.offset(), e.getMessage());
                    progress = true;
                }
            }
            work = waiting;
        }
        parked.addAll(work);
        for (ConsumerRecord<String, String> r : parked)
            log.warn("Master data {} still waiting for a dependency", r.key());

        Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
        for (var e : highest.entrySet()) {
            long safe = e.getValue() + 1;
            for (ConsumerRecord<String, String> r : parked)
                if (new TopicPartition(r.topic(), r.partition()).equals(e.getKey())) safe = Math.min(safe, r.offset());
            if (safe > committed.getOrDefault(e.getKey(), -1L)) {
                commit.put(e.getKey(), new OffsetAndMetadata(safe));
                committed.put(e.getKey(), safe);
            }
        }
        if (!commit.isEmpty()) consumer.commitSync(commit);
        return applied;
    }

    @Override
    public void run() {
        loopStarted.set(true);
        while (running) {
            try {
                int n = pollOnce(Duration.ofSeconds(2));
                if (n > 0) log.info("Applied {} master data record(s)", n);
            } catch (WakeupException e) {
                break;
            } catch (RuntimeException e) {
                log.error("Master data consumer error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        consumer.close();
    }

    /** Stops the background loop (which then closes the consumer), or closes the consumer directly if it was only polled by hand. */
    @Override
    public void close() {
        running = false;
        if (loopStarted.get()) consumer.wakeup();   // KafkaConsumer is not thread-safe: let the loop thread close it
        else consumer.close();                      // leave the group promptly so a successor does not wait for the session timeout
    }
}
