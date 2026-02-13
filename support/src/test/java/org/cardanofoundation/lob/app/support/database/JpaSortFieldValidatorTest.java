package org.cardanofoundation.lob.app.support.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.persistence.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@DataJpaTest
@ContextConfiguration(classes = JpaSortFieldValidatorTest.TestConfig.class)
@Import(JpaSortFieldValidator.class)
class JpaSortFieldValidatorTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    public static class TestConfig {
    }

    @Autowired
    EntityManager entityManager;

    private JpaSortFieldValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JpaSortFieldValidator(entityManager);
    }

    @Test
    void isSortable_validFields() {
        assertTrue(validator.isSortable(TestRootEntity.class, "name"));
        assertTrue(validator.isSortable(TestRootEntity.class, "status"));
        assertTrue(validator.isSortable(TestRootEntity.class, "nested.value"));
    }

    @Test
    void isSortable_invalidFields() {
        assertFalse(validator.isSortable(TestRootEntity.class, "nonExistent"));
        assertFalse(validator.isSortable(TestRootEntity.class, "nested.nonExistent"));
        assertFalse(validator.isSortable(TestRootEntity.class, "nested.value.deep"));
    }

    @Test
    void validateEntity_invalidSortProperty() {
        PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("invalidField"));

        Either<Problem, Pageable> result = validator.validateEntity(TestRootEntity.class, pageable, Map.of());

        assertTrue(result.isLeft());
        assertEquals("Invalid sort: invalidField", result.getLeft().getDetail());
    }

    @Test
    void validateEntity_validSortProperty() {
        PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("nested.value"));

        Either<Problem, Pageable> result = validator.validateEntity(TestRootEntity.class, pageable, Map.of());

        assertTrue(result.isRight());
    }

    @Test
    void convertPageable_notSortableProperty() {
        PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("invalidField"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isLeft());
        assertEquals("Invalid sort: invalidField", result.getLeft().getDetail());
    }

    @Test
    void convertPageable_sortableProperty() {
        PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("invalidButMappedField"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of("invalidButMappedField", "nested.value"), TestRootEntity.class);

        assertTrue(result.isRight());
    }

    @Test
    void convertPageable_enum() {
        PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("status"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        Sort.Order orderFor = converted.getSort().getOrderFor("function('enum_to_text', status)");
        assertTrue(orderFor.isAscending());
        Sort.Order notAvailable = converted.getSort().getOrderFor("status");
        assertNull(notAvailable);
    }

    @Test
    void convertPageable_enumDescDirection() {
        PageRequest pageable = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "status"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        Sort.Order orderFor = converted.getSort().getOrderFor("function('enum_to_text', status)");
        assertTrue(orderFor.isDescending());
    }

    @Test
    void convertPageable_unsortedPageable() {
        PageRequest pageable = PageRequest.of(0, 10);

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        assertTrue(converted.getSort().isUnsorted());
    }

    @Test
    void convertPageable_mixedEnumAndNonEnumSort() {
        PageRequest pageable = PageRequest.of(0, 10,
                Sort.by(
                        Sort.Order.asc("status"),
                        Sort.Order.desc("name")
                ));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        Sort.Order enumOrder = converted.getSort().getOrderFor("function('enum_to_text', status)");
        assertTrue(enumOrder.isAscending());
        Sort.Order nameOrder = converted.getSort().getOrderFor("name");
        assertTrue(nameOrder.isDescending());
    }

    @Test
    void convertPageable_enumWithFieldMapping() {
        PageRequest pageable = PageRequest.of(0, 10,
                Sort.by("mappedStatus"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of("mappedStatus", "status"), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        Sort.Order orderFor = converted.getSort().getOrderFor("function('enum_to_text', status)");
        assertTrue(orderFor.isAscending());
        assertNull(converted.getSort().getOrderFor("mappedStatus"));
    }

    @Test
    void convertPageable_nonEnumPreservesDirection() {
        PageRequest pageable = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "name"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        Sort.Order nameOrder = converted.getSort().getOrderFor("name");
        assertTrue(nameOrder.isDescending());
    }

    @Test
    void convertPageable_preservesPageInfo() {
        PageRequest pageable = PageRequest.of(3, 25,
                Sort.by("name"));

        Either<Problem, Pageable> result = validator.convertPageable(pageable, Map.of(), TestRootEntity.class);

        assertTrue(result.isRight());
        Pageable converted = result.get();
        assertEquals(3, converted.getPageNumber());
        assertEquals(25, converted.getPageSize());
    }

}

// ---------- Test JPA Entities ----------

@Entity
class TestRootEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Embedded
    NestedEntity nested;

    @Enumerated(EnumType.STRING)
    StatusEnum status;

    String name;
}

@Embeddable
class NestedEntity {
    String value;
}

enum StatusEnum {A, B}
