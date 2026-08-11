package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.util.Optional;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * Pins the one distinction this fetcher exists to make: "the agent says it does not have it" (404,
 * empty — a genuine, permanent outcome the callers report as a failed step) versus "the agent is
 * broken" (anything else, thrown — which the callers report as a fetch failure instead).
 *
 * <p>Collapsing the two is what the class originally did, and it turns a KERIA outage into the
 * permanent "credential was not found in the store", failing a ceremony that would have succeeded on
 * retry. That distinction is invisible in a compile and has no other test, hence this one.
 */
@ExtendWith(MockitoExtension.class)
class CredentialCesrFetcherTest {

    private static final String CREDENTIAL_SAID = "ECREDSAID000000000000000000000000000000";

    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private SignifyClient client;

    private CredentialCesrFetcher fetcher;

    @BeforeEach
    void setUp() {
        when(keriClient.client()).thenReturn(client);
        fetcher = new CredentialCesrFetcher(keriClient);
    }

    @SuppressWarnings("unchecked")
    private void stubFetch(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        if (status == 200) {
            when(response.body()).thenReturn(body);
        }
        when(client.fetch(eq("/credentials/" + CREDENTIAL_SAID), eq("GET"), any(), any())).thenReturn(response);
    }

    @Test
    void returnsTheRawCesrBodyOn200() throws Exception {
        stubFetch(200, "FULL-CESR-STREAM");

        assertEquals(Optional.of("FULL-CESR-STREAM"), fetcher.fetch(CREDENTIAL_SAID));
    }

    @Test
    void asksForTheCesrStreamRatherThanTheStructuredCredential() throws Exception {
        // The whole point of going at the endpoint instead of credentials().get(): the structured
        // credential has already lost the vcp/iss events and signatures the chain validator needs.
        stubFetch(200, "FULL-CESR-STREAM");

        fetcher.fetch(CREDENTIAL_SAID);

        org.mockito.Mockito.verify(client).fetch(eq("/credentials/" + CREDENTIAL_SAID), eq("GET"), eq(null),
                eq(java.util.Map.of("Accept", "application/json+cesr")));
    }

    @Test
    void returnsEmptyWhenTheAgentAnswersThatItDoesNotHoldTheCredential() throws Exception {
        stubFetch(404, null);

        assertTrue(fetcher.fetch(CREDENTIAL_SAID).isEmpty());
    }

    @Test
    void throwsOnAServerErrorRatherThanReportingTheCredentialAsMissing() throws Exception {
        // The regression this guards: a 503 that reads as empty becomes "not found in the store", which
        // fails the step for good — for an outage that a retry would have ridden out.
        stubFetch(503, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> fetcher.fetch(CREDENTIAL_SAID));

        assertTrue(thrown.getMessage().contains("503"));
    }

    @Test
    void throwsOnAnUnexpectedSuccessfulStatusToo() throws Exception {
        // Not 200 and not 404 — the agent did something this code has no contract for, and guessing
        // "missing" would be the same mistake in a quieter form.
        stubFetch(204, null);

        assertThrows(IllegalStateException.class, () -> fetcher.fetch(CREDENTIAL_SAID));
    }
}
