package org.cardanofoundation.lob.app.funding.domain.csv;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
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
 * row against each {@link FundingCsvFileType}'s full declared column set. Detection requires an
 * <em>exact</em> match (not just "required headers present") — a subset match would let, say, an
 * Events file (which happens to also carry "External Project ID" and "Project Title" columns)
 * satisfy the Projects file's required-header check too. Since users are expected to fill in the
 * downloaded template unmodified, exact-set matching is unambiguous in practice.
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
                .filter(type -> declaredColumns(type.getLineType()).equals(headers))
                .findFirst();
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
