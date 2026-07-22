package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.credentialing.credentials.Credentials;

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
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private CeremonyAsyncRunner asyncRunner;

    private KeriAuthBeginService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.credentials()).thenReturn(credentials);
        // Present by default (F9 fix) — the specific "submitter unavailable" tests override this to null.
        lenient().when(submitterProvider.getIfAvailable()).thenReturn(submitter);
        service = new KeriAuthBeginService(keriClient, cesrChainReducer, metadataFactory, submitterProvider,
                ceremonyService, ceremonyRepository, identityLinkRepository, properties(), asyncRunner);
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

    // ==================== submitAuthBegin: guards ====================

    @Test
    void submitAuthBeginBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, submitter, asyncRunner);
    }

    @Test
    void submitAuthBeginWithNoIdentityLinkFailsStepButStillReturnsRight() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.AUTH_BEGIN_SUBMITTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User user-1 has no linked identity to submit AUTH_BEGIN for.");
        verifyNoInteractions(submitter, asyncRunner);
    }

    // ==================== submitAuthBegin: external verification ====================

    @Test
    void submitAuthBeginExternalVerifiedPersistsLinkAndCompletesStep() {
        // The link write happens inside completeStep's mutator (only runs once the ceremony's own CAS
        // has confirmed this attempt is current) — the guard at the top of submitAuthBegin uses the
        // plain findById; the fresh re-fetch inside the mutator uses the row-locked
        // findByUserIdForUpdate (F3 fix) — a different mocked method, stubbed separately.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        KeriIdentityLinkEntity link = linkedWithCredential();
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(link));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID, "block", 12345L)));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
        verifyNoInteractions(asyncRunner);

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
        // The freshly re-fetched link (as the mutator would see it, post-relink) carries a different
        // bindingVersion than the ceremony's own — the write must be skipped, not silently reattach
        // AUTH_BEGIN authority data to what is now a different identity.
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setBindingVersion(1);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremonyEntity));
        KeriIdentityLinkEntity initialLink = linkedWithCredential();
        initialLink.setBindingVersion(1);
        KeriIdentityLinkEntity relinkedLink = linkedWithCredential();
        relinkedLink.setBindingVersion(2);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(initialLink));
        // F3 fix: the mutator's re-fetch is row-locked, a different mocked method.
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinkedLink));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID)));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutatorCaptor.capture());

        mutatorCaptor.getValue().accept(ceremonyEntity);

        assertTrue(relinkedLink.getAuthBeginTxHash() == null);
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void submitAuthBeginExternalVerifiedButCompleteStepThrowsFailsWithAuthBeginUnverifiedInsteadOfPropagating() {
        // completeStep's mutator does real JPA work (findById + save) that can throw — unlike every
        // other external-boundary call in this class, completeStep itself has no checked-exception
        // contract to remind us of that. An escaped exception here must not propagate out of
        // submitAuthBegin as an unhandled failure; it must still resolve the ceremony via failStep.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitter.readCip170Metadata(TX_HASH)).thenReturn(
                Optional.of(Map.of("t", "AUTH_BEGIN", "i", WALLET_AID, "s", SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenThrow(new RuntimeException("db down"));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, TX_HASH, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED), any());
        verifyNoInteractions(submitter);
    }

    // ==================== submitAuthBegin: own submission ====================

    /** Stubs {@code ceremonyService.updateWaitingStepData} (F2 fix) to apply the mutator to
     *  {@code ceremony} and report success, mirroring the real guarded update when the row lock still
     *  matches. */
    private void stubGuardedUpdateSuccess(KeriAttestationCeremonyEntity ceremony) {
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.AUTH_BEGIN_SUBMITTED), any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(3);
                    mutator.accept(ceremony);
                    return true;
                });
    }

    @Test
    void submitAuthBeginOwnSubmissionHappyPathPersistsTxHashAndDispatchesConfirmationWait() throws Exception {
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
        stubGuardedUpdateSuccess(ceremonyEntity);

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        assertEquals(TX_HASH, ceremonyEntity.getAuthBeginTxHash());
        verify(ceremonyService).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.AUTH_BEGIN_SUBMITTED), any());
        verify(ceremonyRepository, never()).save(any());
        verify(asyncRunner).awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionStaleGuardedUpdateSkipsDispatchWithoutFailingTheStep() throws Exception {
        // F2 fix: a concurrent retry/sweep transition beat this attempt's tx-hash write — the
        // confirmation wait must never be dispatched for a step that has already moved on, and per this
        // class's own return-value convention (see class javadoc) submitAuthBegin still reports right()
        // since the request itself was accepted and processed.
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
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.AUTH_BEGIN_SUBMITTED), any())).thenReturn(false);

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verifyNoInteractions(asyncRunner);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void submitAuthBeginOwnSubmissionWithNoValidatedCredentialFailsWithIdentityNotLinked() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(WALLET_AID);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.IDENTITY_NOT_LINKED), any());
        verifyNoInteractions(credentials, asyncRunner);
    }

    @Test
    void submitAuthBeginOwnSubmissionCredentialNotFoundFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
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

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.AUTH_BEGIN_SUBMITTED,
                submitProblem.getTitle(), submitProblem.getDetail());
        verifyNoInteractions(asyncRunner);
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void submitAuthBeginOwnSubmissionReducerThrowingFailsInsteadOfPropagating() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR"));
        when(cesrChainReducer.reduceToVcpIssAcdc("FULL-CESR")).thenThrow(new RuntimeException("malformed CESR"));

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void submitAuthBeginOwnSubmissionWithNoSubmitterAvailableFailsWithAuthBeginSubmissionUnavailable() {
        // F9 fix: module enabled without blockchain_publisher -> no CardanoMetadataTxSubmitter bean. The
        // credential-presence guard already passed, so this is specifically about the submitter itself.
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.CREDENTIAL_RECEIVED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, false)).thenReturn(Either.right(ceremony()));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkedWithCredential()));
        when(submitterProvider.getIfAvailable()).thenReturn(null);

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_SUBMISSION_UNAVAILABLE), any());
        verifyNoInteractions(credentials, cesrChainReducer, asyncRunner, submitter);
    }

    // ==================== awaitAuthBeginConfirmation ====================

    @Test
    void awaitAuthBeginConfirmationCeremonyNotFoundNoOps() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.empty());

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verifyNoInteractions(submitter, identityLinkRepository, ceremonyService);
    }

    @Test
    void awaitAuthBeginConfirmationWithNoPendingTxHashFailsWithAuthBeginRolledBack() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony()));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.AUTH_BEGIN_SUBMITTED,
                KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                "No pending AUTH_BEGIN transaction hash was recorded to confirm.");
        verifyNoInteractions(submitter);
    }

    @Test
    void awaitAuthBeginConfirmationWithNoSubmitterAvailableFailsWithAuthBeginRolledBack() {
        // F9 fix: defensive — the submitter existed at submit time but is gone by the time this poll
        // runs (e.g. a redeploy removed blockchain_publisher mid-wait).
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitterProvider.getIfAvailable()).thenReturn(null);

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
        verifyNoInteractions(submitter);
    }

    @Test
    void awaitAuthBeginConfirmationReachesConfirmationsAndCompletes() {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitter.confirmations(TX_HASH)).thenReturn(Optional.of(3L));
        KeriIdentityLinkEntity link = linkedWithCredential();
        // awaitAuthBeginConfirmation itself never touches identityLinkRepository — only the mutator
        // (invoked manually below) does, via the row-locked findByUserIdForUpdate (F3 fix).
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(link));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremonyEntity);

        assertEquals(TX_HASH, link.getAuthBeginTxHash());
        verify(identityLinkRepository).save(link);
    }

    @Test
    void awaitAuthBeginConfirmationConfirmedButIdentityRelinkedMidFlightSkipsTheLinkWrite() {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        ceremonyEntity.setBindingVersion(1);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitter.confirmations(TX_HASH)).thenReturn(Optional.of(3L));
        KeriIdentityLinkEntity relinkedLink = linkedWithCredential();
        relinkedLink.setBindingVersion(2);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinkedLink));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremonyEntity);

        assertTrue(relinkedLink.getAuthBeginTxHash() == null);
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void awaitAuthBeginConfirmationCompleteStepThrowsFailsWithAuthBeginRolledBackInsteadOfPropagating() {
        // Same rationale as the external-verify path's equivalent test: this is an unsupervised async
        // worker — an escaped exception from completeStep's mutator must not propagate out of
        // awaitAuthBeginConfirmation, it must still resolve the ceremony via failStep.
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitter.confirmations(TX_HASH)).thenReturn(Optional.of(3L));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenThrow(new RuntimeException("db down"));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
    }

    @Test
    void awaitAuthBeginConfirmationTimesOutAfterRollbackWindowFailsWithAuthBeginRolledBack() {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitter.confirmations(TX_HASH)).thenReturn(Optional.of(0L));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void awaitAuthBeginConfirmationTreatsAConfirmationsCheckExceptionAsNotYetConfirmedAndKeepsPolling() {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony();
        ceremonyEntity.setAuthBeginTxHash(TX_HASH);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        when(submitter.confirmations(TX_HASH))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(Optional.of(3L));

        service.awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any());
    }

    // ==================== dispatch-failure resilience ====================

    @Test
    void submitAuthBeginOwnSubmissionDispatchFailureFailsWithAuthBeginRolledBackInsteadOfPropagating()
            throws Exception {
        // If CeremonyAsyncRunner's executor rejects the dispatch (pool/queue saturated), the tx is
        // already submitted on-chain — the ceremony must still land in a terminal, retryable state
        // rather than letting the rejection exception escape submitAuthBegin uncaught.
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
        stubGuardedUpdateSuccess(ceremonyEntity);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
                .when(asyncRunner).awaitAuthBeginConfirmation(CEREMONY_ID, GENERATION);

        Either<ProblemDetail, Void> result = service.submitAuthBegin(CEREMONY_ID, USER_ID, null, false);

        assertTrue(result.isRight());
        assertEquals(TX_HASH, ceremonyEntity.getAuthBeginTxHash());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
    }
}
