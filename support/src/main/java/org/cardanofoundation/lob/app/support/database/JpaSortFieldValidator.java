package org.cardanofoundation.lob.app.support.database;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;


@Service
@RequiredArgsConstructor
public class JpaSortFieldValidator {

    private final EntityManager entityManager;

    public Either<ProblemDetail, Pageable> validateEntity(Class entityClass, Pageable pageable, Map<String, String> mappings) {
        if(pageable.getSort().isSorted()) {
            Optional<Sort.Order> notSortableProperty = pageable.getSort().get().filter(order -> {
                String property = Optional.ofNullable(mappings.get(order.getProperty())).orElse(order.getProperty());

                return !this.isSortable(entityClass, property);

            }).findFirst();
            if (notSortableProperty.isPresent()) {
                ProblemDetail problemDetail = ProblemDetail
                        .forStatusAndDetail(HttpStatusCode.valueOf(400), "Invalid sort: " + notSortableProperty.get().getProperty());
                problemDetail.setTitle("Invalid Sort Property");
                return Either.left(problemDetail);
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


    public Either<ProblemDetail, Pageable> convertPageable(Pageable page,
                                                     Map<String, String> fieldMappings, Class<?> classType) {
        if (page.getSort().isSorted()) {
            Optional<Sort.Order> notSortableProperty =
                    page.getSort().get().filter(order -> {
                        String property = Optional
                                .ofNullable(fieldMappings.get(order
                                        .getProperty()))
                                .orElse(order.getProperty());

                        return !isSortable(classType,
                                property);
                    }).findFirst();

            if (notSortableProperty.isPresent()) {
                ProblemDetail problemDetail = ProblemDetail
                        .forStatusAndDetail(HttpStatusCode.valueOf(400), "Invalid sort: " + notSortableProperty.get().getProperty());
                problemDetail.setTitle("Invalid Sort Property");
                return Either.left(problemDetail);
            }

            Sort sort = Sort.by(page.getSort().get().map(order -> {
                String property = Optional
                        .ofNullable(fieldMappings.get(order.getProperty()))
                        .orElse(order.getProperty());

                boolean isEnum = false;
                try {
                    String[] parts = property.split("\\.");
                    Class<?> currentClass = classType;

                    for (int i = 0; i < parts.length; i++) {
                        Field field = currentClass
                                .getDeclaredField(parts[i]);
                        currentClass = field.getType();

                        if (i == parts.length - 1) {
                            isEnum = currentClass.isEnum();
                        }
                    }
                } catch (NoSuchFieldException ignored) {
                }

                if (isEnum) {
                    return JpaSort.unsafe(order.getDirection(),
                                    "function('enum_to_text', " + property
                                            + ")")
                            .iterator().next();
                }

                return new Sort.Order(order.getDirection(), property);
            }).toList());

            return Either.right(PageRequest.of(page.getPageNumber(), page.getPageSize(),
                    sort));
        }

        return Either.right(page);
    }
}
