package org.cardanofoundation.lob.app.organisation.util;

import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;


@Component
public class JpaSortFieldValidator {

    private final EntityManager entityManager;

    public JpaSortFieldValidator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Either<Problem, Pageable> validateEntity(Class entityClass, Pageable pageable, Map<String, String> mappings) {
        if(pageable.getSort().isSorted()) {
            Optional<Sort.Order> notSortableProperty = pageable.getSort().get().filter(order -> {
                String property = Optional.ofNullable(mappings.get(order.getProperty())).orElse(order.getProperty());

                return !this.isSortable(entityClass, property);

            }).findFirst();
            if (notSortableProperty.isPresent()) {
                return Either.left(Problem.builder()
                        .withStatus(Status.BAD_REQUEST)
                        .withTitle("Invalid Sort Property")
                        .withDetail("Invalid sort: " + notSortableProperty.get().getProperty())
                        .build());
            }
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(pageable.getSort().get().map(order -> new Sort.Order(order.getDirection(),
                            Optional.ofNullable(mappings.get(order.getProperty())).orElse(order.getProperty()))).toList()));
        }
        return Either.right(pageable);
    }

    public boolean isSortable(Class<?> entityClass, String propertyPath) {
        try {
            ManagedType<?> currentType = entityManager.getMetamodel().managedType(entityClass);
            String[] parts = propertyPath.split("\\.");

            // Navigate through the property path (e.g., "reconcilation.source")
            for (String part : parts) {
                // Get the attribute for the current part. Fails if 'part' isn't a valid attribute.

                Attribute<?, ?> attribute = currentType.getAttribute(part);

                // Update the currentType for the next iteration if it's a traversable type (entity or embedded).
                if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.EMBEDDED ||
                        attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE ||
                        attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE) {

                    currentType = entityManager.getMetamodel().managedType(attribute.getJavaType());
                }
            }
            return true; // If the loop completes without exception, the path is valid.
        } catch (IllegalArgumentException e) {
            // Thrown by getAttribute() if any part of the path is not found.
            return false;
        }
    }
}
