package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.chrono.ChronoLocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.responses.TokenReponse;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TransactionDataSearchResult;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteClient {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Getter
    private final String baseUrl;
    private final String tokenUrl;
    private final String privateKeyFilePath;
    private final String certificateId;
    private final String clientId;
    private final Integer recordsPerCall;

    private Optional<String> accessToken = Optional.empty();
    private Optional<LocalDateTime> accessTokenExpiration = Optional.empty();

    private static final String NETSUITE_API_ERROR = "NETSUITE_API_ERROR";

    @PostConstruct
    public void init() {
        log.info("Initializing NetSuite client...");
        log.info("token url: {}", tokenUrl);

        refreshToken();
    }

    private PrivateKey loadPrivateKeyFromFile(String fileName) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File f = new File(fileName);
        String key = Files.readString(f.toPath(), Charset.defaultCharset());

        String privateKeyPEM = key.replace("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll(System.lineSeparator(), "").replace("-----END PRIVATE KEY-----", "");
        byte[] decoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    private String getJwtTokenFromCertifikate() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        PrivateKey privateKey = loadPrivateKeyFromFile(privateKeyFilePath);
        return Jwts.builder()
                .setIssuedAt(new Date())
                .setAudience(tokenUrl)
                .setHeader(Map.of("kid", certificateId, "typ", "jwt"))
                .setExpiration(Date.from(Instant.now().plusSeconds(3600))) // 1-hour expiration
                .claim("scope", "restlets")  // Adding the scope claim
                .claim("iss", clientId)  // Adding the issuer (you can adjust it to your needs)
                .signWith(privateKey, SignatureAlgorithm.PS256)  // Sign with RS256
                .compact();
    }

    private void refreshToken() {
        log.info("Refreshing NetSuite access token...");
        String jwtToken = null;
        try {
            jwtToken = getJwtTokenFromCertifikate();
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Error generating jwt Token: {}", e.getMessage());
            return;
        }
        // Encode parameters
        String requestBody = STR."grant_type=\{URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)}&client_assertion_type=\{URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8)}&client_assertion=\{URLEncoder.encode(jwtToken, StandardCharsets.UTF_8)}";
        // Create the request
        try {
            ResponseEntity<String> entity = restClient.post()
                    .uri(tokenUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);
            if (entity.getStatusCode().is2xxSuccessful()) {
                TokenReponse tokenResponse = null;
                try {
                    tokenResponse = objectMapper.readValue(entity.getBody(), TokenReponse.class);
                } catch (JsonProcessingException e) {
                    log.error("Error parsing JSON response from NetSuite API: {}", e.getMessage());
                }
                accessTokenExpiration = Optional.of(LocalDateTime.now().plusSeconds(tokenResponse.getExpiresIn()));
                accessToken = Optional.of(tokenResponse.getAccessToken());
                log.info("NetSuite access token refreshed successfully...");
            } else {
                log.error("Error refreshing NetSuite access token: {}", entity.getBody());
            }
        } catch (Exception e) {
            log.error("Error refreshing NetSuite access token: {}", e.getMessage());
        }
    }

    public Either<Problem, Optional<List<String>>> retrieveLatestNetsuiteTransactionLines(LocalDate extractionFrom, LocalDate extractionTo) {
        boolean hasMore;
        List<String> lines = new ArrayList<>();
        int start = 0;
        do {
            Either<Problem, Optional<String>> retrievedData = retrieveTransactionLineData(extractionFrom, extractionTo, Optional.of(start));
            if (retrievedData.isLeft()) {
                return Either.left(retrievedData.getLeft());
            } else {
                Optional<String> searchResultString = retrievedData.get();
                if (searchResultString.isEmpty()) {
                    return Either.right(Optional.of(lines));
                } else {
                    try {
                        TransactionDataSearchResult transactionDataSearchResult = objectMapper.readValue(searchResultString.get(), TransactionDataSearchResult.class);
                        lines.add(searchResultString.get());
                        if(transactionDataSearchResult.more()) {
                            hasMore = true;
                            start += recordsPerCall;
                        } else {
                            hasMore = false;
                        }
                    } catch (JsonProcessingException e) {
//                        log.error("Error parsing JSON response from NetSuite API: {}", e.getMessage());
//                        return Either.left(Problem.builder()
//                                .withStatus(Status.INTERNAL_SERVER_ERROR)
//                                .withTitle(NETSUITE_API_ERROR)
//                                .withDetail(e.getMessage())
//                                .build());
                        hasMore = false;
                        log.error("Error parsing JSON response from NetSuite API: {}", e.getMessage());
                    }
                }

            }
        } while(hasMore);
        log.info("Netsuite response success...customerCode:{}, messageCount:{}", 200, lines.size());
        return Either.right(Optional.of(lines));
    }

    private Either<Problem, Optional<String>> retrieveTransactionLineData(LocalDate extractionFrom, LocalDate extractionTo, Optional<Integer> start) {
        ResponseEntity<String> response;
        try {
            response = callForTransactionLinesData(extractionFrom, extractionTo, start);
        } catch (IOException e) {
            return Either.left(Problem.builder()
                    .withStatus(Status.INTERNAL_SERVER_ERROR)
                    .withTitle(NETSUITE_API_ERROR)
                    .withDetail(e.getMessage())
                    .build());
        }

        if (response.getStatusCode().is2xxSuccessful()) {
            final String body = response.getBody();
            log.info("Netsuite response success...customerCode:{}, message:{}", response.getStatusCode().value(), body);

            try {
                JsonNode bodyJsonTree = objectMapper.readTree(body);
                if (bodyJsonTree.has("error")) {
                    int error = bodyJsonTree.get("error").asInt();
                    String text = bodyJsonTree.get("text").asText();
                    log.error("Error api error:{}, message:{}", error, text);

                    if (error == 105) {
                        log.warn("No data to read from NetSuite API...");

                        return Either.right(Optional.empty());
                    }

                    return Either.left(Problem.builder()
                            .withStatus(Status.valueOf(response.getStatusCode().value()))
                            .withTitle(NETSUITE_API_ERROR)
                            .withDetail(String.format("Error customerCode: %d, message: %s", error, text))
                            .build());
                }

                return Either.right(Optional.of(body));
            } catch (JsonProcessingException e) {
                log.error("Error parsing JSON response from NetSuite API: {}", e.getMessage());

                return Either.left(Problem.builder()
                        .withStatus(Status.valueOf(response.getStatusCode().value()))
                        .withTitle(NETSUITE_API_ERROR)
                        .withDetail(e.getMessage())
                        .build());
            }
        }
        if(response.getStatusCode().is1xxInformational()) {
            log.info("Netsuite response success...customerCode:{}, message:{}", response.getStatusCode().value(), response.getBody());
            return Either.right(Optional.empty());
        }

        return Either.left(Problem.builder()
                .withStatus(Status.valueOf(response.getStatusCode().value()))
                .withTitle(NETSUITE_API_ERROR)
                .withDetail(response.getBody())
                .build());
    }

    private ResponseEntity<String> callForTransactionLinesData(LocalDate from, LocalDate to, Optional<Integer> start) throws IOException {
        log.info("Retrieving data from NetSuite...");

        if(LocalDateTime.now().isAfter(ChronoLocalDateTime.from(accessTokenExpiration.orElse(LocalDateTime.MIN)))) {
            refreshToken();
        }
        String baseUrl = this.baseUrl;
        // Remove the recordspercall parameter if it exists, since we are setting it by ourselves
        // This is just to be sure that we are not sending multiple recordspercall parameters
        baseUrl = baseUrl.replaceAll("&recordspercall=\\d+", "");

        baseUrl = baseUrl + "&recordspercall=" + recordsPerCall;
        if (start.isPresent()) {
            baseUrl = baseUrl + "&start=" + start.get();
        }
        String uriString = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("trandate:within", isoFormatDates(from, to)).toUriString();
        log.info("Call to url: {}", uriString);
        RestClient.RequestHeadersSpec<?> uri = restClient.get().uri(uriString);
        accessToken.ifPresent(s -> uri.header("Authorization", STR."Bearer \{s}"));
        return uri.retrieve().toEntity(String.class);
    }

    private String isoFormatDates(LocalDate from, LocalDate to) {
        return String.format("%s,%s", ISO_LOCAL_DATE.format(from), ISO_LOCAL_DATE.format(to));
    }

}
