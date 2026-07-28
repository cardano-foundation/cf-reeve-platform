package org.cardanofoundation.lob.app.blockchain_publisher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * What this module's composition root still owns for documents, now that the DOCUMENT attestation path
 * lives in document_vault.
 *
 * <p>Supersedes {@code ModuleFlagCombinationsTest}, which exercised a 2x2 matrix of
 * {@code lob.keri-attestation.enabled} x {@code lob.document_vault.enabled} over four beans this
 * module declared: the attestation target provider, the freeze guard, the freeze cleanup job and the
 * dispatch-time lookup. All four have moved or been deleted, so that matrix no longer describes
 * anything here — and the combination it was really trying to protect (keri on, publisher off) is
 * covered where it now belongs, by {@code ApiPodDocumentAttestationContextTest} in document_vault.
 *
 * <p>What survives is the reserved-label guard, which matters more than it looks: it is
 * unconditional, so a deployment that misconfigures the document metadata label to 170 fails at
 * startup in EVERY shape rather than silently colliding with the CIP-170 attestation map at publish
 * time.
 */
class PublisherPodModuleShapeTest {

    private final TransactionSubmissionConfig config = new TransactionSubmissionConfig();

    private DocumentConverter documentConverter() {
        return new DocumentConverter();
    }

    private void buildDocumentCreator(int metadataLabel) {
        config.documentL1TransactionCreator(
                mock(BackendService.class),
                documentConverter(),
                mock(DocumentIpfsSerialiser.class),
                mock(DocumentMetadataSerialiser.class),
                mock(BlockchainReaderPublicApiIF.class),
                mock(MetadataChecker.class),
                mock(OrganisationPublicApi.class),
                mock(Account.class),
                Optional.of(mock(IpfsPublisher.class)),
                mock(Cip170MetadataFactory.class),
                metadataLabel,
                false);
    }

    @Test
    void aDocumentMetadataLabelOf170IsRejectedAtStartup() {
        assertThatThrownBy(() -> buildDocumentCreator(170))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void theDefaultDocumentMetadataLabelIsAccepted() {
        assertThat(catchNothing(() -> buildDocumentCreator(1447))).isTrue();
    }

    private static boolean catchNothing(Runnable r) {
        r.run();

        return true;
    }
}
