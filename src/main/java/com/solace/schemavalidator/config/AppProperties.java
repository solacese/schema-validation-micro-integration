package com.solace.schemavalidator.config;

import com.solace.schemavalidator.resolution.ArtifactType;
import com.solace.schemavalidator.resolution.VersionStrategyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app")
@Validated
public class AppProperties {

    @Valid
    @NotNull
    private final Ingress ingress = new Ingress();

    @Valid
    @NotNull
    private final Routing routing = new Routing();

    @Valid
    @NotNull
    private final SchemaRegistry schemaRegistry = new SchemaRegistry();

    @Valid
    @NotNull
    private final Cache cache = new Cache();

    @Valid
    @NotNull
    private final Prewarm prewarm = new Prewarm();

    private int maxPayloadBytes = 1_048_576; // 1MB default

    @NotNull
    private Duration validationTimeout = Duration.ofSeconds(5);

    @Valid
    @NotEmpty
    private List<Mapping> mappings = new ArrayList<>();

    public Ingress getIngress() {
        return ingress;
    }

    public Routing getRouting() {
        return routing;
    }

    public SchemaRegistry getSchemaRegistry() {
        return schemaRegistry;
    }

    public Cache getCache() {
        return cache;
    }

    public Prewarm getPrewarm() {
        return prewarm;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public Duration getValidationTimeout() {
        return validationTimeout;
    }

    public void setValidationTimeout(Duration validationTimeout) {
        this.validationTimeout = validationTimeout;
    }

    public List<Mapping> getMappings() {
        return mappings;
    }

    public void setMappings(List<Mapping> mappings) {
        this.mappings = mappings;
    }

    public static class Ingress {
        @NotBlank
        private String queueName;
        private List<String> topicSubscriptions = new ArrayList<>();
        private int partitionCount = 0;
        private int partitionRebalanceDelay = 5;

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public List<String> getTopicSubscriptions() {
            return topicSubscriptions;
        }

        public void setTopicSubscriptions(List<String> topicSubscriptions) {
            this.topicSubscriptions = topicSubscriptions;
        }

        public int getPartitionCount() {
            return partitionCount;
        }

        public void setPartitionCount(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        public int getPartitionRebalanceDelay() {
            return partitionRebalanceDelay;
        }

        public void setPartitionRebalanceDelay(int partitionRebalanceDelay) {
            this.partitionRebalanceDelay = partitionRebalanceDelay;
        }
    }

    public static class Routing {
        @NotBlank
        private String validatedTopicTemplate;
        @NotBlank
        private String rejectTopicTemplate;
        @NotBlank
        private String processingErrorTopicTemplate;

        public String getValidatedTopicTemplate() {
            return validatedTopicTemplate;
        }

        public void setValidatedTopicTemplate(String validatedTopicTemplate) {
            this.validatedTopicTemplate = validatedTopicTemplate;
        }

        public String getRejectTopicTemplate() {
            return rejectTopicTemplate;
        }

        public void setRejectTopicTemplate(String rejectTopicTemplate) {
            this.rejectTopicTemplate = rejectTopicTemplate;
        }

        public String getProcessingErrorTopicTemplate() {
            return processingErrorTopicTemplate;
        }

        public void setProcessingErrorTopicTemplate(String processingErrorTopicTemplate) {
            this.processingErrorTopicTemplate = processingErrorTopicTemplate;
        }
    }

    public static class SchemaRegistry {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        @NotNull
        private Duration requestTimeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class Cache {
        @NotNull
        private Duration topicResolutionTtl = Duration.ofMinutes(15);
        @NotNull
        private Duration registryLookupTtl = Duration.ofMinutes(60);
        @NotNull
        private Duration schemaBundleTtl = Duration.ofMinutes(60);
        @NotNull
        private Duration compiledValidatorTtl = Duration.ofMinutes(60);
        @NotNull
        @Min(1)
        private long maxTopicEntries = 5_000;
        @Min(1)
        private long maxRegistryEntries = 1_000;
        @Min(1)
        private long maxSchemaEntries = 1_000;
        @Min(1)
        private long maxValidatorEntries = 1_000;

        public Duration getTopicResolutionTtl() {
            return topicResolutionTtl;
        }

        public void setTopicResolutionTtl(Duration topicResolutionTtl) {
            this.topicResolutionTtl = topicResolutionTtl;
        }

        public Duration getRegistryLookupTtl() {
            return registryLookupTtl;
        }

        public void setRegistryLookupTtl(Duration registryLookupTtl) {
            this.registryLookupTtl = registryLookupTtl;
        }

        public Duration getSchemaBundleTtl() {
            return schemaBundleTtl;
        }

        public void setSchemaBundleTtl(Duration schemaBundleTtl) {
            this.schemaBundleTtl = schemaBundleTtl;
        }

        public Duration getCompiledValidatorTtl() {
            return compiledValidatorTtl;
        }

        public void setCompiledValidatorTtl(Duration compiledValidatorTtl) {
            this.compiledValidatorTtl = compiledValidatorTtl;
        }

        public long getMaxTopicEntries() {
            return maxTopicEntries;
        }

        public void setMaxTopicEntries(long maxTopicEntries) {
            this.maxTopicEntries = maxTopicEntries;
        }

        public long getMaxRegistryEntries() {
            return maxRegistryEntries;
        }

        public void setMaxRegistryEntries(long maxRegistryEntries) {
            this.maxRegistryEntries = maxRegistryEntries;
        }

        public long getMaxSchemaEntries() {
            return maxSchemaEntries;
        }

        public void setMaxSchemaEntries(long maxSchemaEntries) {
            this.maxSchemaEntries = maxSchemaEntries;
        }

        public long getMaxValidatorEntries() {
            return maxValidatorEntries;
        }

        public void setMaxValidatorEntries(long maxValidatorEntries) {
            this.maxValidatorEntries = maxValidatorEntries;
        }
    }

    public static class Prewarm {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mapping {
        @NotBlank
        private String id;
        @NotBlank
        private String topicPattern;
        @NotBlank
        private String schemaGroup;
        @NotBlank
        private String artifactId;
        @NotNull
        private ArtifactType artifactType;
        @Valid
        @NotNull
        private VersionStrategy versionStrategy = new VersionStrategy();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTopicPattern() {
            return topicPattern;
        }

        public void setTopicPattern(String topicPattern) {
            this.topicPattern = topicPattern;
        }

        public String getSchemaGroup() {
            return schemaGroup;
        }

        public void setSchemaGroup(String schemaGroup) {
            this.schemaGroup = schemaGroup;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public ArtifactType getArtifactType() {
            return artifactType;
        }

        public void setArtifactType(ArtifactType artifactType) {
            this.artifactType = artifactType;
        }

        public VersionStrategy getVersionStrategy() {
            return versionStrategy;
        }

        public void setVersionStrategy(VersionStrategy versionStrategy) {
            this.versionStrategy = versionStrategy;
        }
    }

    public static class VersionStrategy {
        @NotNull
        private VersionStrategyType type;
        private String capture;
        private String value;

        public VersionStrategyType getType() {
            return type;
        }

        public void setType(VersionStrategyType type) {
            this.type = type;
        }

        public String getCapture() {
            return capture;
        }

        public void setCapture(String capture) {
            this.capture = capture;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
