package org.cardanofoundation.reeve.demoapplication.convertors;

import io.vavr.control.Either;
import lombok.val;
import org.zalando.problem.Problem;

import java.util.function.Function;
import java.util.regex.Pattern;

public class ProjectConvertor implements Function<String, Either<Problem, String>> {

    private static final Pattern pattern = Pattern.compile("^([A-Z]{2}) (\\d{6}) (\\d{4}) (.+)$");

    @Override
    public Either<Problem, String> apply(String s) {
        val matcher = pattern.matcher(s);

        if (matcher.matches()) {
            return Either.right(STR."\{matcher.group(1)} \{matcher.group(2)} \{matcher.group(3)}");
        }

        return Either.left(Problem.builder()
                .withTitle("INVALID_PROJECT")
                .withDetail("Invalid project code")
                .with("project", s)
                .build());
    }

}
