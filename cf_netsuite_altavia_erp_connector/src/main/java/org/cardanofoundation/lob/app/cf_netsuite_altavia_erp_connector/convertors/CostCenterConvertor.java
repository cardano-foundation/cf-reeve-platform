package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import java.util.function.Function;
import java.util.regex.Pattern;

import lombok.val;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

public class CostCenterConvertor implements Function<String, Either<ProblemDetail, String>> {

    private static final Pattern pattern = Pattern.compile("(\\d{4}) (.+)");

    @Override
    public Either<ProblemDetail, String> apply(String s) {
        val matcher = pattern.matcher(s);

        if (matcher.find()) {
            return Either.right(matcher.group(1));
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid cost center");
        problemDetail.setTitle("INVALID_COST_CENTER");
        problemDetail.setProperty("cost_center", s);
        return Either.left(problemDetail);

    }

}
