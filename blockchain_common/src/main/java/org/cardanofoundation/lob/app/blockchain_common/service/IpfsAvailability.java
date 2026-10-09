package org.cardanofoundation.lob.app.blockchain_common.service;

/**
 * Cross-module capability probe: is an IPFS publisher configured in this deployment?
 * Implemented by blockchain_publisher; consumed by document_vault to gate publishing
 * ("no IPFS -> no document publishing"). Lives here so the vault never depends on the publisher.
 */
public interface IpfsAvailability {

    boolean isAvailable();
}
