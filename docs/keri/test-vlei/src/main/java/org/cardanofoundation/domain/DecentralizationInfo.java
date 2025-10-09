package org.cardanofoundation.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record DecentralizationInfo(String prefix, String[] oobi, JsonNode[] vcp, JsonNode credentialChainAcdc) {
    
}
