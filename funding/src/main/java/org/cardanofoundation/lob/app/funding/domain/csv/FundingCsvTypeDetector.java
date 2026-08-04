package org.cardanofoundation.lob.app.funding.domain.csv;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.bean.CsvBindByName;

/**
 * Detects which of the three bulk-import CSV shapes an uploaded file is, by comparing its header
 * row against each {@link FundingCsvFileType}'s full declared column set (mandatory + optional).
 * A type is a candidate only if the uploaded headers are entirely explainable by it (i.e. the
 * uploaded header set is a subset of its declared columns) — this rules out files belonging to a
 * different template. Among candidates, the one with the smallest declared column set wins (the
 * tightest fit), since a file missing some of its own columns still explains itself better via its
 * true, smaller template than via an unrelated, larger one it happens to also be a subset of.
 *
 * <p>Note this deliberately does <em>not</em> require every mandatory column to be present — a
 * file missing a mandatory column still needs to be routed to the right parser so it gets a
 * specific "missing required header" error, rather than being dropped into the generic
 * "unrecognized file" bucket.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundingCsvTypeDetector {

    @Value("${lob.csv.delimiter:;}")
    private String delimiter;

    public Optional<FundingCsvFileType> detect(MultipartFile file) {
        Set<String> headers = readHeaders(file);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(FundingCsvFileType.values())
                .filter(type -> declaredColumns(type.getLineType()).containsAll(headers))
                .min(Comparator.comparingInt(type -> declaredColumns(type.getLineType()).size()));
    }

    private Set<String> readHeaders(MultipartFile file) {
        CSVParser csvParser = new CSVParserBuilder().withSeparator(delimiter.charAt(0)).build();
        try (CSVReader reader = new CSVReaderBuilder(
                new InputStreamReader(new ByteArrayInputStream(file.getBytes())))
                .withCSVParser(csvParser)
                .withSkipLines(0)
                .build()) {
            String[] headerRow = reader.readNext();
            if (headerRow == null) {
                return Set.of();
            }
            return Arrays.stream(headerRow)
                    .map(h -> h.trim().toLowerCase())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to read CSV header row from file {}: {}", file.getOriginalFilename(), e.getMessage());
            return Set.of();
        }
    }

    private Set<String> declaredColumns(Class<?> type) {
        Set<String> columns = new java.util.HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            CsvBindByName bind = field.getAnnotation(CsvBindByName.class);
            if (bind != null) {
                String header = bind.column().isEmpty() ? field.getName() : bind.column();
                columns.add(header.trim().toLowerCase());
            }
        }
        return columns;
    }
}
