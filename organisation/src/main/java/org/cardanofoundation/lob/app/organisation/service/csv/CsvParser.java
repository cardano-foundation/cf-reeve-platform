package org.cardanofoundation.lob.app.organisation.service.csv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import com.opencsv.exceptions.CsvValidationException;
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

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
                    .withStatus(Status.BAD_REQUEST)
                    .withTitle("FILE_IS_EMPTY_ERROR")
                    .withDetail(fileIsNullLog)
                    .build());
        }
        try {
            return parseCsv(file.getBytes(), type);
        } catch (Exception e) {
            return Either.left(Problem.builder()
                    .withStatus(Status.BAD_REQUEST)
                    .withTitle("CSV_PARSING_ERROR")
                    .withDetail(e.getMessage())
                    .build());
        }
    }

    private Either<Problem, Void> checkHeaders(byte[] file, Class<T> type) throws CsvValidationException, IOException {
        CSVReader headerReader = new CSVReaderBuilder(new InputStreamReader(new ByteArrayInputStream(file)))
                .withSkipLines(0)
                .build();

        String[] csvHeader = headerReader.readNext();
        Set<String> headerSet = csvHeader == null
                ? Set.of()
                : Arrays.stream(csvHeader).map(String::trim).collect(Collectors.toSet());

        Set<String> requiredHeaders = getRequiredHeaders(type);
        Set<String> missingHeaders = requiredHeaders.stream()
                .filter(req -> headerSet.stream().noneMatch(h -> h.equalsIgnoreCase(req)))
                .collect(Collectors.toSet());

        if (!missingHeaders.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("CSV_HEADER_ERROR")
                    .withStatus(Status.BAD_REQUEST)
                    .withDetail("Missing required headers: " + String.join(", ", missingHeaders))
                    .build());
        }
        return Either.right(null); // No missing headers, return success
    }

    private <T> Set<String> getRequiredHeaders(Class<T> type) {
        Set<String> requiredHeaders = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            CsvBindByName bind = field.getAnnotation(CsvBindByName.class);
            if (bind != null) {
                String header = bind.column().isEmpty() ? field.getName() : bind.column();
                requiredHeaders.add(header);
            }
        }
        // adding the required headers of the superclass as well
        Optional.ofNullable(type.getSuperclass())
                .ifPresent(superClass -> requiredHeaders.addAll(getRequiredHeaders(superClass)));
        return requiredHeaders;
    }

    public Either<Problem, List<T>> parseCsv(byte[] file, Class<T> type) {
        try {
            if (!antiVirusScanner.isFileSafe(file)) {
                return Either.left(Problem.builder()
                        .withTitle("MALICIOUS_FILE_DETECTED")
                        .withStatus(Status.BAD_REQUEST)
                        .withDetail("The uploaded file contains malicious content and has been rejected.")
                        .build());
            }
            try {
                Either<Problem, Void> headerCheck = checkHeaders(file, type);
                if (headerCheck.isLeft()) {
                    return Either.left(headerCheck.getLeft());
                }
            } catch (CsvValidationException | IOException e) {
                return Either.left(Problem.builder()
                        .withTitle("CSV_HEADER_ERROR")
                        .withStatus(Status.BAD_REQUEST)
                        .withDetail(e.getMessage())
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
                    .withStatus(Status.BAD_REQUEST)
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
                } catch (IllegalAccessException ignored) {
                    log.debug("Failed to access field {} in bean {}: {}", field.getName(), bean.getClass().getSimpleName(), ignored.getMessage());
                }
            }
        }
        return bean;
    }
}
