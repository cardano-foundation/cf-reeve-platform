package org.cardanofoundation.reeve.demoapplication.convertors;

import io.vavr.control.Either;
import lombok.val;
import org.zalando.problem.Problem;

import java.util.function.Function;
import java.util.regex.Pattern;

public class AccountNumberConvertor implements Function<String, Either<Problem, String>> {

    private static final Pattern pattern = Pattern.compile("^(\\d+)");

    @Override
    public Either<Problem, String> apply(String s) {
        val matcher = pattern.matcher(s);

        if (matcher.find()) {
            return Either.right(matcher.group(1));
        }

        return Either.left(Problem.builder()
                .withTitle("INVALID_ACCOUNT_NUMBER")
                .withDetail("Invalid account number")
                .with("account_number", s)
                .build()
        );
    }

}