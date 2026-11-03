package com.solace.schemavalidator.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.ObjectMapper;
import com.solace.schemavalidator.registry.ArtifactReference;
import com.solace.schemavalidator.registry.CachingSchemaRegistryClient;
import com.solace.schemavalidator.registry.HttpSchemaRegistryClient;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaBundleLoader;
import com.solace.schemavalidator.registry.SchemaRegistryClient;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.registry.SchemaVersionMetadata;
import com.solace.schemavalidator.registry.SchemaVersionSummary;
import com.solace.schemavalidator.resolution.ResolvedTopicMapping;
import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import com.solace.schemavalidator.validation.CompiledValidator;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfiguration {

    @Bean
    public HttpClient schemaRegistryHttpClient(AppProperties properties) {
        return HttpClient.newBuilder()
            .connectTimeout(properties.getSchemaRegistry().getRequestTimeout())
            .build();
    }

    @Bean
    public SchemaRegistryClient schemaRegistryClient(HttpClient httpClient,
                                                     ObjectMapper objectMapper,
                                                     AppProperties properties,
                                                     Cache<String, SchemaVersionMetadata> versionMetadataCache,
                                                     Cache<String, Map<String, Object>> artifactMetadataCache,
                                                     Cache<String, List<SchemaVersionSummary>> artifactVersionsCache,
                                                     Cache<String, String> versionContentCache,
                                                     Cache<String, List<ArtifactReference>> versionReferencesCache) {
        SchemaRegistryClient delegate = new HttpSchemaRegistryClient(httpClient, objectMapper, properties.getSchemaRegistry());
        return new CachingSchemaRegistryClient(
            delegate,
            versionMetadataCache,
            artifactMetadataCache,
            artifactVersionsCache,
            versionContentCache,
            versionReferencesCache);
    }

    @Bean
    public Cache<String, ResolvedTopicMapping> topicResolutionCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxTopicEntries())
            .expireAfterWrite(properties.getCache().getTopicResolutionTtl())
            .build();
    }

    @Bean
    public Cache<SchemaVersionCoordinate, SchemaBundle> schemaBundleCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxSchemaEntries())
            .expireAfterWrite(properties.getCache().getSchemaBundleTtl())
            .build();
    }

    @Bean
    public Cache<String, SchemaVersionMetadata> registryVersionMetadataCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxRegistryEntries())
            .expireAfterWrite(properties.getCache().getRegistryLookupTtl())
            .build();
    }

    @Bean
    public Cache<String, Map<String, Object>> registryArtifactMetadataCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxRegistryEntries())
            .expireAfterWrite(properties.getCache().getRegistryLookupTtl())
            .build();
    }

    @Bean
    public Cache<String, List<SchemaVersionSummary>> registryArtifactVersionsCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxRegistryEntries())
            .expireAfterWrite(properties.getCache().getRegistryLookupTtl())
            .build();
    }

    @Bean
    public Cache<String, String> registryVersionContentCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxRegistryEntries())
            .expireAfterWrite(properties.getCache().getRegistryLookupTtl())
            .build();
    }

    @Bean
    public Cache<String, List<ArtifactReference>> registryVersionReferencesCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxRegistryEntries())
            .expireAfterWrite(properties.getCache().getRegistryLookupTtl())
            .build();
    }

    @Bean
    public Cache<SchemaVersionCoordinate, CompiledValidator> compiledValidatorCache(AppProperties properties) {
        return Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxValidatorEntries())
            .expireAfterWrite(properties.getCache().getCompiledValidatorTtl())
            .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SchemaBundleLoader schemaBundleLoader(SchemaRegistryClient schemaRegistryClient,
                                                 Cache<SchemaVersionCoordinate, SchemaBundle> schemaBundleCache,
                                                 SchemaValidationMetrics metrics) {
        return new SchemaBundleLoader(schemaRegistryClient, schemaBundleCache, metrics);
    }
}
