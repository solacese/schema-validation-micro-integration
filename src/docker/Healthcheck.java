import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Healthcheck {
    private Healthcheck() {
    }

    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("PORT", "8088");
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:" + port + "/actuator/health";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body == null || !body.contains("\"status\":\"UP\"")) {
            System.err.printf("unhealthy: HTTP %d %s%n", response.statusCode(), body);
            System.exit(1);
        }
    }
}
