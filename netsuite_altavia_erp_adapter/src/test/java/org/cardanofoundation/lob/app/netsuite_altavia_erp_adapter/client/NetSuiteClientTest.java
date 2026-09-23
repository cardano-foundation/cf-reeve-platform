package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetSuiteClientTest {

    private static String pem;

    // Nothing listens on this loopback port, so the OS refuses the connection immediately
    // instead of hanging until a socket timeout - the same failure shape (a RestClientException
    // wrapping an I/O error) a real NetSuite connect/read timeout would produce.
    private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();

        pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NetSuiteClient.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(NetSuiteClient.class)).detachAppender(logAppender);
    }

    private NetSuiteClient clientWith(String privateKeyPem) {
        return clientWith(privateKeyPem, "https://base");
    }

    private NetSuiteClient clientWith(String privateKeyPem, String baseUrl) {
        // tokenUrl also points at the unreachable address: refreshToken() runs before every call
        // (no cached token yet) and swallows its own errors, but a real hostname here would add
        // a slow, environment-dependent DNS failure to every test using this overload.
        return new NetSuiteClient(new ObjectMapper(), RestClient.create(),
                baseUrl, UNREACHABLE_BASE_URL, privateKeyPem, "cert-1", "client-1", 100);
    }

    private Object loadPrivateKey(NetSuiteClient client) throws Exception {
        Method method = NetSuiteClient.class.getDeclaredMethod("loadPrivateKey");
        method.setAccessible(true);

        return method.invoke(client);
    }

    @Test
    void parsesAPemSuppliedDirectlyRatherThanFromAFile() throws Exception {
        assertThat(loadPrivateKey(clientWith(pem))).isNotNull();
    }

    @Test
    void toleratesPemsWithCarriageReturnsAndSurroundingWhitespace() {
        String crlfPem = "  \r\n" + pem.replace("\n", "\r\n") + "\r\n  ";

        assertThatCode(() -> loadPrivateKey(clientWith(crlfPem))).doesNotThrowAnyException();
    }

    @Test
    void exposesTheBaseUrlItWasConstructedWith() {
        assertThat(clientWith(pem).getBaseUrl()).isEqualTo("https://base");
    }

    @Test
    void testConnectionReturnsNetsuiteApiErrorInsteadOfThrowingWhenTheCallTimesOutOrFails() {
        NetSuiteClient client = clientWith(pem, UNREACHABLE_BASE_URL);

        Either<ProblemDetail, Void> result = client.testConnection();

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("NETSUITE_API_ERROR");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void testConnectionLogsWhenTheCallTimesOutOrFails() {
        NetSuiteClient client = clientWith(pem, UNREACHABLE_BASE_URL);

        client.testConnection();

        List<ILoggingEvent> errors = logAppender.list;
        assertThat(errors)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Error calling NetSuite API"));
    }

    @Test
    void retrieveLatestNetsuiteTransactionLinesReturnsNetsuiteApiErrorInsteadOfThrowingWhenTheCallTimesOutOrFails() {
        NetSuiteClient client = clientWith(pem, UNREACHABLE_BASE_URL);

        Either<ProblemDetail, java.util.Optional<List<String>>> result =
                client.retrieveLatestNetsuiteTransactionLines(LocalDate.now(), LocalDate.now());

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("NETSUITE_API_ERROR");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void retrieveLatestNetsuiteTransactionLinesLogsWhenTheCallTimesOutOrFails() {
        NetSuiteClient client = clientWith(pem, UNREACHABLE_BASE_URL);

        client.retrieveLatestNetsuiteTransactionLines(LocalDate.now(), LocalDate.now());

        List<ILoggingEvent> errors = logAppender.list;
        assertThat(errors)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Error calling NetSuite API"));
    }

}
