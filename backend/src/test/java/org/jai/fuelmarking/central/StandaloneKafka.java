package org.jai.fuelmarking.central;

import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * A real single-node Kafka (KRaft) broker as a separate process, for end-to-end runs where Docker is not available:
 * {@code java -cp <test classpath> org.jai.fuelmarking.central.StandaloneKafka 19092}. Data is kept in a temp dir and
 * lost on exit, so this is for testing only.
 */
public final class StandaloneKafka {
    private StandaloneKafka() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19092;
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1).kafkaPorts(port);
        broker.afterPropertiesSet();
        System.out.println("KAFKA_READY " + broker.getBrokersAsString());
        Runtime.getRuntime().addShutdownHook(new Thread(broker::destroy));
        Thread.currentThread().join();
    }
}
