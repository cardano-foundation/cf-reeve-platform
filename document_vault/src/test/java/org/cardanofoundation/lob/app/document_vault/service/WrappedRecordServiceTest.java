package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.repository.WrappedRecordRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class WrappedRecordServiceTest {

    @Mock
    private WrappedRecordRepository recordRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;

    @InjectMocks
    private WrappedRecordService service;

    @BeforeEach
    void setUp() {
        // lenient: STRICT_STUBS would fail the oversize test, which returns before reading the user
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("acc1");
        ReflectionTestUtils.setField(service, "maxRecordBytes", 8192L);
    }

    private UpsertWrappedRecordRequest request(String blob) {
        UpsertWrappedRecordRequest request = new UpsertWrappedRecordRequest();
        request.setRecord(blob);
        request.setVersion(1);
        return request;
    }

    @Test
    void upsertStoresBlobVerbatim() {
        when(recordRepository.save(any(WrappedRecordEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, WrappedRecordView> result = service.upsert("cred-1", request("{\"v\":1}"));

        assertTrue(result.isRight());
        assertEquals("{\"v\":1}", result.get().record());
        assertEquals("cred-1", result.get().credentialId());
    }

    @Test
    void upsertRejectsOversizedBlob() {
        Either<ProblemDetail, WrappedRecordView> result = service.upsert("cred-1", request("x".repeat(9000)));

        assertTrue(result.isLeft());
        assertEquals(413, result.getLeft().getStatus());
    }

    @Test
    void getReturnsOwnRecordOnly() {
        when(recordRepository.findById(new WrappedRecordId("acc1", "cred-1"))).thenReturn(Optional.empty());

        Either<ProblemDetail, WrappedRecordView> result = service.get("cred-1");

        assertTrue(result.isLeft());
        assertEquals(404, result.getLeft().getStatus());
    }
}
