package org.cardanofoundation.lob.app.organisation.service.csv;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a CSV column as the old name of a renamed column, so files prepared before the rename still import.
 * The field also needs {@code @CsvBindByName(column = "<old name>", profiles = "optional")}.
 *
 * <p>{@link CsvParser} then treats the old header as satisfying the required {@link #replacedBy()} header,
 * and rejects a file that has both, instead of silently picking one of them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CsvLegacyHeader {

    /** The current header that replaced this one. */
    String replacedBy();
}
