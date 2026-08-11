package org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


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
        publisher = new BlockfrostPublisher("https://ipfs.blockfrost.io/api/v0/ipfs/add", "test-project-id",
                httpClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_success_returnsIpfsHash() throws IOException, InterruptedException {
        String responseBody = "{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmTestHash123\",\"size\":\"100\"}";
        when(httpResponse.statusCode()).thenReturn(200);
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
        when(httpResponse.statusCode()).thenReturn(200);
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
        when(httpResponse.statusCode()).thenReturn(200);
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
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);

        List<HttpRequest> requests = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return httpResponse;
                });

        Either<ProblemDetail, String> result = publisher.publish("some content");

        assertThat(result.isRight()).isTrue();
        // Both calls carry the project id; only the upload is a multipart body.
        assertThat(requests).allSatisfy(request ->
                assertThat(request.headers().firstValue("project_id")).hasValue("test-project-id"));
        assertThat(requests.get(0).headers().firstValue("Content-Type").orElse(""))
                .startsWith("multipart/form-data; boundary=");
    }

    /**
     * Blockfrost's add does NOT pin, and unpinned objects are garbage-collected — so publishing has to
     * pin explicitly. It never did, which left every published envelope collectable.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publish_pinsAfterAddingSoTheEnvelopeSurvives() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmHash\",\"size\":\"50\"}");

        List<HttpRequest> requests = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return httpResponse;
                });

        assertThat(publisher.publish("some content").get()).isEqualTo("QmHash");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().toString()).endsWith("/ipfs/add");
        assertThat(requests.get(1).uri().toString()).endsWith("/ipfs/pin/add/QmHash");
    }

    /**
     * contentId must NOT pin: it names the bytes during an attestation ceremony, and an abandoned
     * ceremony has to leave nothing durable behind. On Blockfrost an unpinned add expires by itself,
     * which is what removes the need for any compensating unpin.
     */
    @Test
    @SuppressWarnings("unchecked")
    void contentId_addsWithoutPinning() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"name\":\"reeve.json\",\"ipfs_hash\":\"QmHash\",\"size\":\"50\"}");

        List<HttpRequest> requests = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return httpResponse;
                });

        assertThat(publisher.contentId("some content").get()).isEqualTo("QmHash");

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).uri().toString()).endsWith("/ipfs/add");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_non2xxError_surfacesBlockfrostErrorBody() throws IOException, InterruptedException {
        // Blockfrost's error envelope carries an "error" field that the success DTO cannot map; the publisher must
        // surface that body (with the real "message") rather than let it crash the success parser.
        String errorBody = "{\"status_code\":403,\"error\":\"Forbidden\",\"message\":\"Invalid project token.\"}";
        when(httpResponse.statusCode()).thenReturn(403);
        when(httpResponse.body()).thenReturn(errorBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Blockfrost IPFS upload rejected");
        assertThat(problem.getDetail()).contains("403").contains("Invalid project token.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_successStatusWithoutHash_returnsLeftInsteadOfNullCid() throws IOException, InterruptedException {
        // A 2xx body lacking ipfs_hash must not be reported as a successful publish with a null CID.
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"name\":\"reeve.json\",\"size\":\"0\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("Blockfrost IPFS response missing ipfs_hash");
    }
}
