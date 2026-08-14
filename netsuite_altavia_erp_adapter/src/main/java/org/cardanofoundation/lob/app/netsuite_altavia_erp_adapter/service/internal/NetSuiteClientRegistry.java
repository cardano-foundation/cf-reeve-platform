package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

/**
 * Resolves and caches one {@link NetSuiteClient} per organisation. Each cached client keeps its
 * own OAuth token, so tenants never share credentials or tokens.
 * <p>
 * Entries are evicted when a configuration changes, so a credential update takes effect without
 * restarting the pod.
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteClientRegistry {

    public static final String CONFIGURATION_NOT_FOUND = "NETSUITE_CONFIGURATION_NOT_FOUND";
    public static final String CONFIGURATION_UNREADABLE = "NETSUITE_CONFIGURATION_UNREADABLE";

    private final NetSuiteConfigRepository netSuiteConfigRepository;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Integer recordsPerCall;

    private final Map<String, NetSuiteClient> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Either<ProblemDetail, NetSuiteClient> forOrganisation(String organisationId) {
        NetSuiteClient cached = cache.get(organisationId);
        if (cached != null) {
            return Either.right(cached);
        }

        Optional<NetSuiteConfigEntity> configM = netSuiteConfigRepository.findById(organisationId);
        if (configM.isEmpty()) {
            log.warn("No NetSuite configuration for organisation {}", organisationId);

            return Either.left(problem(HttpStatus.PRECONDITION_REQUIRED, CONFIGURATION_NOT_FOUND,
                    "No NetSuite configuration exists for organisation %s. An administrator must create one before ingestion can run."
                            .formatted(organisationId)));
        }

        NetSuiteConfigEntity config = configM.orElseThrow();

        String pem;
        try {
            pem = secretCipher.decrypt(config.getPrivateKeyEncrypted());
        } catch (RuntimeException e) {
            log.error("Cannot decrypt NetSuite private key for organisation {}: {}",
                    organisationId, e.getMessage());

            return Either.left(problem(HttpStatus.INTERNAL_SERVER_ERROR, CONFIGURATION_UNREADABLE,
                    "Stored NetSuite credentials for organisation %s cannot be decrypted. The configuration encryption key may have changed."
                            .formatted(organisationId)));
        }

        NetSuiteClient client = new NetSuiteClient(objectMapper, restClient,
                config.getBaseUrl(), config.getTokenUrl(), pem,
                config.getCertificateId(), config.getClientId(), recordsPerCall);

        // computeIfAbsent would re-run the (expensive) build under contention; a racing thread
        // simply wins here and both callers get a usable client.
        cache.put(organisationId, client);

        return Either.right(client);
    }

    public void evict(String organisationId) {
        if (cache.remove(organisationId) != null) {
            log.info("Evicted cached NetSuite client for organisation {}", organisationId);
        }
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        return problem;
    }

}
