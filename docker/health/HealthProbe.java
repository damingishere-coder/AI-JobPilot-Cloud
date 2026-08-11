import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthProbe {
    private HealthProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("health probe requires exactly one URL");
            System.exit(2);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception e) {
            System.err.println("health probe failed: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
