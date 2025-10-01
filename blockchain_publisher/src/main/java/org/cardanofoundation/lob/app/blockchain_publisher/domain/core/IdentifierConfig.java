package org.cardanofoundation.lob.app.blockchain_publisher.domain.core;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IdentifierConfig {

    private String prefix;
    private String name;
    private String role;
}
