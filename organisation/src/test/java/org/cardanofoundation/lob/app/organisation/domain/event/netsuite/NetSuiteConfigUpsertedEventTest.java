package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

class NetSuiteConfigUpsertedEventTest {

    @Test
    void doesNotLeakTheEncryptedKeyInToString() {
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId("org-1")
                .revision(3L)
                .baseUrl("https://example.restlets.api.netsuite.com")
                .tokenUrl("https://example.suitetalk.api.netsuite.com/token")
                .clientId("client-1")
                .certificateId("cert-1")
                .privateKeyEncrypted("v1:SUPERSECRETENVELOPE")
                .build();

        assertThat(event.toString())
                .doesNotContain("SUPERSECRETENVELOPE")
                .contains("org-1");
    }

    @Test
    void carriesANullKeyToMeanReuseTheStoredOne() {
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId("org-1")
                .revision(4L)
                .baseUrl("https://base")
                .tokenUrl("https://token")
                .clientId("client-1")
                .certificateId("cert-1")
                .build();

        assertThat(event.getPrivateKeyEncrypted()).isNull();
    }

}
