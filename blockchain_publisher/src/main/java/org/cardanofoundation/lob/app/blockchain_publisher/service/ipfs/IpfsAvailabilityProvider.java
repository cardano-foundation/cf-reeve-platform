package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;

@Component
@RequiredArgsConstructor
public class IpfsAvailabilityProvider implements IpfsAvailability {

    private final Optional<IpfsPublisher> ipfsPublisher;

    @Override
    public boolean isAvailable() {
        return ipfsPublisher.isPresent();
    }

}
