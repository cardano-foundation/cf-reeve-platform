package org.cardanofoundation.lob.app.blockchain_publisher.service;


import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

@Service
@RequiredArgsConstructor
@Slf4j
public class API3MetadataSerialiser {
    private final OrganisationPublicApi organisationPublicApi;
    public static final String VERSION = "1.2";
    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(ReportEntity reportEntity,
                                              long creationSlot) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));
        Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Organisation> byOrganisationId =
                organisationPublicApi.findByOrganisationId(reportEntity.getOrganisationId());
        if (byOrganisationId.isPresent()) {
            Organisation organisation = Organisation.fromOrganisationEntity(byOrganisationId.get());
            globalMetadataMap.put("org", serialiseOrganisation(organisation));
        } else {
            throw new IllegalArgumentException("Organisation not found for id: %s".formatted(reportEntity.getOrganisationId()));
        }
        globalMetadataMap.put("type", "REPORT");
        globalMetadataMap.put("subType", reportEntity.getReportTemplateType().name());
        globalMetadataMap.put("interval", reportEntity.getIntervalType().name());
        globalMetadataMap.put("year", String.valueOf(reportEntity.getYear()));
        globalMetadataMap.put("mode", reportEntity.getDataMode().name());
        globalMetadataMap.put("ver", BigInteger.valueOf(reportEntity.getReportVer()));
        globalMetadataMap.put("period", BigInteger.valueOf(reportEntity.getPeriod()));
        MetadataMap dataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("data", createRecursiveMetadataSection(dataMap, reportEntity.getReportData()));
        return globalMetadataMap;
    }

    /**
     * Serialises reportData recursively. New format embeds fieldOrder with named keys:
     *   - Leaf field value is {"v": normalised_decimal, "o": fieldOrder} — "v" = value, "o" = order.
     *   - Section map contains "_o" for the section's own fieldOrder alongside its children.
     * Old format (plain numeric values / plain section maps) is also handled for backward compatibility.
     */
    private MetadataMap createRecursiveMetadataSection(MetadataMap metadataMap, Map<String, Object> data) {
        data.forEach((key, value) -> {
            if ("_o".equals(key)) {
                // Section's own order: write directly as integer, no snake-case conversion
                metadataMap.put("_o", toBigInteger(value));
                return;
            }

            String snakeKey = toLowerSnakeCase(key);

            if (value == null) {
                log.debug("Null value for key: {}", snakeKey);
            } else if (value instanceof Map<?, ?> childMapRaw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childMap = (Map<String, Object>) childMapRaw;
                if (childMap.containsKey("v")) {
                    // New format leaf: {"v": rawValue, "o": fieldOrder}
                    MetadataMap leafMap = MetadataBuilder.createMap();
                    addLeafValue(leafMap, childMap.get("v"));
                    leafMap.put("_o", toBigInteger(childMap.get("_o")));
                    metadataMap.put(snakeKey, leafMap);
                } else {
                    // Section: recurse — "_o" inside will be written as an integer entry
                    MetadataMap childMetadataMap = MetadataBuilder.createMap();
                    createRecursiveMetadataSection(childMetadataMap, childMap);
                    metadataMap.put(snakeKey, childMetadataMap);
                }
            } else if (value instanceof Integer integerValue) {
                // Old format leaf
                metadataMap.put(snakeKey, BigDecimals.normaliseString(BigDecimal.valueOf(integerValue)));
            } else if (value instanceof Long longValue) {
                // Old format leaf
                metadataMap.put(snakeKey, BigDecimals.normaliseString(BigDecimal.valueOf(longValue)));
            } else if (value instanceof Double doubleValue) {
                // Old format leaf
                metadataMap.put(snakeKey, BigDecimals.normaliseString(BigDecimal.valueOf(doubleValue)));
            } else {
                throw new IllegalArgumentException("Unsupported data type in report data: %s".formatted(value.getClass().getName()));
            }
        });

        return metadataMap;
    }

    private void addLeafValue(MetadataMap leafMap, Object value) {
        if (value == null) {
            log.debug("Null leaf value, skipping");
        } else if (value instanceof Integer integerValue) {
            leafMap.put("v", BigDecimals.normaliseString(BigDecimal.valueOf(integerValue)));
        } else if (value instanceof Long longValue) {
            leafMap.put("v", BigDecimals.normaliseString(BigDecimal.valueOf(longValue)));
        } else if (value instanceof Double doubleValue) {
            leafMap.put("v", BigDecimals.normaliseString(BigDecimal.valueOf(doubleValue)));
        } else if (value instanceof String strValue) {
            leafMap.put("v", strValue);
        } else {
            throw new IllegalArgumentException("Unsupported leaf type in report data: %s".formatted(value.getClass().getName()));
        }
    }

    private BigInteger toBigInteger(Object value) {
        if (value instanceof Integer i) return BigInteger.valueOf(i);
        if (value instanceof Long l) return BigInteger.valueOf(l);
        return BigInteger.ZERO;
    }

    private String toLowerSnakeCase(String input) {
        if (input == null || input.isEmpty()) return input;

        return input
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
                .replaceAll(" ", "_")
                .toLowerCase();
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        Instant now = Instant.now(clock);

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        metadataMap.put("version", VERSION);

        return metadataMap;
    }

    private static MetadataMap serialiseOrganisation(org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation organisation) {
        MetadataMap orgMap = MetadataBuilder.createMap();

        orgMap.put("id", organisation.getId());
        orgMap.put("name", organisation.getName());
        orgMap.put("tax_id_number", organisation.getTaxIdNumber());
        orgMap.put("currency_id", organisation.getCurrencyId());
        orgMap.put("country_code", organisation.getCountryCode());

        return orgMap;
    }

}
