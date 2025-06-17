package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.util.Map;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType;

@RequiredArgsConstructor
@Slf4j
public class PreprocessorService {

    private final Map<FieldType, Function<String, Either<Problem, String>>> fieldProcessors;

    public Either<Problem, String> preProcess(String data, FieldType fieldType) {
        Function<String, Either<Problem, String>> fieldProcessorFun = fieldProcessors.get(fieldType);

        if (fieldProcessorFun == null) {
            throw new RuntimeException("Field processor not found for field severity: %s".formatted(fieldType));
        }

        return fieldProcessorFun.apply(data);
    }

}
