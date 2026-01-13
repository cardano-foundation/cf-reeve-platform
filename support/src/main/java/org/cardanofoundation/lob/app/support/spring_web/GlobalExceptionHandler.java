package org.cardanofoundation.lob.app.support.spring_web;

import java.util.Arrays;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleEnumMappingError(HttpMessageNotReadableException ex) {

        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException invalidFormatException) {

            if (invalidFormatException.getTargetType().isEnum()) {
                String fieldName = invalidFormatException.getPath()
                        .stream()
                        .map(ref -> ref.getFieldName())
                        .findFirst()
                        .orElse("unknown field");

                String invalidValue = invalidFormatException.getValue().toString();

                String message = String.format(
                        "Invalid value '%s' for field '%s'. Allowed values are: %s",
                        invalidValue,
                        fieldName,
                        Arrays.toString(invalidFormatException.getTargetType().getEnumConstants())
                );

                return ResponseEntity.badRequest().body(message);
            }
        }

        return ResponseEntity.badRequest().body("Malformed request body");
    }
}
