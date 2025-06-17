package org.cardanofoundation.lob.app.accounting_reporting_core.service;


import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;

@Component
public class ValidateIngestionResponseWaiter {

    private final Map<String, CompletableFuture<ValidateIngestionResponseEvent>> futures = new ConcurrentHashMap<>();

    public CompletableFuture<ValidateIngestionResponseEvent> createFuture(String correlationId) {
        CompletableFuture<ValidateIngestionResponseEvent> future = new CompletableFuture<>();
        futures.put(correlationId, future);
        return future;
    }

    public void complete(String correlationId, ValidateIngestionResponseEvent response) {
        CompletableFuture<ValidateIngestionResponseEvent> future = futures.remove(correlationId);
        if (future != null) {
            future.complete(response);
        }
    }
}
