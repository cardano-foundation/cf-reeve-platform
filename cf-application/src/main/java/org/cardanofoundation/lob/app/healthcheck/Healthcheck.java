package org.cardanofoundation.lob.app.healthcheck;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shell-free Docker health check for the application readiness endpoint.
 */
public final class Healthcheck {
    private static final String READINESS_ENDPOINT_FORMAT = "http://127.0.0.1:%d/actuator/health/readiness";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private Healthcheck() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Healthcheck requires port");
            System.exit(1);
        }

        try {
            var port = Integer.valueOf(args[0]);
            var readinessEndpoint = URI.create(String.format(READINESS_ENDPOINT_FORMAT, port));
            var client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            var request = HttpRequest.newBuilder(readinessEndpoint)
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return;
            }

            System.err.printf("Health check returned HTTP %d%n", response.statusCode());
        } catch (Exception exception) {
            System.err.printf("Health check failed: %s: %s%n", exception.getClass().getName(), exception.getMessage());
        }

        System.exit(1);
    }
}
