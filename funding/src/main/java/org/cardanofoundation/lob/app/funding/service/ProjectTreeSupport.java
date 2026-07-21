package org.cardanofoundation.lob.app.funding.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

/** Shared traversal helpers over the self-referential project tree. */
final class ProjectTreeSupport {

    private ProjectTreeSupport() {
    }

    /** The project plus every descendant sub-project, resolved by walking the parent links downward. */
    static Set<String> subtreeProjectIds(FundingProjectRepository projectRepository, String rootId) {
        Set<String> ids = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!ids.add(id)) {
                continue;
            }
            for (ProjectEntity child : projectRepository.findByParentProjectId(id)) {
                stack.push(child.getId());
            }
        }
        return ids;
    }
}
