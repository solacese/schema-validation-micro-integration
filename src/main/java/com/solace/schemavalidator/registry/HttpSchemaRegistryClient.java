package com.solace.schemavalidator.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class HttpSchemaRegistryClient implements SchemaRegistryClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties.SchemaRegistry properties;
    private final String authorizationHeader;

    public HttpSchemaRegistryClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties.SchemaRegistry properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.authorizationHeader = "Basic " + Base64.getEncoder()
            .encodeToString((properties.getUsername() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression) {
        JsonNode node = readJson(get(path("/groups/%s/artifacts/%s/versions/%s", groupId, artifactId, versionExpression)));
        return new SchemaVersionMetadata(
            node.path("groupId").asText(groupId),
            node.path("artifactId").asText(artifactId),
            node.path("version").asText(),
            ArtifactType.fromRegistryType(node.path("artifactType").asText()));
    }

    @Override
    public Map<String, Object> getArtifactMetadata(String groupId, String artifactId) {
        JsonNode node = readJson(get(path("/groups/%s/artifacts/%s", groupId, artifactId)));
        return objectMapper.convertValue(node, new TypeReference<>() { });
    }

    @Override
    public List<SchemaVersionSummary> getArtifactVersions(String groupId, String artifactId) {
        JsonNode node = readJson(get(path("/groups/%s/artifacts/%s/versions", groupId, artifactId)));
        JsonNode versionsNode = node.path("versions");
        if (!versionsNode.isArray()) {
            throw new SchemaRegistryException("Schema Registry version list response was missing the versions array");
        }
        try {
            List<Map<String, Object>> versions = objectMapper.convertValue(versionsNode, new TypeReference<>() { });
            return versions.stream()
                .map(version -> new SchemaVersionSummary(
                    String.valueOf(version.getOrDefault("groupId", groupId)),
                    String.valueOf(version.getOrDefault("artifactId", artifactId)),
                    String.valueOf(version.get("version")),
                    ArtifactType.fromRegistryType(String.valueOf(version.get("artifactType"))),
                    String.valueOf(version.getOrDefault("state", ""))))
                .toList();
        } catch (IllegalArgumentException ex) {
            throw new SchemaRegistryException("Failed to decode schema versions", ex);
        }
    }

    @Override
    public String getVersionContent(String groupId, String artifactId, String versionExpression) {
        return execute(get(path("/groups/%s/artifacts/%s/versions/%s/content", groupId, artifactId, versionExpression)),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    @Override
    public List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression) {
        JsonNode node = readJson(get(path("/groups/%s/artifacts/%s/versions/%s/references", groupId, artifactId, versionExpression)));
        try {
            return objectMapper.readerForListOf(ArtifactReference.class).readValue(node);
        } catch (IOException e) {
            throw new SchemaRegistryException("Failed to decode schema references", e);
        }
    }

    @Override
    public Map<String, Object> getSystemInfo() {
        JsonNode node = readJson(get("/system/info"));
        return objectMapper.convertValue(node, new TypeReference<>() { });
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(properties.getBaseUrl() + path))
            .timeout(properties.getRequestTimeout())
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .GET()
            .build();
    }

    private JsonNode readJson(HttpRequest request) {
        HttpResponse<String> response = execute(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new SchemaRegistryException("Failed to parse registry response", e);
        }
    }

    private <T> HttpResponse<T> execute(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            HttpResponse<T> response = httpClient.send(request, bodyHandler);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response;
            }
            throw new SchemaRegistryException("Schema Registry request failed with HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchemaRegistryException("Schema Registry request failed", e);
        } catch (IOException e) {
            throw new SchemaRegistryException("Schema Registry request failed", e);
        }
    }

    private String path(String template, String groupId, String artifactId) {
        return template.formatted(encode(groupId), encode(artifactId));
    }

    private String path(String template, String groupId, String artifactId, String versionExpression) {
        return template.formatted(encode(groupId), encode(artifactId), encode(versionExpression));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
