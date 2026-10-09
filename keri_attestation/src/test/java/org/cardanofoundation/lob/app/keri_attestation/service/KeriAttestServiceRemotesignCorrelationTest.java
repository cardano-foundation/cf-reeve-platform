package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.http.ProblemDetail;

import id.veridian.signify.app.Exchanging;
import id.veridian.signify.app.Exchanging.ExchangeMessageResult;
import id.veridian.signify.app.aiding.IdentifierController;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.app.coring.KeyStates;
import id.veridian.signify.app.coring.Operations;
import id.veridian.signify.cesr.Serder;
import id.veridian.signify.generated.keria.model.Exn;
import id.veridian.signify.generated.keria.model.HabState;
import id.veridian.signify.generated.keria.model.KeyStateRecord;
import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * {@link KeriAttestService} only accepts a remotesign ref that answers ITS OWN request: the predicate it
 * hands the correlator requires the fetched exn's {@code r} to be the ref route, {@code p} the request SAID
 * (the one just sent on the main wait; the persisted one on the retry pre-check), {@code i} the wallet and
 * {@code rp} our agent — exactly how Veridian builds the ref.
 */
@ExtendWith(MockitoExtension.class)
class KeriAttestServiceRemotesignCorrelationTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String WALLET_AID = "EWALLETAID00000000000000000000000000000";
    private static final String OTHER_AID = "EOTHERAID000000000000000000000000000000";
    private static final String AGENT_NAME = "keriAttestationAgent";
    private static final String AGENT_PREFIX = "EAGENTPREFIX000000000000000000000000000";
    private static final String TARGET_TYPE = "DOCUMENT";
    private static final String TARGET_ID = "doc-1";
    private static final String DIGEST = "Edigest0000000000000000000000000000000000000";
    private static final String METADATA_LABEL = "1447";
    private static final String PAYLOAD_SAID = "Epayloadsaid00000000000000000000000000000000";
    private static final String OLD_REQUEST_EXN_SAID = "EOLDREQ0000000000000000000000000000000";
    private static final String NEW_REQUEST_EXN_SAID = "ENEWREQ0000000000000000000000000000000";
    private static final List<String> REMOTESIGN_REF_ROUTES =
            List.of("/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private IdentifierController identifiers;
    @Mock
    private Exchanging.Exchanges exchanges;
    @Mock
    private KeyStates keyStates;
    @Mock
    private Operations operations;
    @Mock
    private KeriAgentService agentService;
    @Mock
    private RemotesignRequestFactory kedFactory;
    @Mock
    private AttestationTargetProviderRegistry providerRegistry;
    @Mock
    private AttestationTargetProvider provider;
    @Mock
    private KeriNotificationCorrelator correlator;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private KeriOobiService oobiService;

    private KeriAttestService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.identifiers()).thenReturn(identifiers);
        lenient().when(client.exchanges()).thenReturn(exchanges);
        lenient().when(client.keyStates()).thenReturn(keyStates);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(agentService.agentName()).thenReturn(AGENT_NAME);
        lenient().when(agentService.agentPrefix()).thenReturn(AGENT_PREFIX);
        lenient().when(agentService.agentOobi()).thenReturn("https://keria.example/oobi/" + AGENT_PREFIX + "/agent/EAG");
        lenient().when(correlator.outstandingNoteIds(any())).thenReturn(Set.of());

        service = new KeriAttestService(keriClient, agentService, kedFactory, providerRegistry, correlator,
                ceremonyService, identityLinkRepository, properties(), oobiService,
                new KelAnchorVerifier(keriClient));
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier", null, null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT0.01S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT0.01S"), Duration.parse("PT0.05S"), Duration.parse("PT0.001S"),
                Duration.parse("PT0.001S"), Duration.parse("PT2M"), null);
    }

    private static KeriAttestationCeremonyEntity ceremony(String requestExnSaid) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setTargetType(TARGET_TYPE);
        ceremony.setTargetId(TARGET_ID);
        ceremony.setState(CeremonyState.ATTEST_REQUESTED);
        ceremony.setAttemptGeneration(GENERATION);
        ceremony.setRequestExnSaid(requestExnSaid);
        ceremony.setMetadataDigest(DIGEST);
        return ceremony;
    }

    private static KeriIdentityLinkEntity link() {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(WALLET_AID);
        return link;
    }

    /** A remotesign ref exn as the correlator hands it to the predicate (already a map). */
    private static Map<String, Object> ref(String route, String requestSaid, String sender, String recipient) {
        Map<String, Object> exn = new HashMap<>();
        exn.put("d", "EREFEXN0000000000000000000000000000000");
        exn.put("r", route);
        exn.put("i", sender);
        exn.put("a", Map.of("sn", "3"));
        if (requestSaid != null) {
            exn.put("p", requestSaid);
        }
        if (recipient != null) {
            exn.put("rp", recipient);
        }
        return exn;
    }

    private static Map<String, Object> refTo(String requestSaid) {
        return ref("/remotesign/ixn/ref", requestSaid, WALLET_AID, AGENT_PREFIX);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Predicate<Map<String, Object>>> predicateCaptor() {
        return ArgumentCaptor.forClass(Predicate.class);
    }

    private void stubUpToTheMainWait(KeriAttestationCeremonyEntity ceremony) {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link()));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(3);
                    mutator.accept(ceremony);
                    return true;
                });
        KeyStateRecord state = mock(KeyStateRecord.class);
        lenient().when(state.getS()).thenReturn("2");
        when(keyStates.get(WALLET_AID)).thenReturn(Optional.of(state));

        HabState hab = new HabState();
        hab.setPrefix(AGENT_PREFIX);
        hab.setName(AGENT_NAME);
        when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(hab));
        Serder builtExn = mock(Serder.class, RETURNS_DEFAULTS);
        lenient().when(builtExn.getKed()).thenReturn(Map.of("d", NEW_REQUEST_EXN_SAID));
        when(exchanges.createExchangeMessage(any(), eq("/remotesign/ixn/req"), anyMap(), anyMap(), eq(WALLET_AID),
                any(), any())).thenReturn(new ExchangeMessageResult(builtExn, List.of("sig1"), "atc1"));
        lenient().when(exchanges.sendFromEvents(any(), any(), any(), any(), any(), any())).thenReturn(new Exn());
    }

    @Test
    void mainWaitIgnoresARefForAnotherRequest() {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubUpToTheMainWait(ceremony);
        ArgumentCaptor<Predicate<Map<String, Object>>> predicate = predicateCaptor();
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any(), predicate.capture()))
                .thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        // Nothing answered our request, so the step times out rather than resolving someone else's ref.
        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_WALLET_TIMEOUT, result.getLeft().getTitle());

        Predicate<Map<String, Object>> answersUs = predicate.getValue();
        assertTrue(answersUs.test(refTo(NEW_REQUEST_EXN_SAID)), "the ref to the request just sent");
        assertFalse(answersUs.test(refTo(OLD_REQUEST_EXN_SAID)), "a ref to an earlier request");
        assertFalse(answersUs.test(refTo(null)), "a ref that names no request");
        assertFalse(answersUs.test(ref("/remotesign/ixn/ref", NEW_REQUEST_EXN_SAID, OTHER_AID, AGENT_PREFIX)),
                "a ref from another identifier");
        assertFalse(answersUs.test(ref("/remotesign/ixn/ref", NEW_REQUEST_EXN_SAID, WALLET_AID, OTHER_AID)),
                "a ref addressed to another agent");
        assertFalse(answersUs.test(ref("/remotesign/ixn/ref", NEW_REQUEST_EXN_SAID, WALLET_AID, null)),
                "a ref with no recipient");
        assertFalse(answersUs.test(ref("/ipex/grant", NEW_REQUEST_EXN_SAID, WALLET_AID, AGENT_PREFIX)),
                "a different route");
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void retryPrecheckOnlyResumesARefForThePersistedRequest() {
        KeriAttestationCeremonyEntity ceremony = ceremony(OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence("2");
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link()));
        ArgumentCaptor<Predicate<Map<String, Object>>> predicate = predicateCaptor();
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any(), predicate.capture()))
                .thenReturn(Optional.empty());
        // Stop right after the pre-check: this test is about what the pre-check accepts.
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.empty());

        service.attest(CEREMONY_ID, USER_ID, true);

        Predicate<Map<String, Object>> answersPersisted = predicate.getValue();
        assertTrue(answersPersisted.test(refTo(OLD_REQUEST_EXN_SAID)), "the late ref to the persisted request");
        assertFalse(answersPersisted.test(refTo(NEW_REQUEST_EXN_SAID)), "a ref to some other request");
        verify(exchanges, never()).sendFromEvents(any(), any(), any(), any(), any(), any());
    }
}
