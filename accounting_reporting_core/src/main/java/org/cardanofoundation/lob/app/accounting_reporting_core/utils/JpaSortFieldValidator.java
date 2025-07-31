package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;

import org.springframework.stereotype.Component;

@Component
public class JpaSortFieldValidator {

    private final EntityManager entityManager;

    public JpaSortFieldValidator(EntityManager entityManager) {
        this.entityManager = entityManager;
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

                    currentType = attribute.getDeclaringType();
                }
            }
            return true; // If the loop completes without exception, the path is valid.
        } catch (IllegalArgumentException e) {
            // Thrown by getAttribute() if any part of the path is not found.
            return false;
        }
    }
}
