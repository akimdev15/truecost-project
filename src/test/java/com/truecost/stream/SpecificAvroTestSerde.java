package com.truecost.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * A registry free Avro serde for the topology unit tests. TopologyTestDriver knows the concrete
 * record type on both ends, so a plain SpecificDatumWriter and reader over the compiled schema
 * round trips the events without needing the Apicurio registry that the running application uses.
 * This keeps the topology test a pure in memory unit test with no container.
 */
final class SpecificAvroTestSerde<T extends SpecificRecord> implements Serde<T> {

    private final Schema schema;

    SpecificAvroTestSerde(Schema schema) {
        this.schema = schema;
    }

    @Override
    public Serializer<T> serializer() {
        DatumWriter<T> writer = new SpecificDatumWriter<>(schema);
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            try {
                writer.write(data, encoder);
                encoder.flush();
            } catch (IOException e) {
                throw new SerializationException("failed to encode " + schema.getName(), e);
            }
            return out.toByteArray();
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        DatumReader<T> reader = new SpecificDatumReader<>(schema);
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            try {
                return reader.read(null, decoder);
            } catch (IOException e) {
                throw new SerializationException("failed to decode " + schema.getName(), e);
            }
        };
    }
}
