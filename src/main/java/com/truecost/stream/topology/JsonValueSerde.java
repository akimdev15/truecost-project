package com.truecost.stream.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * A minimal Jackson backed Kafka serde for internal stream state such as the changelog for the
 * windowed route count store. Internal changelog and repartition topics do not need the Apicurio
 * Avro registry, which is reserved for the public event topics, so a plain JSON serde keeps this
 * readable with no schema subject to manage.
 */
public final class JsonValueSerde<T> implements Serde<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Class<T> type;

    public JsonValueSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("failed to serialize " + type.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return MAPPER.readValue(bytes, type);
            } catch (Exception e) {
                throw new SerializationException("failed to deserialize " + type.getSimpleName(), e);
            }
        };
    }
}
