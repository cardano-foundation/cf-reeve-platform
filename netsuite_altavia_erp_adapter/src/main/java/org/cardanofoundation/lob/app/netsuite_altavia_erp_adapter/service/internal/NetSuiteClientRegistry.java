package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Clock clock;

    /**
     * Cached clients expire so a replica that did not consume the configuration event still picks
     * up a credential change. Eviction only reaches the one replica whose consumer handled the
     * event — the configuration topic uses a single shared group — so without an expiry the other
     * replicas would serve revoked credentials indefinitely.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record CachedClient(NetSuiteClient client, Instant builtAt) {

        boolean isFresh(Clock clock) {
            return Duration.between(builtAt, Instant.now(clock)).compareTo(CACHE_TTL) < 0;
        }
    }

    private final Map<String, CachedClient> cache = new ConcurrentHashMap<>();

    /**
     * Incremented on every eviction. A build that started before an eviction must not publish its
     * now-stale client over the freshly rebuilt one, which would pin the cache to revoked
     * credentials until the next configuration change.
     */
    private final Map<String, Long> generations = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Either<ProblemDetail, NetSuiteClient> forOrganisation(String organisationId) {
        CachedClient cached = cache.get(organisationId);
        if (cached != null && cached.isFresh(clock)) {
            return Either.right(cached.client());
        }

        long generation = generations.getOrDefault(organisationId, 0L);

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

        // Publish only if no eviction happened while we were loading and decrypting. If one did,
        // this client was built from superseded configuration; the caller still gets it for the
        // operation already in flight, but it must not become the cached one.
        cache.compute(organisationId, (key, present) -> {
            if (generations.getOrDefault(key, 0L) != generation) {
                log.info("Discarding NetSuite client for organisation {} built against superseded configuration", key);
                return present;
            }

            return new CachedClient(client, Instant.now(clock));
        });

        return Either.right(client);
    }

    public void evict(String organisationId) {
        // Bump the generation before removing, so a build already in flight cannot re-publish.
        generations.merge(organisationId, 1L, Long::sum);

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
