package org.cardanofoundation.lob.app.healthcheck;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shell-free Docker health check for the application readiness endpoint.
 * <p>
 * Packaged alone in healthcheck.jar with no dependencies, so there is no logger to use: diagnostics go to stderr.
 */
@SuppressWarnings("java:S106")
public final class Healthcheck {
    private static final String READINESS_ENDPOINT_FORMAT = "http://127.0.0.1:%d/actuator/health/readiness";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private Healthcheck() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * @return the process exit code: 0 when the readiness endpoint answers 2xx or 3xx, 1 otherwise
     */
    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("Healthcheck requires port");
            return 1;
        }

        try {
            var port = Integer.parseInt(args[0]);
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
                return 0;
            }

            System.err.printf("Health check returned HTTP %d%n", response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.printf("Health check interrupted: %s%n", exception.getMessage());
        } catch (Exception exception) {
            System.err.printf("Health check failed: %s: %s%n", exception.getClass().getName(), exception.getMessage());
        }

        return 1;
    }
}
