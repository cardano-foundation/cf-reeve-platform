package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.service.ProjectChildSequenceService.ChildKind;

@ExtendWith(MockitoExtension.class)
class ProjectChildSequenceServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;

    @Test
    void sharedCounter_prefixesSubProjectsWithS_andMilestonesWithM() {
        ProjectEntity parent = ProjectEntity.builder().id("p1").proId("PRJ-A").build();
        when(projectRepository.findWithLockById("p1")).thenReturn(Optional.of(parent));
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        ProjectChildSequenceService service = new ProjectChildSequenceService(projectRepository);

        assertThat(service.nextChildProId(parent, ChildKind.SUB_PROJECT)).isEqualTo("PRJ-A-S1");
        assertThat(service.nextChildProId(parent, ChildKind.MILESTONE)).isEqualTo("PRJ-A-M2");
    }

}
