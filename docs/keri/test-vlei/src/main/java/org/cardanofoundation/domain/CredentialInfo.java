package org.cardanofoundation.domain;

public record CredentialInfo(String id, String schema, ClientAidPair issuer, ClientAidPair holder, CredentialType type) {
}
