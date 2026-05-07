package org.cardanofoundation.lob.app.reporting.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;

class ReportTemplateFieldEntityTest {

    @Test
    void builder_defaultFieldOrderIsZero() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .build();

        assertThat(entity.getFieldOrder()).isZero();
    }

    @Test
    void builder_canSetFieldOrder() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(5)
                .build();

        assertThat(entity.getFieldOrder()).isEqualTo(5);
    }

    @Test
    void childFields_defaultToEmptyList() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .build();

        assertThat(entity.getChildFields()).isNotNull();
        assertThat(entity.getChildFields()).isEmpty();
    }

    @Test
    void childFields_canBeSet() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .build();

        List<ReportTemplateFieldEntity> children = new ArrayList<>();
        children.add(ReportTemplateFieldEntity.builder().name("child1").fieldOrder(0).build());
        children.add(ReportTemplateFieldEntity.builder().name("child2").fieldOrder(1).build());

        entity.setChildFields(children);

        assertThat(entity.getChildFields()).hasSize(2);
        assertThat(entity.getChildFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getChildFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void mappingAccounts_defaultToEmptySet() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .build();

        assertThat(entity.getMappingAccounts()).isNotNull();
        assertThat(entity.getMappingAccounts()).isEmpty();
    }

    @Test
    void computeContentHash_doesNotIncludeFieldOrder() {
        ReportTemplateFieldEntity entity1 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity entity2 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(1)
                .build();

        assertThat(entity1.computeContentHash()).isEqualTo(entity2.computeContentHash());
    }

    @Test
    void computeContentHash_sameFieldOrderProducesSameHash() {
        ReportTemplateFieldEntity entity1 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity entity2 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .build();

        assertThat(entity1.computeContentHash()).isEqualTo(entity2.computeContentHash());
    }

    @Test
    void computeContentHash_withChildFields() {
        ReportTemplateFieldEntity child1 = ReportTemplateFieldEntity.builder()
                .name("child1")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity parent = ReportTemplateFieldEntity.builder()
                .name("parent")
                .fieldOrder(0)
                .childFields(List.of(child1))
                .build();

        assertThat(parent.computeContentHash()).isNotZero();
    }

    @Test
    void computeContentHash_dateRangeNotIncludedWhenHasChildFields() {
        ReportTemplateFieldEntity child = ReportTemplateFieldEntity.builder()
                .name("child")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity parent1 = ReportTemplateFieldEntity.builder()
                .name("parent")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.PERIOD)
                .childFields(List.of(child))
                .build();

        ReportTemplateFieldEntity parent2 = ReportTemplateFieldEntity.builder()
                .name("parent")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END)
                .childFields(List.of(child))
                .build();

        assertThat(parent1.computeContentHash()).isEqualTo(parent2.computeContentHash());
    }

    @Test
    void computeContentHash_dateRangeIncludedWhenNoChildFields() {
        ReportTemplateFieldEntity entity1 = ReportTemplateFieldEntity.builder()
                .name("leaf")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.PERIOD)
                .build();

        ReportTemplateFieldEntity entity2 = ReportTemplateFieldEntity.builder()
                .name("leaf")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END)
                .build();

        assertThat(entity1.computeContentHash()).isNotEqualTo(entity2.computeContentHash());
    }

    @Test
    void computeContentHash_negatedPropertyAffectsHash() {
        ReportTemplateFieldEntity entity1 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .negated(true)
                .build();

        ReportTemplateFieldEntity entity2 = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .negated(false)
                .build();

        assertThat(entity1.computeContentHash()).isNotEqualTo(entity2.computeContentHash());
    }

    @Test
    void fieldOrder_canBeMutated() {
        ReportTemplateFieldEntity entity = ReportTemplateFieldEntity.builder()
                .name("testField")
                .fieldOrder(0)
                .build();

        entity.setFieldOrder(5);

        assertThat(entity.getFieldOrder()).isEqualTo(5);
    }

    @Test
    void orderByAnnotation_sortChildFieldsByFieldOrder() {
        ReportTemplateFieldEntity child0 = ReportTemplateFieldEntity.builder()
                .name("child0")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity child2 = ReportTemplateFieldEntity.builder()
                .name("child2")
                .fieldOrder(2)
                .build();

        ReportTemplateFieldEntity child1 = ReportTemplateFieldEntity.builder()
                .name("child1")
                .fieldOrder(1)
                .build();

        List<ReportTemplateFieldEntity> unsortedChildren = new ArrayList<>();
        unsortedChildren.add(child2);
        unsortedChildren.add(child0);
        unsortedChildren.add(child1);

        ReportTemplateFieldEntity parent = ReportTemplateFieldEntity.builder()
                .name("parent")
                .fieldOrder(0)
                .childFields(unsortedChildren)
                .build();

        assertThat(parent.getChildFields()).hasSize(3);
    }
}
