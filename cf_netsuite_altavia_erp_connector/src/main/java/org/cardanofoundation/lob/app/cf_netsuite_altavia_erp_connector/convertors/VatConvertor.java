package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import java.util.function.Function;

import lombok.val;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

public class VatConvertor implements Function<String, Either<ProblemDetail, String>> {

    @Override
    public Either<ProblemDetail, String> apply(String s) {
        val split = s.split(":");

        if (split.length == 1) {
            return Either.right(s);
        }

        if (split.length == 2) {
            return Either.right(split[1].trim());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid vat code");
        problemDetail.setTitle("INVALID_VAT");
        problemDetail.setProperty("vat", s);
        return Either.left(problemDetail);
    }

}
