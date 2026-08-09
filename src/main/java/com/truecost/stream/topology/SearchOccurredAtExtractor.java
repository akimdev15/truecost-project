package com.truecost.stream.topology;

import com.truecost.events.SearchRequested;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Uses the search's occurredAt as the event time for windowing. Falling back to the record's own
 * broker timestamp only when the value is missing keeps the topology robust against a malformed
 * record without letting it derail stream time.
 */
public class SearchOccurredAtExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof SearchRequested search && search.getOccurredAt() != null) {
            return search.getOccurredAt().toEpochMilli();
        }
        return record.timestamp() >= 0 ? record.timestamp() : partitionTime;
    }
}
