package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.impl.BlockfrostPublisher;

@ExtendWith(MockitoExtension.class)
class BlockfrostPublisherTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private HttpResponse httpResponse;

    private BlockfrostPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new BlockfrostPublisher(httpClient);
        ReflectionTestUtils.setField(publisher, "blockfrostUrl", "https://ipfs.blockfrost.io/api/v0/ipfs/add");
        ReflectionTestUtils.setField(publisher, "blockfrostProjectId", "test-project-id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_success_returnsIpfsHash() throws IOException, InterruptedException {
        String responseBody = "{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmTestHash123\",\"size\":\"100\"}";
        when(httpResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("QmTestHash123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_ioException_returnsLeftWithProblemDetail() throws IOException, InterruptedException {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Error sending request to Blockfrost IPFS");
        assertThat(problem.getDetail()).contains("Connection refused");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_interruptedException_returnsLeftWithProblemDetail() throws IOException, InterruptedException {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("Thread interrupted"));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Error sending request to Blockfrost IPFS");
        assertThat(problem.getDetail()).contains("Thread interrupted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_invalidJsonResponse_returnsLeftWithProblemDetail() throws IOException, InterruptedException {
        when(httpResponse.body()).thenReturn("not-valid-json");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Error parsing Blockfrost IPFS response");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_emptyContent_stillSendsRequest() throws IOException, InterruptedException {
        String responseBody = "{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmEmptyHash\",\"size\":\"0\"}";
        when(httpResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Either<ProblemDetail, String> result = publisher.publish("");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("QmEmptyHash");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_requestContainsProjectIdHeader() throws IOException, InterruptedException {
        String responseBody = "{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmHash\",\"size\":\"50\"}";
        when(httpResponse.body()).thenReturn(responseBody);

        // Capture the request to verify headers
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertThat(request.headers().firstValue("project_id")).hasValue("test-project-id");
                    assertThat(request.headers().firstValue("Content-Type").orElse(""))
                            .startsWith("multipart/form-data; boundary=");
                    return httpResponse;
                });

        Either<ProblemDetail, String> result = publisher.publish("some content");

        assertThat(result.isRight()).isTrue();
    }
}
