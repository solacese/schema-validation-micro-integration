package com.solace.schemavalidator.observability;

import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TraceIdExtractor {

    public static final String TRACE_ID_HEADER = "mi_trace_id";

    public String extractOrCreate(Message<?> message) {
        return extractOrCreate(message.getHeaders());
    }

    public String extractOrCreate(MessageHeaders headers) {
        Object existing = headers.get(TRACE_ID_HEADER);
        if (existing instanceof String traceId && StringUtils.hasText(traceId)) {
            return traceId;
        }
        Object correlation = headers.get("solace_correlationId");
        if (correlation instanceof String traceId && StringUtils.hasText(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }
}
