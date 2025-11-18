package org.cardanofoundation.lob.app.accounting_reporting_core.service.csv;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportCsvLine;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;

@Slf4j
@RequiredArgsConstructor
@Service
public class CsvReportMapper {

    /**
     * Helper class to track both the matched value and the original CSV field key
     */
    private static class MatchResult {
        final String originalKey;
        final BigDecimal value;

        MatchResult(String originalKey, BigDecimal value) {
            this.originalKey = originalKey;
            this.value = value;
        }
    }

    public Either<Problem, CreateReportView> mapCsvLinesToReportEntity(List<ReportCsvLine> csvLines, String organisationId) {
        if(csvLines.isEmpty()) {
            Problem problem = Problem.builder()
                    .withTitle(Constants.CSV_PARSING_ERROR)
                    .withDetail("CSV lines are empty, cannot map to CreateReportView.")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
            return Either.left(problem);
        }
        ReportType reportType = null;
        try {
                reportType = ReportType.valueOf(csvLines.getFirst().getReport());
            } catch (IllegalArgumentException e) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.INVALID_REPORT_TYPE)
                        .withDetail(Constants.REPORT_TYPE_IS_NOT_VALID_EXPECTED_BALANCE_SHEET_OR_INCOME_STATEMENT_BUT_GOT_S.formatted(
                                csvLines.getFirst().getReport()))
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_TYPE, csvLines.getFirst().getReport())
                        .build();
                return Either.left(problem);
        }
        try {
            IntervalType.valueOf(csvLines.getFirst().getIntervalType());
        } catch(IllegalArgumentException e) {
            Problem problem = Problem.builder()
                    .withTitle(Constants.INVALID_INTERVAL_TYPE)
                    .withDetail(Constants.INTERVAL_TYPE_IS_NOT_VALID_EXPECTED_QUARTER_OR_YEAR_BUT_GOT_S.formatted(
                            csvLines.getFirst().getIntervalType()))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.INTERVAL_TYPE, csvLines.getFirst().getIntervalType())
                    .build();
            return Either.left(problem);
        }

        switch (reportType) {
            // These two functions are quite doubled, but will be removed once we have the generic report module
            case BALANCE_SHEET:
                return mapBalanceSheetCsvLinesToReportEntity(csvLines, organisationId);
            case INCOME_STATEMENT:
                return mapIncomeStatementCsvLinesToReportEntity(csvLines, organisationId);
            default:
                return Either.left(Problem.builder()
                        .withTitle(Constants.UNSUPPORTED_REPORT_TYPE)
                        .withDetail("The report type '%s' is not supported.".formatted(reportType))
                        .withStatus(Status.BAD_REQUEST)
                        .build());
        }
    }

    private Either<Problem, CreateReportView> mapBalanceSheetCsvLinesToReportEntity(List<ReportCsvLine> csvLines, String organisationId) {
        try {
            BalanceSheetData balanceSheetData = new BalanceSheetData();
            Map<String, BigDecimal> fieldValues = new HashMap<>();

            // Collect all field values from CSV lines
            for (ReportCsvLine line : csvLines) {
                fieldValues.put(line.getField(), BigDecimal.valueOf(line.getAmount()));
            }

            // Map values to BalanceSheetData using reflection
            List<String> unmappedFields = new java.util.ArrayList<>();
            mapFieldsToObject(balanceSheetData, fieldValues, "", unmappedFields);

            // Check if there are any unmapped fields
            if (!unmappedFields.isEmpty()) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("The following fields could not be mapped to Balance Sheet structure: " + String.join(", ", unmappedFields))
                        .withStatus(Status.BAD_REQUEST)
                        .with("unmappedFields", unmappedFields)
                        .build();
                return Either.left(problem);
            }



            return Either.right(CreateReportView.builder()
                    .organisationId(organisationId)
                    .balanceSheetData(Optional.of(balanceSheetData))
                    .incomeStatementData(Optional.empty())
                    .build());
        } catch (Exception e) {
            log.error("Error mapping Balance Sheet CSV lines to ReportEntity", e);
            Problem problem = Problem.builder()
                    .withTitle(Constants.CSV_PARSING_ERROR)
                    .withDetail("Failed to map Balance Sheet CSV lines: " + e.getMessage())
                    .withStatus(Status.INTERNAL_SERVER_ERROR)
                    .build();
            return Either.left(problem);
        }
    }

    private Either<Problem, CreateReportView> mapIncomeStatementCsvLinesToReportEntity(List<ReportCsvLine> csvLines, String organisationId) {
        try {
            IncomeStatementData incomeStatementData = new IncomeStatementData();
            Map<String, BigDecimal> fieldValues = new HashMap<>();

            // Collect all field values from CSV lines
            for (ReportCsvLine line : csvLines) {
                fieldValues.put(line.getField(), BigDecimal.valueOf(line.getAmount()));
            }

            // Map values to IncomeStatementData using reflection
            List<String> unmappedFields = new java.util.ArrayList<>();
            mapFieldsToObject(incomeStatementData, fieldValues, "", unmappedFields);

            // Check if there are any unmapped fields
            if (!unmappedFields.isEmpty()) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("The following fields could not be mapped to Income Statement structure: " + String.join(", ", unmappedFields))
                        .withStatus(Status.BAD_REQUEST)
                        .with("unmappedFields", unmappedFields)
                        .build();
                return Either.left(problem);
            }

            return Either.right(CreateReportView.builder()
                    .organisationId(organisationId)
                    .incomeStatementData(Optional.of(incomeStatementData))
                    .balanceSheetData(Optional.empty())
                    .build());
        } catch (Exception e) {
            log.error("Error mapping Income Statement CSV lines to ReportEntity", e);
            Problem problem = Problem.builder()
                    .withTitle(Constants.CSV_PARSING_ERROR)
                    .withDetail("Failed to map Income Statement CSV lines: " + e.getMessage())
                    .withStatus(Status.INTERNAL_SERVER_ERROR)
                    .build();
            return Either.left(problem);
        }
    }

    /**
     * Recursively maps field values to nested objects using reflection.
     * Converts field names from snake_case or "Readable Name" format to camelCase.
     */
    private void mapFieldsToObject(Object targetObject, Map<String, BigDecimal> fieldValues, String pathPrefix, List<String> unmappedFields) throws Exception {
        mapFieldsToObjectWithTracking(targetObject, fieldValues, pathPrefix, unmappedFields, new HashMap<>());
    }

    /**
     * Internal method that recursively maps fields while tracking which CSV fields have been mapped.
     */
    private void mapFieldsToObjectWithTracking(Object targetObject, Map<String, BigDecimal> fieldValues, String pathPrefix, List<String> unmappedFields, Map<String, Boolean> globalFieldsMapped) throws Exception {
        Class<?> clazz = targetObject.getClass();

        // Initialize tracking map at root level
        if (pathPrefix.isEmpty()) {
            for (String csvField : fieldValues.keySet()) {
                globalFieldsMapped.put(csvField, false);
            }
        }

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();
            String fieldName = field.getName();

            // Skip serialVersionUID and other static fields
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (fieldType.equals(BigDecimal.class)) {
                // This is a leaf field - try to find matching value
                MatchResult matchResult = findMatchingValueWithKey(fieldValues, pathPrefix, fieldName);
                if (matchResult != null && matchResult.value != null) {
                    field.set(targetObject, matchResult.value);
                    globalFieldsMapped.put(matchResult.originalKey, true);
                }
            } else if (!fieldType.isPrimitive() && !fieldType.getName().startsWith("java.")) {
                // This is a nested object - create instance and recurse
                Object nestedObject = fieldType.getDeclaredConstructor().newInstance();
                String newPrefix = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;
                mapFieldsToObjectWithTracking(nestedObject, fieldValues, newPrefix, unmappedFields, globalFieldsMapped);

                // Only set the nested object if it has at least one non-null field
                if (hasNonNullFields(nestedObject)) {
                    field.set(targetObject, nestedObject);
                }
            }
        }

        // After processing all fields, collect unmapped fields (only at root level)
        if (pathPrefix.isEmpty()) {
            for (Map.Entry<String, Boolean> entry : globalFieldsMapped.entrySet()) {
                if (!entry.getValue()) {
                    unmappedFields.add(entry.getKey());
                }
            }
        }
    }

    /**
     * Finds a matching value for a field by trying different naming conventions.
     * Returns both the matched value and the original CSV key.
     * Supports multiple naming conventions in order:
     * - Direct camelCase match (e.g., "cryptoAssets")
     * - snake_case (e.g., "crypto_assets")
     * - UPPER_SNAKE_CASE (e.g., "CRYPTO_ASSETS")
     * - "Readable Name" with spaces (e.g., "Crypto Assets")
     * - lowercase with spaces (e.g., "crypto assets")
     * - Path-based matching (e.g., "assets.currentAssets.cryptoAssets")
     * - Path-based with underscores (e.g., "assets_currentAssets_cryptoAssets")
     * - Path-based UPPER_SNAKE_CASE (e.g., "ASSETS_CURRENT_ASSETS_CRYPTO_ASSETS")
     *
     * @param fieldValues Map of all field values
     * @param pathPrefix Current object path
     * @param fieldName Field name in camelCase
     * @return MatchResult containing the original key and value, or null if not found
     */
    private MatchResult findMatchingValueWithKey(Map<String, BigDecimal> fieldValues, String pathPrefix, String fieldName) {
        // Generate possible field name variations
        String camelCase = fieldName;
        String snakeCase = camelCaseToSnakeCase(fieldName);
        String upperSnakeCase = snakeCase.toUpperCase();
        String readableName = camelCaseToReadable(fieldName);
        String lowerCaseReadable = readableName.toLowerCase();

        // Generate path-based variations
        String fullPath = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;
        String fullPathSnakeCase = camelCaseToSnakeCase(fullPath.replace(".", "_"));
        String fullPathUpperSnakeCase = fullPathSnakeCase.toUpperCase();
        String fullPathReadable = camelCaseToReadable(fullPath.replace(".", " "));

        // Try direct matches with all variations
        for (String key : fieldValues.keySet()) {
            String normalizedKey = key.trim();
            String normalizedKeyLower = normalizedKey.toLowerCase();

            // Try exact matches with different formats (case-insensitive)
            if (normalizedKey.equalsIgnoreCase(camelCase) ||
                normalizedKey.equalsIgnoreCase(snakeCase) ||
                normalizedKey.equals(upperSnakeCase) ||
                normalizedKey.equalsIgnoreCase(readableName) ||
                normalizedKeyLower.equals(lowerCaseReadable)) {
                return new MatchResult(key, fieldValues.get(key));
            }

            // Try path-based matching with dots
            if (normalizedKey.equalsIgnoreCase(fullPath) ||
                normalizedKey.replace("_", ".").equalsIgnoreCase(fullPath.replace("_", "."))) {
                return new MatchResult(key, fieldValues.get(key));
            }

            // Try path-based matching with underscores
            if (normalizedKey.equalsIgnoreCase(fullPathSnakeCase) ||
                normalizedKey.equals(fullPathUpperSnakeCase)) {
                return new MatchResult(key, fieldValues.get(key));
            }

            // Try path-based with spaces (readable format)
            if (normalizedKey.equalsIgnoreCase(fullPathReadable)) {
                return new MatchResult(key, fieldValues.get(key));
            }

            // Try removing all separators and comparing
            String keyNoSeparators = normalizedKeyLower.replaceAll("[_\\s.-]", "");
            String fieldNoSeparators = fullPath.toLowerCase().replaceAll("[_\\s.-]", "");
            if (keyNoSeparators.equals(fieldNoSeparators)) {
                return new MatchResult(key, fieldValues.get(key));
            }
        }

        return null;
    }

    /**
     * Converts camelCase to snake_case.
     * Example: cryptoAssets -> crypto_assets
     */
    private String camelCaseToSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Converts camelCase to Readable Name with spaces.
     * Example: cryptoAssets -> Crypto Assets
     */
    private String camelCaseToReadable(String camelCase) {
        String result = camelCase.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }

    /**
     * Checks if an object has at least one non-null field.
     * Used to determine if nested objects should be set.
     */
    private boolean hasNonNullFields(Object obj) throws IllegalAccessException {
        Class<?> clazz = obj.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.get(obj) != null) {
                return true;
            }
        }
        return false;
    }

}
