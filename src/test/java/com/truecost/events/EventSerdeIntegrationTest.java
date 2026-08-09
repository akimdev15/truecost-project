package com.truecost.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Apicurio Avro serde wiring end to end against a real Apicurio registry and a real
 * broker, the same serializer and deserializer classes and config keys the application uses. A
 * SearchRequested produced through AvroKafkaSerializer, which auto registers the schema, is read
 * back through AvroKafkaDeserializer as a typed specific record with every field intact. This is
 * what proves the serde configuration is correct, since the serde only truly exercises the
 * registry protocol at runtime, not at compile time.
 */
@Testcontainers
class EventSerdeIntegrationTest {

    private static final String TOPIC = "trip-searches";

    @Container
    static GenericContainer<?> apicurio = new GenericContainer<>("apicurio/apicurio-registry:3.2.6")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forPort(8080).forStatusCode(200));

    static EmbeddedKafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC).kafkaPorts(0);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    @Test
    void searchRequestedRoundTripsThroughApicurioAvroSerde() {
        String registryUrl = "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080) + "/apis/registry/v3";
        String bootstrap = broker.getBrokersAsString();
        SearchRequested event = sampleSearch();

        try (Producer<String, SearchRequested> producer = new KafkaProducer<>(producerConfig(bootstrap, registryUrl))) {
            producer.send(new ProducerRecord<>(TOPIC, event.getRouteKey(), event));
            producer.flush();
        }

        try (Consumer<String, SearchRequested> consumer = new KafkaConsumer<>(consumerConfig(bootstrap, registryUrl))) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecord<String, SearchRequested> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC);

            assertThat(record.key()).isEqualTo(event.getRouteKey());
            SearchRequested read = record.value();
            assertThat(read.getRouteKey()).isEqualTo(event.getRouteKey());
            assertThat(read.getDateBucket()).isEqualTo(event.getDateBucket());
            assertThat(read.getEventId()).isEqualTo(event.getEventId());
            assertThat(read.getOriginLat()).isEqualTo(event.getOriginLat());
            assertThat(read.getDepartureAt()).isEqualTo(event.getDepartureAt());
            assertThat(read.getHasPersonalEzpass()).isEqualTo(event.getHasPersonalEzpass());
            assertThat(read.getCarClass()).isEqualTo(event.getCarClass());
        }
    }

    private static SearchRequested sampleSearch() {
        Instant departure = Instant.parse("2026-08-14T22:00:00Z");
        return SearchRequested.newBuilder()
                .setEventId(UUID.randomUUID())
                .setRouteKey("dr5ru-dr4e3")
                .setDateBucket("2026-08-14")
                .setOriginLat(40.7505)
                .setOriginLng(-73.9934)
                .setDestLat(39.95)
                .setDestLng(-75.16)
                .setDepartureAt(departure)
                .setReturnAt(departure.plusSeconds(2 * 24 * 3600))
                .setHasPersonalEzpass(false)
                .setCarClass("MIDSIZE")
                .setOccurredAt(Instant.now())
                .build();
    }

    private static Map<String, Object> producerConfig(String bootstrap, String registryUrl) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroKafkaSerializer.class);
        config.put(SchemaResolverConfig.REGISTRY_URL, registryUrl);
        config.put(SchemaResolverConfig.AUTO_REGISTER_ARTIFACT, "true");
        return config;
    }

    private static Map<String, Object> consumerConfig(String bootstrap, String registryUrl) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "event-serde-test");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class);
        config.put(SchemaResolverConfig.REGISTRY_URL, registryUrl);
        config.put(AvroSerdeConfig.USE_SPECIFIC_AVRO_READER, "true");
        return config;
    }
}
