package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

@ExtendWith(MockitoExtension.class)
class NetSuiteClientRegistryTest {

    private static final String ORG = "org-1";

    @Mock
    private NetSuiteConfigRepository repository;
    @Mock
    private SecretCipher secretCipher;

    private NetSuiteClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NetSuiteClientRegistry(repository, secretCipher, new ObjectMapper(),
                RestClient.create(), 100);
    }

    private NetSuiteConfigEntity config() {
        return NetSuiteConfigEntity.builder()
                .organisationId(ORG)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted("v1:ENVELOPE")
                .revision(1L)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();
    }

    @Test
    void returnsAProblemWhenTheOrganisationHasNoConfiguration() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED.value());
    }

    @Test
    void buildsAClientFromTheDecryptedConfiguration() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getBaseUrl()).isEqualTo("https://base");
    }

    @Test
    void cachesTheClientSoRepeatedLookupsDoNotHitTheDatabase() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        NetSuiteClient first = registry.forOrganisation(ORG).get();
        NetSuiteClient second = registry.forOrganisation(ORG).get();

        assertThat(first).isSameAs(second);
        verify(repository, times(1)).findById(ORG);
    }

    @Test
    void keepsTenantsIsolatedWithSeparateClients() {
        NetSuiteConfigEntity other = NetSuiteConfigEntity.builder()
                .organisationId("org-2")
                .baseUrl("https://other").tokenUrl("https://othertoken")
                .clientId("otherclient").certificateId("othercert")
                .privateKeyEncrypted("v1:OTHER")
                .revision(1L)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();

        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(repository.findById("org-2")).thenReturn(Optional.of(other));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");
        when(secretCipher.decrypt("v1:OTHER")).thenReturn("OTHERPEM");

        NetSuiteClient first = registry.forOrganisation(ORG).get();
        NetSuiteClient second = registry.forOrganisation("org-2").get();

        assertThat(first).isNotSameAs(second);
        assertThat(first.getBaseUrl()).isEqualTo("https://base");
        assertThat(second.getBaseUrl()).isEqualTo("https://other");
    }

    @Test
    void rebuildsTheClientAfterEviction() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        NetSuiteClient first = registry.forOrganisation(ORG).get();
        registry.evict(ORG);
        NetSuiteClient second = registry.forOrganisation(ORG).get();

        assertThat(first).isNotSameAs(second);
        verify(repository, times(2)).findById(ORG);
    }

    @Test
    void surfacesADecryptionFailureAsAProblemRatherThanThrowing() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenThrow(new IllegalStateException("wrong key"));

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_UNREADABLE");
    }

    @Test
    void doesNotCacheAClientBuiltAgainstConfigurationThatWasEvictedMidBuild() {
        // Simulate an eviction landing while the client is being built: the decrypt call is the
        // slow step, so evict there. The caller still gets a usable client for the work already
        // in flight, but it must not be pinned into the cache over the newer configuration.
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenAnswer(invocation -> {
            registry.evict(ORG);
            return "PEM";
        });

        NetSuiteClient first = registry.forOrganisation(ORG).get();

        assertThat(first).isNotNull();

        // A subsequent lookup must rebuild rather than serve the stale client.
        reset(secretCipher);
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");
        NetSuiteClient second = registry.forOrganisation(ORG).get();

        assertThat(second).isNotSameAs(first);
    }

}
