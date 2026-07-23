package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
 * Tests {@link KeriAuthBeginService#submitAuthBegin}: both the external-verification path and the
 * own-chain submission path complete (or fail) the ceremony synchronously in this one call — the
 * own-chain path no longer dispatches a background confirmation poll ({@code awaitAuthBeginConfirmation}
 * / {@code CeremonyAsyncRunner} are gone); it fires the AUTH_BEGIN tx and completes the step the moment
 * the submitter returns a tx hash, without waiting for block confirmations.
 */
@ExtendWith(MockitoExtension.class)
class KeriAuthBeginServiceTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String WALLET_AID = "EWALLETAID00000000000000000000000000000";
    private static final String CREDENTIAL_SAID = "ECREDSAID000000000000000000000000000000";
    private static final String SCHEMA_SAID = "ESCHEMA00000000000000000000000000000000";
    private static final String OTHER_SCHEMA_SAID = "EOTHERSCHEMA0000000000000000000000000000";
    private static final String TX_HASH = "a".repeat(64);

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Credentials credentials;
    @Mock
    private CesrChainReducer cesrChainReducer;
    @Mock
    private Cip170MetadataFactory metadataFactory;
    @Mock
    private CardanoMetadataTxSubmitter submitter;
    @Mock
    private ObjectProvider<CardanoMetadataTxSubmitter> submitterProvider;
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
        // Present by default (F9 fix) — the specific "submitter unavailable" tests override this to null.
        lenient().when(submitterProvider.getIfAvailable()).thenReturn(submitter);
        // Own-chain path validates the fetched chain before publishing (reusable-attestation design
        // rev) — accepted by default; the specific rejection test below overrides this to Left.
        lenient().when(chainValidator.validate(any(), any(), any(), any()))
                .thenReturn(Either.right(new CredentialChainValidator.ValidatedCredential(CREDENTIAL_SAID, SCHEMA_SAID)));
        service = new KeriAuthBeginService(keriClient, cesrChainReducer, metadataFactory, submitterProvider,
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

    // ==================== submitAuthBegin: guards ====================

    @Test
    void submitAuthBeginBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, submitter);
    }

    @Test
    void submitAuthBeginWithNoIdentityLinkFailsStepButStillReturnsRight() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.AUTH_BEGIN_SUBMITTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User user-1 has no linked identity to submit AUTH_BEGIN for.");
        verifyNoInteractions(submitter);
    }

    // ==================== submitAuthBegin: external verification ====================

    @Test
    void submitAuthBeginExternalVerifiedPersistsLinkAndCompletesStepSynchronously() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        KeriIdentityLinkEntity link = linkedWithCredential();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(link));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID, "block", 12345L)));
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.AUTH_BEGIN_CONFIRMED, result.get().state());
        verify(ceremonyService, never()).failStep(any(), anyInt(),
                any(), any(), any());

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutatorCaptor.capture());
        verify(identityLinkRepository, never()).save(any());

        mutatorCaptor.getValue().accept(ceremony());

        assertEquals(TX_HASH, link.getAuthBeginTxHash());
        assertEquals(12345L, link.getAuthBeginBlock());
        assertTrue(link.getAuthBeginAt() != null);
        verify(identityLinkRepository).save(link);
    }

    @Test
    void submitAuthBeginExternalVerifiedButIdentityRelinkedMidFlightSkipsTheLinkWrite() {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setBindingVersion(1);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremonyEntity));
        KeriIdentityLinkEntity initialLink = linkedWithCredential();
        initialLink.setBindingVersion(1);
        KeriIdentityLinkEntity relinkedLink = linkedWithCredential();
        relinkedLink.setBindingVersion(2);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(initialLink));
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinkedLink));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID)));
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutatorCaptor.capture());

        mutatorCaptor.getValue().accept(ceremonyEntity);

        assertNull(relinkedLink.getAuthBeginTxHash());
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void submitAuthBeginExternalVerifiedButCompleteStepThrowsFailsWithAuthBeginUnverifiedInsteadOfPropagating() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenThrow(new RuntimeException("db down"));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
    }

    @Test
    void submitAuthBeginExternalNoMetadataFoundFailsWithAuthBeginUnverified() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(Optional.empty());
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void submitAuthBeginExternalWrongTypeFailsWithAuthBeginUnverified() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH))
                .thenReturn(Optional.of(Map.of("t", "ATTEST", "i", WALLET_AID, "s", SCHEMA_SAID)));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
    }

    @Test
    void submitAuthBeginExternalWrongIssuerAidFailsWithAuthBeginUnverified() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH))
                .thenReturn(Optional.of(Map.of("t", "AUTH_BEGIN", "i", "ESOMEONEELSE", "s", SCHEMA_SAID)));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
    }

    @Test
    void submitAuthBeginExternalDisallowedSchemaFailsWithAuthBeginUnverified() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH))
                .thenReturn(Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", OTHER_SCHEMA_SAID)));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
    }

    @Test
    void submitAuthBeginExternalReadThrowingFailsWithAuthBeginUnverifiedInsteadOfPropagating() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH)).thenThrow(new RuntimeException("blockfrost down"));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
    }

    @Test
    void submitAuthBeginExternalWithNoSubmitterAvailableFailsWithAuthBeginUnverified() {
        // F9 fix: module enabled without blockchain_publisher -> no CardanoMetadataTxSubmitter bean.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitterProvider.getIfAvailable()).thenReturn(null);
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
        verifyNoInteractions(submitter);
    }

    // ==================== submitAuthBegin: own submission (fire-and-complete) ====================

    @Test
    void submitAuthBeginOwnSubmissionHappyPathSubmitsTxAndCompletesTheStepSynchronouslyWithoutWaitingForConfirmations() throws Exception {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremonyEntity));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        byte[] reduced = "reduced".getBytes();
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenReturn(reduced);
        MetadataMap map = mock(MetadataMap.class);
        when(metadataFactory.authBeginMap(WALLET_AID, SCHEMA_SAID, reduced, null, List.of(1447L))).thenReturn(map);
        when(submitter.submitMetadataTransaction(170L, map)).thenReturn(Either.right(TX_HASH));
        KeriIdentityLinkEntity link = linkedWithCredential();
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(link));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(4);
                    mutator.accept(ceremonyEntity);
                    return true;
                });
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.AUTH_BEGIN_CONFIRMED, result.get().state());
        // No confirmation poll -- submitter.confirmations is never even a dependency any more (the
        // CardanoMetadataTxSubmitter mock here only stubs submitMetadataTransaction /
        // readCip170Metadata). completeStep is called directly off the tx-hash return, in this same call.
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any());
        verify(ceremonyService, never()).failStep(any(), anyInt(),
                any(), any(), any());
        assertEquals(TX_HASH, link.getAuthBeginTxHash());
        verify(identityLinkRepository).save(link);
    }

    @Test
    void submitAuthBeginOwnSubmissionStaleCompleteStepSkipsFailingTheStepAndReturnsCurrentView() throws Exception {
        // A concurrent retry/sweep transition beat this attempt's completeStep CAS — the tx is already
        // submitted on-chain, but this attempt is no longer the current one; report whatever the
        // winning attempt's own state ended up being rather than clobbering it via failStep.
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremonyEntity));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        byte[] reduced = "reduced".getBytes();
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenReturn(reduced);
        MetadataMap map = mock(MetadataMap.class);
        when(metadataFactory.authBeginMap(WALLET_AID, SCHEMA_SAID, reduced, null, List.of(1447L))).thenReturn(map);
        when(submitter.submitMetadataTransaction(170L, map)).thenReturn(Either.right(TX_HASH));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenReturn(false);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_REQUESTED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService, never()).failStep(any(), anyInt(),
                any(), any(), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionWithNoValidatedCredentialFailsWithIdentityNotLinked() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(WALLET_AID);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.IDENTITY_NOT_LINKED), any());
        verifyNoInteractions(credentials);
    }

    @Test
    void submitAuthBeginOwnSubmissionCredentialNotFoundFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.empty());
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionWithAnInvalidChainFailsWithCredentialRejectedAndNeverSubmitsATx() throws Exception {
        // Reusable-attestation design rev: the own-chain path re-validates the fetched chain before
        // building/submitting the AUTH_BEGIN tx, same gate as credential-presentation.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        ProblemDetail rejection = KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED,
                "Credential chain is not structurally valid.");
        when(chainValidator.validate("FULL-CESR", WALLET_AID, List.of(SCHEMA_SAID), List.of()))
                .thenReturn(Either.left(rejection));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REJECTED), eq("Credential chain is not structurally valid."));
        verifyNoInteractions(cesrChainReducer, metadataFactory, submitter);
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionSubmitterRejectionFailsWithTheSubmittersProblem() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        byte[] reduced = "reduced".getBytes();
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenReturn(reduced);
        MetadataMap map = mock(MetadataMap.class);
        when(metadataFactory.authBeginMap(WALLET_AID, SCHEMA_SAID, reduced, null, List.of(1447L))).thenReturn(map);
        ProblemDetail submitProblem = KeriAttestationProblems.unprocessable("TX_BUILD_FAILED", "insufficient funds");
        when(submitter.submitMetadataTransaction(170L, map)).thenReturn(Either.left(submitProblem));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.AUTH_BEGIN_SUBMITTED,
                submitProblem.getTitle(), submitProblem.getDetail());
    }

    @Test
    void submitAuthBeginOwnSubmissionReducerThrowingFailsInsteadOfPropagating() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenThrow(new RuntimeException("malformed CESR"));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionWithNoSubmitterAvailableFailsWithAuthBeginSubmissionUnavailable() {
        // F9 fix: module enabled without blockchain_publisher -> no CardanoMetadataTxSubmitter bean. The
        // credential-presence guard already passed, so this is specifically about the submitter itself.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitterProvider.getIfAvailable()).thenReturn(null);
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_SUBMISSION_UNAVAILABLE), any());
        verifyNoInteractions(credentials, cesrChainReducer, submitter);
    }

    @Test
    void submitAuthBeginOwnSubmissionCompleteStepThrowsFailsWithAuthBeginRolledBackInsteadOfPropagating() throws Exception {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremonyEntity));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        byte[] reduced = "reduced".getBytes();
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenReturn(reduced);
        MetadataMap map = mock(MetadataMap.class);
        when(metadataFactory.authBeginMap(WALLET_AID, SCHEMA_SAID, reduced, null, List.of(1447L))).thenReturn(map);
        when(submitter.submitMetadataTransaction(170L, map)).thenReturn(Either.right(TX_HASH));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenThrow(new RuntimeException("db down"));
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.FAILED)));

        Either<ProblemDetail, CeremonyView> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
    }
}
