package org.cardanofoundation.lob.app.blockchain_common.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Request to publish a CIP-170 AUTH_BEGIN transaction, handed to blockchain_publisher.
 *
 * <p>Carries the inputs the label-170 map is built from rather than the finished map: the metadata is
 * assembled on the publisher side via {@code Cip170MetadataFactory}, the same way a document manifest
 * is. That keeps the wire format JSON-friendly and leaves the publisher as the only tier that touches
 * the chain — {@code keri_attestation} has no transaction submitter at all.
 *
 * <p>Nothing here identifies a person: the AID and the credential chain are the identity material the
 * ceremony is publishing on purpose, and the whole point of AUTH_BEGIN is that they go on-chain.
 *
 * @param ceremonyId       the ceremony this publication belongs to; comes back on the ledger update so
 *                         the ceremony's AUTH_BEGIN step can be completed with the resulting tx hash.
 * @param organisationId   the organisation of the ceremony's target — the publisher's dispatcher is
 *                         organisation-scoped, so a row without a resolvable organisation is never
 *                         picked up.
 * @param aid              the KERI AID whose signing authority is being published.
 * @param leafSchemaSaid   schema SAID of the leaf credential, published as the map's {@code s} field.
 * @param reducedCesrChain the reduced CESR credential chain, chunked into the map's {@code c} field.
 * @param authorizedLabels the metadata labels this AID is being authorised for.
 */
@DomainEvent
public record AuthBeginPublishCommand(String ceremonyId,
                                      String organisationId,
                                      String aid,
                                      String leafSchemaSaid,
                                      byte[] reducedCesrChain,
                                      List<Long> authorizedLabels) {
}
