package org.cardanofoundation.lob.app.document_vault.config;

import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.document_vault.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.document_vault.service.DocumentAttestationFreezeGuard;
import org.cardanofoundation.lob.app.document_vault.service.DocumentAttestationTargetProvider;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Composition root for the DOCUMENT attestation path.
 *
 * <p>Gated on {@code lob.document_vault.enabled} (via {@code DocumentVaultModuleConfig}'s scan, which
 * is what puts this class in the context at all) AND {@code lob.keri-attestation.enabled} — and on
 * NOTHING else. That pairing is the entire point: these two flags are exactly what the split
 * deployment's {@code api} service sets, and the beans below must appear there even though
 * {@code blockchain_publisher} and {@code blockchain_reader} are both off.
 *
 * <p>The previous home for this wiring was {@code blockchain_publisher}'s
 * {@code TransactionSubmissionConfig}. Its bean method carried the same two conditions and they were
 * both satisfied on {@code api} — but the enclosing class sits in a package that is only scanned when
 * {@code lob.blockchain_publisher.enabled=true}, so the conditions were never evaluated and the
 * provider silently did not exist. Module gates compose by AND with whatever gates their contents
 * declare; a bean can never be more available than the configuration class that declares it.
 */
@Configuration
@ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
public class DocumentVaultAttestationConfig {

    @Bean
    public DocumentAttestationTargetProvider documentAttestationTargetProvider(
            VaultDocumentService vaultDocumentService,
            DocumentIpfsSerialiser documentIpfsSerialiser,
            DocumentMetadataSerialiser documentMetadataSerialiser,
            OrganisationPublicApiIF organisationPublicApi,
            // Optional: this tier only has an IPFS publisher if it is configured with one, and the
            // provider fails the ceremony cleanly when it is not, rather than the bean failing to exist.
            Optional<IpfsPublisher> ipfsPublisher,
            Cip170MetadataFactory cip170MetadataFactory,
            DocumentAttestationFreezeRepository freezeRepository,
            KeycloakSecurityHelper securityHelper,
            Clock clock,
            @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel) {
        return new DocumentAttestationTargetProvider(vaultDocumentService, documentIpfsSerialiser,
                documentMetadataSerialiser, organisationPublicApi, ipfsPublisher,
                cip170MetadataFactory, freezeRepository, securityHelper, clock, metadataLabel);
    }

    @Bean
    public DocumentAttestationFreezeGuard documentAttestationFreezeGuard(
            DocumentAttestationFreezeRepository freezeRepository,
            DocumentIpfsSerialiser documentIpfsSerialiser,
            KeriAttestationProperties keriAttestationProperties,
            Clock clock) {
        return new DocumentAttestationFreezeGuard(freezeRepository, documentIpfsSerialiser,
                keriAttestationProperties.freezeMaxAge(), clock);
    }
}
