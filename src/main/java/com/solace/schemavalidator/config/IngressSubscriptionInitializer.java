package com.solace.schemavalidator.config;

import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.Topic;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class IngressSubscriptionInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngressSubscriptionInitializer.class);

    private final AppProperties properties;
    private final Environment environment;

    public IngressSubscriptionInitializer(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void provisionQueueAndSubscriptions() {
        if (properties.getIngress().getTopicSubscriptions().isEmpty()) {
            return;
        }
        if (properties.getIngress().getPartitionCount() > 0) {
            LOGGER.info("event=partitioned_queue_mode queueName={} partitionCount={} — queue must be pre-provisioned via SEMP",
                properties.getIngress().getQueueName(),
                properties.getIngress().getPartitionCount());
            return;
        }
        JCSMPSession session = null;
        try {
            JCSMPProperties sessionProperties = new JCSMPProperties();
            sessionProperties.setProperty(JCSMPProperties.HOST,
                resolveProperty("spring.cloud.stream.binders.solace-input.environment.solace.java.host", "solace.java.host"));
            sessionProperties.setProperty(JCSMPProperties.VPN_NAME,
                resolveProperty("spring.cloud.stream.binders.solace-input.environment.solace.java.msg-vpn", "solace.java.msg-vpn"));
            sessionProperties.setProperty(JCSMPProperties.USERNAME,
                resolveProperty("spring.cloud.stream.binders.solace-input.environment.solace.java.client-username", "solace.java.client-username"));
            sessionProperties.setProperty(JCSMPProperties.PASSWORD,
                resolveProperty("spring.cloud.stream.binders.solace-input.environment.solace.java.client-password", "solace.java.client-password"));
            session = JCSMPFactory.onlyInstance().createSession(sessionProperties);
            session.connect();
            Queue queue = JCSMPFactory.onlyInstance().createQueue(properties.getIngress().getQueueName());
            EndpointProperties endpointProperties = new EndpointProperties();
            endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE);
            endpointProperties.setPermission(EndpointProperties.PERMISSION_CONSUME);
            session.provision(queue, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
            for (String subscription : properties.getIngress().getTopicSubscriptions()) {
                Topic topic = JCSMPFactory.onlyInstance().createTopic(subscription);
                try {
                    session.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);
                } catch (JCSMPErrorResponseException ex) {
                    if (!ex.getMessage().contains("Already Exists")) {
                        throw ex;
                    }
                }
            }
            LOGGER.info("event=queue_initialized queueName={} subscriptions={}",
                properties.getIngress().getQueueName(),
                properties.getIngress().getTopicSubscriptions().size());
        } catch (JCSMPException ex) {
            throw new IllegalStateException("Failed to provision ingress queue subscriptions", ex);
        } finally {
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private String resolveProperty(String primaryKey, String fallbackKey) {
        String value = environment.getProperty(primaryKey);
        if (StringUtils.hasText(value)) {
            return value;
        }
        value = environment.getProperty(fallbackKey);
        if (StringUtils.hasText(value)) {
            return value;
        }
        throw new IllegalStateException("Missing required Solace property '" + primaryKey + "' (or fallback '" + fallbackKey + "')");
    }
}
