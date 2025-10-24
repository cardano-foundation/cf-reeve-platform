package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.IdentifierConfig;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

@Component("Publisher")
@RequiredArgsConstructor
public class PublisherHealth implements HealthIndicator {

    private final AtomicReference<Health> cachedBlockFrostHealth = new AtomicReference<>(Health.unknown().build());

    private final List<BackendService> backendService;
    private final Optional<SignifyClient> signifyClient;
    private final Optional<IdentifierConfig> identifierConfig;

    @PostConstruct
    public void init() {
        updateHealth();
    }

    @Override
    public Health health() {
        Health blockFrostHealth = cachedBlockFrostHealth.get();
        if(blockFrostHealth.getStatus().equals(Status.DOWN)) {
            return blockFrostHealth;
        }
        // TODO waiting for signify client to be more stable
        // if(!checkSignifyClient()) {
        //     return Health.down().withDetail("message", "Signify client is not healthy").build();
        // }
        return Health.up().withDetail("message", "Publisher is healthy").build();
    }

    private boolean checkSignifyClient() {
        try {
            if (signifyClient.isPresent()) {
                if(!identifierConfig.isPresent()) {
                    return false;
                }
                IdentifierConfig idConfig = identifierConfig.get();
                SignifyClient client = signifyClient.get();
                client.identifiers().get(idConfig.getPrefix());
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private boolean checkL1Connection() {
        return backendService.stream().anyMatch(t -> {
            try {
                Result<ProtocolParams> ppResult = t.getEpochService().getProtocolParameters();
                return ppResult.isSuccessful();
            } catch (Exception e) {
                return false;
            }
        });
    }

    // Runs once per day at 3 AM (you can adjust the cron)
    @Scheduled(cron = "0 0 3 * * *")
    public void updateHealth() {
        if (!checkL1Connection()) {
            cachedBlockFrostHealth.set(Health.down().withDetail("message", "No healthy L1 connection").build());
        } else {
            cachedBlockFrostHealth.set(Health.up().withDetail("message", "All systems operational").build());
        }
    }

}
