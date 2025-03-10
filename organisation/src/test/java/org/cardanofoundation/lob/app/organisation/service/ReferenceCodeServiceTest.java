package org.cardanofoundation.lob.app.organisation.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@ExtendWith(MockitoExtension.class)
class ReferenceCodeServiceTest {

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

    @InjectMocks
    private ReferenceCodeService referenceCodeService;

    private static final String ORG_ID = "org123";
    private static final String REF_CODE = "ref001";

    private ReferenceCode referenceCode;
    private ReferenceCodeView referenceCodeView;
    private ReferenceCodeUpdate referenceCodeUpdate;

    @BeforeEach
    void setUp() {
        referenceCode = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, REF_CODE))
                .name("Test Reference")
                .build();

        referenceCodeView = ReferenceCodeView.fromEntity(referenceCode);

        referenceCodeUpdate = new ReferenceCodeUpdate(REF_CODE, "Updated Reference");
    }

    @Test
    void testGetAllReferenceCodes() {
        when(referenceCodeRepository.findAllByOrgId(ORG_ID)).thenReturn(List.of(referenceCode));

        List<ReferenceCodeView> result = referenceCodeService.getAllReferenceCodes(ORG_ID);

        assertEquals(1, result.size());
        assertEquals(referenceCodeView.getReferenceCode(), result.getFirst().getReferenceCode());
        assertEquals(referenceCodeView.getDescription(), result.getFirst().getDescription());
        verify(referenceCodeRepository).findAllByOrgId(ORG_ID);
    }

    @Test
    void testGetReferenceCode_Found() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.of(referenceCode));

        Optional<ReferenceCodeView> result = referenceCodeService.getReferenceCode(ORG_ID, REF_CODE);

        assertTrue(result.isPresent());
        assertEquals(referenceCodeView.getReferenceCode(), result.get().getReferenceCode());
        assertEquals(referenceCodeView.getDescription(), result.get().getDescription());
        verify(referenceCodeRepository).findByOrgIdAndReferenceCode(ORG_ID, REF_CODE);
    }

    @Test
    void testGetReferenceCode_NotFound() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.empty());

        Optional<ReferenceCodeView> result = referenceCodeService.getReferenceCode(ORG_ID, REF_CODE);

        assertTrue(result.isEmpty());
        verify(referenceCodeRepository).findByOrgIdAndReferenceCode(ORG_ID, REF_CODE);
    }

    @Test
    void testUpsertReferenceCode_InsertNew() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.empty());

        Optional<ReferenceCodeView> result = referenceCodeService.upsertReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.isPresent());
        assertEquals("Updated Reference", result.get().getDescription());
        verify(referenceCodeRepository).save(any(ReferenceCode.class));
    }

    @Test
    void testUpsertReferenceCode_UpdateExisting() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.of(referenceCode));

        Optional<ReferenceCodeView> result = referenceCodeService.upsertReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.isPresent());
        assertEquals("Updated Reference", result.get().getDescription());
        verify(referenceCodeRepository).save(referenceCode);
    }
}
