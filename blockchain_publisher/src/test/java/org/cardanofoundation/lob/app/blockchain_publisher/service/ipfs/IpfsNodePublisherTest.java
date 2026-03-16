package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.impl.IpfsNodePublisher;

@ExtendWith(MockitoExtension.class)
class IpfsNodePublisherTest {

    @Mock
    private IPFS ipfs;

    private IpfsNodePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new IpfsNodePublisher();
        ReflectionTestUtils.setField(publisher, "ipfs", ipfs);
    }

    @Test
    void publish_success_returnsCid() throws IOException {
        MerkleNode node = new MerkleNode("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
        when(ipfs.add(any(NamedStreamable.class))).thenReturn(List.of(node));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("QmVEhJneSa8WNKxSSJgyahfvvMqkS7p6E22fUXsPoGFvH5");
    }

    @Test
    void publish_ioException_returnsLeftWithProblemDetail() throws IOException {
        when(ipfs.add(any(NamedStreamable.class))).thenThrow(new IOException("Connection lost"));

        Either<ProblemDetail, String> result = publisher.publish("{\"test\":\"content\"}");

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("Error while saving to IPFS");
        assertThat(problem.getDetail()).contains("Connection lost");
    }

    @Test
    void init_withValidHostPort_throwsRuntimeExceptionWhenNodeUnavailable() {
        IpfsNodePublisher localPublisher = new IpfsNodePublisher();
        ReflectionTestUtils.setField(localPublisher, "node", "192.0.2.0:5001");

        assertThatThrownBy(localPublisher::init)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("192.0.2.0")
                .hasMessageContaining("5001");
    }

    @Test
    void init_withHostOnly_usesDefaultPort5001() {
        IpfsNodePublisher localPublisher = new IpfsNodePublisher();
        ReflectionTestUtils.setField(localPublisher, "node", "192.0.2.0");

        assertThatThrownBy(localPublisher::init)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("192.0.2.0")
                .hasMessageContaining("5001");
    }
}
