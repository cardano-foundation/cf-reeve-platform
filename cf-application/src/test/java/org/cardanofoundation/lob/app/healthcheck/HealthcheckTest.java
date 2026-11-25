package org.cardanofoundation.lob.app.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HealthcheckTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 302})
    void succeedsWhenReadinessAnswersSuccessOrRedirect(int status) throws IOException {
        int port = startReadinessServer(status);

        assertThat(Healthcheck.run(new String[]{String.valueOf(port)})).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    void failsWhenReadinessAnswersError(int status) throws IOException {
        int port = startReadinessServer(status);

        assertThat(Healthcheck.run(new String[]{String.valueOf(port)})).isEqualTo(1);
    }

    @Test
    void failsWhenNothingListensOnPort() throws IOException {
        int port = startReadinessServer(200);
        server.stop(0);
        server = null;

        assertThat(Healthcheck.run(new String[]{String.valueOf(port)})).isEqualTo(1);
    }

    @Test
    void failsWithoutPortArgument() {
        assertThat(Healthcheck.run(new String[0])).isEqualTo(1);
    }

    @Test
    void failsWithNonNumericPort() {
        assertThat(Healthcheck.run(new String[]{"not-a-port"})).isEqualTo(1);
    }

    @Test
    void restoresInterruptFlagWhenInterrupted() throws IOException {
        int port = startReadinessServer(200);
        Thread.currentThread().interrupt();
        try {
            assertThat(Healthcheck.run(new String[]{String.valueOf(port)})).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private int startReadinessServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }
}
