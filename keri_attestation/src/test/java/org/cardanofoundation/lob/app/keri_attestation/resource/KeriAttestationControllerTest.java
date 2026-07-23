package org.cardanofoundation.lob.app.keri_attestation.resource;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.RequiredSteps;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CeremonyService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestationProblems;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAuthBeginService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriCredentialService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * MockMvc slice test: the controller wired against bare Mockito mocks of every collaborator via
 * {@link MockMvcBuilders#standaloneSetup}, no Spring context — mirrors document_vault's precedent of
 * unit-testing the resource layer against mocked services (see e.g. {@code VaultDocumentServiceTest}
 * for the service-layer half of that split), extended here to also exercise the HTTP layer itself
 * (status codes, JSON shape, {@code @Valid} enforcement) since document_vault has no equivalent
 * controller-level slice test to copy verbatim.
 */
@ExtendWith(MockitoExtension.class)
class KeriAttestationControllerTest {

    private static final String USER_ID = "user-1";
    private static final String CEREMONY_ID = "cer-1";

    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriOobiService oobiService;
    @Mock
    private KeriAgentService agentService;
    @Mock
    private KeriCredentialService credentialService;
    @Mock
    private KeriAuthBeginService authBeginService;
    @Mock
    private KeriAttestService attestService;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(USER_ID);
        KeriAttestationController controller = new KeriAttestationController(securityHelper, ceremonyService,
                oobiService, agentService, credentialService, authBeginService, attestService, identityLinkRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static CeremonyView ceremonyView(CeremonyState state) {
        return new CeremonyView(CEREMONY_ID, state, new RequiredSteps(false, false, false), null, null, null, null,
                null, null);
    }

    // ==================== GET /identity ====================

    @Test
    void identityReturnsLinkedFalseWhenNoLinkExists() throws Exception {
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/keri-attestation/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.aid").doesNotExist())
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.authBegin").doesNotExist());
    }

    @Test
    void identityReturnsFullShapeWhenLinked() throws Exception {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid("EAID000000000000000000000000000000000000");
        link.setCredentialSaid("ECRED00000000000000000000000000000000000");
        link.setCredentialSchemaSaid("ESCHEMA0000000000000000000000000000000000");
        link.setAuthBeginTxHash("deadbeef");
        link.setAuthBeginAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));

        mockMvc.perform(get("/api/v1/keri-attestation/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.aid").value("EAID000000000000000000000000000000000000"))
                .andExpect(jsonPath("$.credential.said").value("ECRED00000000000000000000000000000000000"))
                .andExpect(jsonPath("$.credential.schemaSaid").value("ESCHEMA0000000000000000000000000000000000"))
                .andExpect(jsonPath("$.authBegin.txHash").value("deadbeef"))
                .andExpect(jsonPath("$.authBegin.external").value(false));
    }

    @Test
    void identityWithNoAuthBeginTxHashOmitsAuthBegin() throws Exception {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid("EAID000000000000000000000000000000000000");
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link));

        mockMvc.perform(get("/api/v1/keri-attestation/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.authBegin").doesNotExist());
    }

    // ==================== GET /agent/oobi ====================

    @Test
    void agentOobiReturnsTheCachedAgentOobiUrl() throws Exception {
        when(agentService.agentOobi()).thenReturn("https://agent.example.org/oobi/EAGENT/agent/EAGENT");

        mockMvc.perform(get("/api/v1/keri-attestation/agent/oobi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oobiUrl").value("https://agent.example.org/oobi/EAGENT/agent/EAGENT"));
    }

    // ==================== POST /identity/oobi/resolve ====================

    @Test
    void resolveOobiHappyPathReturns200WithAid() throws Exception {
        when(oobiService.resolveUserOobi(eq(USER_ID), anyString(), eq(false)))
                .thenReturn(Either.right("EAID000000000000000000000000000000000000"));

        mockMvc.perform(post("/api/v1/keri-attestation/identity/oobi/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oobiUrl":"https://example.org/oobi/EAID/witness/EWIT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aid").value("EAID000000000000000000000000000000000000"));
    }

    @Test
    void resolveOobiMissingOobiUrlIsRejectedWithBadRequestBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/keri-attestation/identity/oobi/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oobiService);
    }

    @Test
    void resolveOobiServiceRejectionIsMappedToItsProblemStatusAndTitle() throws Exception {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.IDENTITY_RELINKED,
                "already linked to a different AID");
        when(oobiService.resolveUserOobi(eq(USER_ID), anyString(), eq(false))).thenReturn(Either.left(problem));

        mockMvc.perform(post("/api/v1/keri-attestation/identity/oobi/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oobiUrl":"https://example.org/oobi/EAID/witness/EWIT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value(KeriAttestationProblems.IDENTITY_RELINKED));
    }

    // ==================== DELETE /identity ====================

    @Test
    void resetIdentityHappyPathReturns200WithResetTrue() throws Exception {
        when(oobiService.resetIdentity(USER_ID)).thenReturn(Either.right(null));

        mockMvc.perform(delete("/api/v1/keri-attestation/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));

        verify(oobiService).resetIdentity(USER_ID);
    }

    @Test
    void resetIdentityWithNoLinkIsStillA200() throws Exception {
        // Idempotent: no link present is not an error -- the service itself always returns Right.
        when(oobiService.resetIdentity(USER_ID)).thenReturn(Either.right(null));

        mockMvc.perform(delete("/api/v1/keri-attestation/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));
    }

    // ==================== POST /ceremonies ====================

    @Test
    void createCeremonyHappyPathReturns201WithCeremonyView() throws Exception {
        when(ceremonyService.create(USER_ID, "DOCUMENT", "doc-1"))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREATED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"DOCUMENT","targetId":"doc-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CEREMONY_ID))
                .andExpect(jsonPath("$.state").value("CREATED"));
    }

    @Test
    void createCeremonyMissingTargetTypeIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetId":"doc-1"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ceremonyService);
    }

    // ==================== POST /ceremonies/{id}/credential/request ====================

    @Test
    void requestCredentialHappyPathReturns200WithCeremonyView() throws Exception {
        when(credentialService.presentCredential(CEREMONY_ID, USER_ID, false))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/credential/request", CEREMONY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CEREMONY_ID))
                .andExpect(jsonPath("$.state").value("CREDENTIAL_RECEIVED"));
    }

    @Test
    void requestCredentialWithNoBodyDefaultsRetryToFalse() throws Exception {
        when(credentialService.presentCredential(CEREMONY_ID, USER_ID, false))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/credential/request", CEREMONY_ID))
                .andExpect(status().isOk());

        verify(credentialService).presentCredential(CEREMONY_ID, USER_ID, false);
    }

    @Test
    void requestCredentialWithRetryTruePassesItThrough() throws Exception {
        when(credentialService.presentCredential(CEREMONY_ID, USER_ID, true))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/credential/request", CEREMONY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retry":true}
                                """))
                .andExpect(status().isOk());

        verify(credentialService).presentCredential(CEREMONY_ID, USER_ID, true);
    }

    @Test
    void requestCredentialProblemIsMappedToItsStatus() throws Exception {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "wrong state");
        when(credentialService.presentCredential(CEREMONY_ID, USER_ID, false)).thenReturn(Either.left(problem));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/credential/request", CEREMONY_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value(KeriAttestationProblems.CEREMONY_INVALID_STATE));
    }

    // ==================== POST /ceremonies/{id}/auth-begin ====================

    @Test
    void submitAuthBeginHappyPathReturns200AndPassesExternalTxHashThrough() throws Exception {
        when(authBeginService.submitAuthBegin(CEREMONY_ID, USER_ID, "deadbeef", false))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/auth-begin", CEREMONY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalTxHash":"deadbeef"}
                                """))
                .andExpect(status().isOk());

        verify(authBeginService).submitAuthBegin(CEREMONY_ID, USER_ID, "deadbeef", false);
    }

    @Test
    void submitAuthBeginWithNoBodySubmitsAFreshTransaction() throws Exception {
        when(authBeginService.submitAuthBegin(CEREMONY_ID, USER_ID, null, false))
                .thenReturn(Either.right(ceremonyView(CeremonyState.AUTH_BEGIN_CONFIRMED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/auth-begin", CEREMONY_ID))
                .andExpect(status().isOk());

        verify(authBeginService).submitAuthBegin(CEREMONY_ID, USER_ID, null, false);
    }

    // ==================== POST /ceremonies/{id}/attest ====================

    @Test
    void attestHappyPathReturns200() throws Exception {
        when(attestService.attest(CEREMONY_ID, USER_ID, false))
                .thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/attest", CEREMONY_ID))
                .andExpect(status().isOk());
    }

    @Test
    void attestWithRetryTruePassesItThrough() throws Exception {
        when(attestService.attest(CEREMONY_ID, USER_ID, true))
                .thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));

        mockMvc.perform(post("/api/v1/keri-attestation/ceremonies/{id}/attest", CEREMONY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retry":true}
                                """))
                .andExpect(status().isOk());

        verify(attestService).attest(CEREMONY_ID, USER_ID, true);
    }

    // ==================== GET /ceremonies/{id} ====================

    @Test
    void getCeremonyHappyPathReturns200WithCeremonyView() throws Exception {
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));

        mockMvc.perform(get("/api/v1/keri-attestation/ceremonies/{id}", CEREMONY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CEREMONY_ID))
                .andExpect(jsonPath("$.state").value("ATTEST_ANCHORED"));
    }

    @Test
    void getCeremonyUnknownIdReturns404WithProblemTitle() throws Exception {
        ProblemDetail problem = KeriAttestationProblems.notFound(KeriAttestationProblems.CEREMONY_NOT_FOUND,
                "Ceremony cer-1 was not found.");
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.left(problem));

        mockMvc.perform(get("/api/v1/keri-attestation/ceremonies/{id}", CEREMONY_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value(KeriAttestationProblems.CEREMONY_NOT_FOUND));
    }

    @Test
    void getCeremonyNonOwnerReturns403WithProblemTitle() throws Exception {
        ProblemDetail problem = KeriAttestationProblems.forbidden("Ceremony cer-1 does not belong to the current user.");
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.left(problem));

        mockMvc.perform(get("/api/v1/keri-attestation/ceremonies/{id}", CEREMONY_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value(KeriAttestationProblems.CEREMONY_FORBIDDEN));
    }
}
