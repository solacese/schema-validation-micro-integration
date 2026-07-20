package com.solace.schemavalidator.messaging;

import java.util.Map;

public interface SchemaResultPublisher {

    void publish(String topic, byte[] payload, Map<String, Object> headers);
}
