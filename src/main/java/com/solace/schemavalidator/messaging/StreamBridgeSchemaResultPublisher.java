package com.solace.schemavalidator.messaging;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import java.util.Map;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.cloud.stream.function.StreamBridge;

@Component
public class StreamBridgeSchemaResultPublisher implements SchemaResultPublisher {

    private static final String OUTPUT_BINDING = "schemaValidationResults-out-0";

    private final StreamBridge streamBridge;

    public StreamBridgeSchemaResultPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public void publish(String topic, byte[] payload, Map<String, Object> headers) {
        MessageBuilder<byte[]> builder = MessageBuilder.withPayload(payload)
            .setHeader(BinderHeaders.TARGET_DESTINATION, topic)
            .setHeader(SolaceBinderHeaders.TARGET_DESTINATION_TYPE, "topic");
        headers.forEach(builder::setHeader);
        Object partitionKey = headers.get(SolaceBinderHeaders.PARTITION_KEY);
        if (partitionKey == null) {
            partitionKey = headers.get("solace_scst_partitionKey");
        }
        if (partitionKey != null) {
            builder.setHeader(SolaceBinderHeaders.PARTITION_KEY, partitionKey);
        }
        boolean accepted = streamBridge.send(OUTPUT_BINDING, builder.build());
        if (!accepted) {
            throw new IllegalStateException("StreamBridge rejected outbound publish to topic '" + topic + "'");
        }
    }
}
