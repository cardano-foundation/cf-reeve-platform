package org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ProblemDetail;

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


@ExtendWith(MockitoExtension.class)
class IpfsNodePublisherTest {

    @Mock
    private IPFS ipfs;

    private IpfsNodePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new IpfsNodePublisher(ipfs);
    }

    @Test
    void publish_success_returnsCid() throws IOException {
        MerkleNode node = new MerkleNode("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
        when(ipfs.add(any(NamedStreamable.class), eq(false), eq(false))).thenReturn(List.of(node));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
    }

    @Test
    void publish_ioException_returnsLeftWithProblemDetail() throws IOException {
        when(ipfs.add(any(NamedStreamable.class), eq(false), eq(false))).thenThrow(new IOException("Connection lost"));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Error while saving to IPFS");
        assertThat(problem.getDetail()).contains("Connection lost");
    }

    /**
     * The CID must come from the node's OWN only-hash mode, so it is produced by the same UnixFS
     * chunking and DAG layout a real add would use. Computing it any other way would let the attested
     * manifest and the published one drift apart, and only at dispatch.
     */
    @Test
    void contentId_usesOnlyHashSoNothingIsStored() throws IOException {
        MerkleNode node = new MerkleNode("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
        when(ipfs.add(any(NamedStreamable.class), eq(false), eq(true))).thenReturn(List.of(node));

        Either<ProblemDetail, String> result = publisher.contentId("{\"test\":\"content\"}");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
        // never the storing form — an abandoned ceremony must leave nothing behind
        verify(ipfs, never()).add(any(NamedStreamable.class), eq(false), eq(false));
    }

    @Test
    void contentIdAndPublishAgreeOnTheCidForTheSameBytes() throws IOException {
        MerkleNode node = new MerkleNode("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
        when(ipfs.add(any(NamedStreamable.class), eq(false), eq(true))).thenReturn(List.of(node));
        when(ipfs.add(any(NamedStreamable.class), eq(false), eq(false))).thenReturn(List.of(node));

        assertThat(publisher.contentId("{\"test\":\"content\"}").get())
                .isEqualTo(publisher.publish("{\"test\":\"content\"}").get());
    }

    @Test
    void connectingToAnUnreachableNodeFails() {
        assertThatThrownBy(() -> new IpfsNodePublisher("192.0.2.0:5001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("192.0.2.0")
                .hasMessageContaining("5001");
    }

    @Test
    void aHostWithoutAPortUsesTheDefault5001() {
        assertThatThrownBy(() -> new IpfsNodePublisher("192.0.2.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("192.0.2.0")
                .hasMessageContaining("5001");
    }
}
