package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import id.veridian.signify.app.Contacting;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.app.coring.Oobis;
import id.veridian.signify.app.coring.Operations;
import id.veridian.signify.generated.keria.model.Contact;
import id.veridian.signify.generated.keria.model.FailedOOBIOperation;
import id.veridian.signify.generated.keria.model.Operation;
import id.veridian.signify.generated.keria.model.OperationStatus;
import id.veridian.signify.generated.keria.model.PendingOOBIOperation;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * A wallet-OOBI resolve that KERIA completes as a {@code FailedOperation} is an unresolvable OOBI (422),
 * not an unavailable agent: the agent answered, the OOBI did not check out.
 */
@ExtendWith(MockitoExtension.class)
class KeriOobiServiceFailedOperationTest {

    private static final String USER = "user-1";
    private static final String VALID_OOBI = "https://witness.example.org/oobi/EAID12345/agent/EAGENT6789";

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Oobis oobis;
    @Mock
    private Operations operations;
    @Mock
    private Contacting.Contacts contacts;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private KeriOobiService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.oobis()).thenReturn(oobis);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(client.contacts()).thenReturn(contacts);
        lenient().when(oobis.resolve(anyString(), anyString())).thenReturn(new PendingOOBIOperation());
        // Were the failed operation mistaken for success, the contact lookup would pass and the link
        // would be written — so a stale contact is deliberately present.
        lenient().when(contacts.get(anyString())).thenReturn(Optional.of(new Contact()));

        service = new KeriOobiService(keriClient, identityLinkRepository, ceremonyRepository, eventPublisher);
    }

    @Test
    void failedResolveIs422() throws Exception {
        FailedOOBIOperation failed = new FailedOOBIOperation()
                .name("oobi.EAID12345")
                .error(new OperationStatus().code(404).message("unable to resolve OOBI"));
        when(operations.wait(any(Operation.class), any(Operations.WaitOptions.class))).thenReturn(failed);

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), result.getLeft().getStatus());
        assertTrue(result.getLeft().getDetail().contains("unable to resolve OOBI"), result.getLeft().getDetail());
        verifyNoInteractions(identityLinkRepository);
    }
}
