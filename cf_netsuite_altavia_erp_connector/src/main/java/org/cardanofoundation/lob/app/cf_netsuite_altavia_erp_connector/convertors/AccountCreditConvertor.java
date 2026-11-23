package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import java.util.function.Function;
import java.util.regex.Pattern;

import lombok.val;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

public class AccountCreditConvertor implements Function<String, Either<ProblemDetail, String>> {

    private static final Pattern pattern = Pattern.compile("^(\\d+)\\s(.*)$");

    @Override
    public Either<ProblemDetail, String> apply(String s) {
        val matcher = pattern.matcher(s);

        if (matcher.matches()) {
            return Either.right(matcher.group(2));
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid credit account code");
        problemDetail.setTitle("INVALID_CREDIT_ACCOUNT");
        problemDetail.setProperty("project", s);
        return Either.left(problemDetail);
    }

}
