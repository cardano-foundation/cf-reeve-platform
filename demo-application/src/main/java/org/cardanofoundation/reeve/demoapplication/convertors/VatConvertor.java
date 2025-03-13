package org.cardanofoundation.reeve.demoapplication.convertors;

import io.vavr.control.Either;
import lombok.val;
import org.zalando.problem.Problem;

import java.util.function.Function;

public class VatConvertor implements Function<String, Either<Problem, String>> {

    @Override
    public Either<Problem, String> apply(String s) {
        val split = s.split(":");

        if (split.length == 1) {
            return Either.right(s);
        }

        if (split.length == 2) {
            return Either.right(split[1].trim());
        }

        return Either.left(Problem.builder()
                .withTitle("INVALID_VAT")
                .withDetail("Invalid vat code")
                .with("vat", s)
                .build());
    }

}