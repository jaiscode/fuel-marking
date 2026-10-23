package org.jai.fuelmarking.central.master;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Sends to a single-partition compacted topic: the key is "TYPE:id" so the topic always holds the latest version of every
 * record, and one partition keeps sites ahead of the users that reference them.
 */
@Component
public class KafkaMasterDataSink implements MasterDataSink {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaMasterDataSink(KafkaTemplate<String, String> kafka, @Value("${fuelmarking.master-data-topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Bean
    NewTopic masterDataTopic(@Value("${fuelmarking.master-data-topic}") String name,
                             @Value("${fuelmarking.master-data-replicas:1}") int replicas) {
        return TopicBuilder.name(name).partitions(1).replicas(replicas).config("cleanup.policy", "compact").build();
    }

    @Override
    public void send(String key, String value) throws Exception {
        kafka.send(topic, key, value).get(15, TimeUnit.SECONDS);
    }
}
