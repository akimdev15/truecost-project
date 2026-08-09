package com.truecost.stream.config;

import com.truecost.events.HotRouteSignal;
import com.truecost.events.QuoteSnapshot;
import com.truecost.events.SearchRequested;
import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Apicurio Avro serdes the prefetcher's topology and listeners use. This configuration
 * lives in the prefetcher-only com.truecost.stream package, so only the prefetcher deployable
 * creates these beans and contacts the registry, the API produces through the serializer
 * configured in application.yml instead.
 */
@Configuration
public class KafkaSerdeConfig {

    private final String registryUrl;

    public KafkaSerdeConfig(@Value("${spring.kafka.properties.apicurio.registry.url}") String registryUrl) {
        this.registryUrl = registryUrl;
    }

    @Bean
    public Serde<SearchRequested> searchRequestedSerde() {
        return avroSerde();
    }

    @Bean
    public Serde<HotRouteSignal> hotRouteSignalSerde() {
        return avroSerde();
    }

    @Bean
    public Serde<QuoteSnapshot> quoteSnapshotSerde() {
        return avroSerde();
    }

    private <T extends SpecificRecord> Serde<T> avroSerde() {
        AvroKafkaSerializer<T> serializer = new AvroKafkaSerializer<>();
        serializer.configure(Map.of(
                SchemaResolverConfig.REGISTRY_URL, registryUrl,
                SchemaResolverConfig.AUTO_REGISTER_ARTIFACT, "true"), false);
        AvroKafkaDeserializer<T> deserializer = new AvroKafkaDeserializer<>();
        deserializer.configure(Map.of(
                SchemaResolverConfig.REGISTRY_URL, registryUrl,
                AvroSerdeConfig.USE_SPECIFIC_AVRO_READER, "true"), false);
        return Serdes.serdeFrom(serializer, deserializer);
    }
}
