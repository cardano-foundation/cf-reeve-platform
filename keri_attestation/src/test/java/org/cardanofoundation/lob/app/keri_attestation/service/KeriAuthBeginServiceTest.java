package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.AuthBeginPublishCommand;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.RequiredSteps;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.credentialing.credentials.Credentials;

/**
 * Tests {@link KeriAuthBeginService#submitAuthBegin}. This module owns no Cardano wallet: the
 * own-chain path validates the credential chain and hands an {@link AuthBeginPublishCommand} to
 * blockchain_publisher, leaving the ceremony in {@code AUTH_BEGIN_SUBMITTED} for
 * {@code AuthBeginLedgerUpdateHandler} to complete. Nothing here submits a transaction.
 */
@ExtendWith(MockitoExtension.class)
class KeriAuthBeginServiceTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final String ORGANISATION_ID = "org-1";
    private static final int GENERATION = 2;
    private static final String WALLET_AID = "EWALLETAID00000000000000000000000000000";
    private static final String CREDENTIAL_SAID = "ECREDSAID000000000000000000000000000000";
    private static final String SCHEMA_SAID = "ESCHEMA00000000000000000000000000000000";
    private static final byte[] REDUCED_CHAIN = new byte[] { 1, 2, 3, 4 };

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Credentials credentials;
    @Mock
    private CesrChainReducer cesrChainReducer;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private CredentialChainValidator chainValidator;

    private KeriAuthBeginService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.credentials()).thenReturn(credentials);
        lenient().when(chainValidator.validate(any(), any(), any(), any()))
                .thenReturn(Either.right(new CredentialChainValidator.ValidatedCredential(CREDENTIAL_SAID, SCHEMA_SAID)));
        service = new KeriAuthBeginService(keriClient, cesrChainReducer, eventPublisher,
                ceremonyService, identityLinkRepository, properties(), chainValidator);
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier",
                new KeriAttestationProperties.CredentialPolicy(List.of(SCHEMA_SAID), List.of(), null),
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT0.01S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT0.01S"), Duration.parse("PT0.05S"), Duration.parse("PT0.01S"),
                Duration.parse("PT0.01S"), Duration.parse("PT2M"), null);
    }

    private static KeriAttestationCeremonyEntity ceremony() {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setOrganisationId(ORGANISATION_ID);
        ceremony.setState(CeremonyState.AUTH_BEGIN_SUBMITTED);
        ceremony.setAttemptGeneration(GENERATION);
        return ceremony;
    }

    private static KeriIdentityLinkEntity linkedWithCredential() {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(WALLET_AID);
        link.setCredentialSaid(CREDENTIAL_SAID);
        link.setCredentialSchemaSaid(SCHEMA_SAID);
        return link;
    }

    private static CeremonyView ceremonyView(CeremonyState state) {
        return new CeremonyView(CEREMONY_ID, state, new RequiredSteps(false, false, false), null, null, null, null,
                null, null);
    }

    private void givenStepBegun() {
        when(ceremonyService.beginStep(eq(CEREMONY_ID), eq(USER_ID), eq(CeremonyState.CREDENTIAL_RECEIVED),
                eq(CeremonyState.AUTH_BEGIN_SUBMITTED), eq(false))).thenReturn(Either.right(ceremony()));
    }

    // ==================== guards ====================

    @Test
    void beginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.CONFLICT, "nope");
        when(ceremonyService.beginStep(any(), any(), any(), any(), eq(false))).thenReturn(Either.left(problem));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isLeft());
        verifyNoInteractions(eventPublisher, identityLinkRepository);
    }

    @Test
    void noIdentityLinkFailsStepButStillReturnsRight() {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.IDENTITY_NOT_LINKED), any());
        verifyNoInteractions(eventPublisher);
    }

    /** A ceremony with no organisation could never be dispatched, so it fails rather than queueing. */
    @Test
    void ceremonyWithoutOrganisationFailsWithoutEmittingACommand() {
        KeriAttestationCeremonyEntity orgless = ceremony();
        orgless.setOrganisationId(null);
        when(ceremonyService.beginStep(any(), any(), any(), any(), eq(false))).thenReturn(Either.right(orgless));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_SUBMISSION_UNAVAILABLE), any());
        verifyNoInteractions(eventPublisher);
    }

    // ==================== assumed-published escape hatch ====================

    @Test
    void assumePublishedCompletesTheStepWithoutTouchingTheChain() {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, true, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.AUTH_BEGIN_CONFIRMED, result.get().state());
        verifyNoInteractions(eventPublisher);
    }

    // ==================== own publication, handed to blockchain_publisher ====================

    @Test
    void ownPublicationEmitsCommandAndLeavesTheCeremonyWaiting() throws Exception {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("full-cesr"));
        when(cesrChainReducer.reduceToVcpIssAcdc("full-cesr")).thenReturn(REDUCED_CHAIN);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_SUBMITTED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        // The step is NOT completed here — the publisher's ledger update does that.
        assertEquals(CeremonyState.AUTH_BEGIN_SUBMITTED, result.get().state());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());

        ArgumentCaptor<AuthBeginPublishCommand> command = ArgumentCaptor.forClass(AuthBeginPublishCommand.class);
        verify(eventPublisher).publishEvent(command.capture());
        assertEquals(CEREMONY_ID, command.getValue().ceremonyId());
        assertEquals(ORGANISATION_ID, command.getValue().organisationId());
        assertEquals(WALLET_AID, command.getValue().aid());
        assertEquals(SCHEMA_SAID, command.getValue().leafSchemaSaid());
        assertArrayEquals(REDUCED_CHAIN, command.getValue().reducedCesrChain());
        assertEquals(List.of(1447L), command.getValue().authorizedLabels());
    }

    @Test
    void missingCredentialFailsWithoutEmittingACommand() throws Exception {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.empty());
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(eventPublisher);
    }

    /** The chain is re-validated immediately before it would be published, not just at presentation. */
    @Test
    void rejectedCredentialChainFailsWithoutEmittingACommand() throws Exception {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("full-cesr"));
        when(chainValidator.validate(any(), any(), any(), any())).thenReturn(Either.left(
                ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "revoked")));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void credentialStoreFailureFailsWithoutPropagating() throws Exception {
        givenStepBegun();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenThrow(new RuntimeException("keria down"));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, false, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(eventPublisher);
    }
}
