package org.cardanofoundation.lob.app.organisation.service.csv;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

@Service
@Slf4j
@RequiredArgsConstructor
public class CsvParser<T> {

    @Value("${lob.csv.delimiter:;}")
    private String delimiter;
    private static final char[] DANGEROUS_PREFIXES = { '=', '+', '-', '@' };
    private final AntiVirusScanner antiVirusScanner;

    public Either<Problem, List<T>> parseCsv(MultipartFile file, Class<T> type){
        if(Objects.isNull(file) || file.isEmpty()){
            String fileIsNullLog = "File is null";
            log.error(fileIsNullLog);
            return Either.left(Problem.builder()
                    .withTitle(fileIsNullLog)
                    .withDetail(fileIsNullLog)
                    .build());
        }
        try {
            return parseCsv(file.getBytes(), type);
        } catch (Exception e) {
            return Either.left(Problem.builder()
                    .withTitle("CSV_PARSING_ERROR")
                    .withDetail(e.getMessage())
                    .build());
        }
    }

    public Either<Problem, List<T>> parseCsv(byte[] file, Class<T> type) {
        try {
            if (!antiVirusScanner.isFileSafe(file)) {
                return Either.left(Problem.builder()
                        .withTitle("MALICIOUS_FILE_DETECTED")
                        .withDetail("The uploaded file contains malicious content and has been rejected.")
                        .build());
            }
            return Either.right(new CsvToBeanBuilder<T>(new InputStreamReader(new ByteArrayInputStream(file)))
                    .withIgnoreLeadingWhiteSpace(true)
                    .withType(type)
                    .withSeparator(delimiter.charAt(0))
                    .withIgnoreEmptyLine(true)
                    .withFieldAsNull(CSVReaderNullFieldIndicator.BOTH)
                    .build().parse()
                    .stream().map(CsvParser::sanitizeBean).toList() // Sanitize each bean and removing malicious prefixes
            );
        } catch (Exception e) {
            return Either.left(Problem.builder()
                    .withTitle("CSV_PARSING_ERROR")
                    .withDetail(e.getMessage())
                    .build());
        }
    }

    public static String sanitizeCell(String value) {
        if (value == null || value.isEmpty()) return value;

        char firstChar = value.charAt(0);
        for (char c : DANGEROUS_PREFIXES) {
            if (firstChar == c) {
                return "'%s".formatted(value); // Prepend with single quote
            }
        }
        return value;
    }

    public static <T> T sanitizeBean(T bean) {
        for (Field field : bean.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType().equals(String.class)) {
                try {
                    String value = (String) field.get(bean);
                    field.set(bean, sanitizeCell(value));
                } catch (IllegalAccessException ignored) {}
            }
        }
        return bean;
    }
}
