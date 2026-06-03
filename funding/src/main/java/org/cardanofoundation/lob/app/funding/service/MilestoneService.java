package org.cardanofoundation.lob.app.funding.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final FundingProjectRepository projectRepository;

    public Optional<MilestoneEntity> findById(String milestoneId) {
        return milestoneRepository.findById(milestoneId);
    }

    public List<MilestoneEntity> findByProjectId(String projectId) {
        return milestoneRepository.findByProject_Id(projectId);
    }

    public Page<MilestoneEntity> findByProjectId(String projectId, Pageable pageable) {
        return milestoneRepository.findByProject_Id(projectId, pageable);
    }

    @Transactional
    public Optional<MilestoneEntity> create(String projectId, MilestoneCreateRequest request) {
        return projectRepository.findById(projectId).map(project -> {
            MilestoneEntity milestone = MilestoneEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .projectId(projectId)
                    .label(request.getLabel())
                    .expectedCost(request.getExpectedCost())
                    .currency(request.getCurrency())
                    .dueDate(request.getDueDate())
                    .project(project)
                    .build();

            return milestoneRepository.saveAndFlush(milestone);
        });
    }

    @Transactional
    public Optional<MilestoneEntity> update(String milestoneId, MilestoneUpdateRequest request) {
        return milestoneRepository.findById(milestoneId).map(milestone -> {
            if (request.getLabel() != null) {
                milestone.setLabel(request.getLabel());
            }
            if (request.getExpectedCost() != null) {
                milestone.setExpectedCost(request.getExpectedCost());
            }
            if (request.getCurrency() != null) {
                milestone.setCurrency(request.getCurrency());
            }
            if (request.getDueDate() != null) {
                milestone.setDueDate(request.getDueDate());
            }
            return milestoneRepository.saveAndFlush(milestone);
        });
    }

    @Transactional
    public boolean delete(String milestoneId) {
        if (!milestoneRepository.existsById(milestoneId)) {
            return false;
        }
        milestoneRepository.deleteById(milestoneId);
        return true;
    }

    public boolean belongsToProject(MilestoneEntity milestone, ProjectEntity project) {
        return milestone.getProject().getId().equals(project.getId());
    }

    public MilestoneView toView(MilestoneEntity milestone) {
        return MilestoneView.builder()
                .milestoneId(milestone.getId())
                .projectId(milestone.getProjectId())
                .label(milestone.getLabel())
                .expectedCost(milestone.getExpectedCost())
                .currency(milestone.getCurrency())
                .dueDate(milestone.getDueDate())
                .build();
    }

}
