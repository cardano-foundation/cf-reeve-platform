package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridDecryptor;
import org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretDecryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretEncryptor;

/**
 * Proves both tiers agree on the AAD context with REAL ciphers — something the mocked service
 * tests cannot show. This mirrors the call sites' data flow rather than invoking them: the admin
 * service encrypts with the request-path organisationId (carried onto the
 * {@link NetSuiteConfigUpsertedEvent}), the netsuite module stores the envelope under the event's
 * organisationId, and {@link NetSuiteClientRegistry#forOrganisation} decrypts with that same id
 * (its method parameter, which is also the entity's primary key). That the mocked tests pass the
 * same ids is separately enforced by Mockito's strict stubbing.
 */
class NetSuiteAadRoundtripTest {

    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQ==\n-----END PRIVATE KEY-----";

    @Test
    void envelopeEncryptedOnTheAdminPathDecryptsOnTheRegistryPath() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        SecretEncryptor encryptor = new RsaOaepHybridEncryptor(
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        SecretDecryptor decryptor = new RsaOaepHybridDecryptor(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));

        String organisationId = "75f95560c1d2de4b4ea0";

        // Admin path: the service encrypts with the organisationId and puts the result on the event.
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .organisationId(organisationId)
                .revision(1L)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted(encryptor.encrypt(PEM, organisationId))
                .build();

        // Netsuite path: NetSuiteConfigService stores the envelope keyed by the event's org id...
        NetSuiteConfigEntity stored = NetSuiteConfigEntity.builder()
                .organisationId(event.getOrganisationId())
                .baseUrl(event.getBaseUrl()).tokenUrl(event.getTokenUrl())
                .clientId(event.getClientId()).certificateId(event.getCertificateId())
                .privateKeyEncrypted(event.getPrivateKeyEncrypted())
                .revision(event.getRevision())
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();

        // ...and the registry decrypts with the entity's organisationId.
        assertThat(decryptor.decrypt(stored.getPrivateKeyEncrypted(), stored.getOrganisationId()))
                .isEqualTo(PEM);

        // The binding is real: any other organisation id fails the integrity check.
        assertThatThrownBy(() -> decryptor.decrypt(stored.getPrivateKeyEncrypted(), "other-org"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context mismatch");
    }

}
