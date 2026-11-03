package com.solace.schemavalidator.registry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.solace.schemavalidator.config.AppProperties;
import java.net.http.HttpClient;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class HttpSchemaRegistryClientTest {
    private final WireMockServer server = new WireMockServer(0);

    @AfterEach
    void stop() { server.stop(); }

    private HttpSchemaRegistryClient client() {
        server.start();
        var properties = new AppProperties.SchemaRegistry();
        properties.setBaseUrl(server.baseUrl() + "/apis/registry/v3/");
        return new HttpSchemaRegistryClient(HttpClient.newHttpClient(), JsonMapper.builder().build(), properties);
    }

    @Test
    void readsAllPagesInCreationOrderAndEncodesCoordinates() {
        var client = client();
        String endpoint = "/apis/registry/v3/groups/my%20group/artifacts/my%2Fschema/versions";
        server.stubFor(get(urlPathEqualTo(endpoint)).withQueryParam("offset", equalTo("0"))
            .withQueryParam("order", equalTo("asc")).withQueryParam("orderby", equalTo("createdOn"))
            .willReturn(okJson("""
                {"count":3,"versions":[
                {"version":"9","artifactType":"JSON","state":"ENABLED"},
                {"version":"10","artifactType":"JSON","state":"DISABLED"}]}
                """)));
        server.stubFor(get(urlPathEqualTo(endpoint)).withQueryParam("offset", equalTo("2"))
            .willReturn(okJson("""
                {"count":3,"versions":[{"version":"release-new","artifactType":"JSON","state":"ENABLED"}]}
                """)));
        assertThat(client.getArtifactVersions("my group", "my/schema"))
            .extracting(SchemaVersionSummary::version).containsExactly("9", "10", "release-new");
    }

    @Test
    void rejectsAnIncompletePaginationResponse() {
        var client = client();
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"count\":2,\"versions\":[]}")));
        assertThatThrownBy(() -> client.getArtifactVersions("g", "a"))
            .isInstanceOf(SchemaRegistryException.class).hasMessageContaining("empty page");
    }

    @Test
    void reportsAuthenticationFailure() {
        var client = client();
        server.stubFor(get(anyUrl()).willReturn(unauthorized()));
        assertThatThrownBy(client::getSystemInfo).isInstanceOf(SchemaRegistryException.class).hasMessageContaining("401");
    }
}
