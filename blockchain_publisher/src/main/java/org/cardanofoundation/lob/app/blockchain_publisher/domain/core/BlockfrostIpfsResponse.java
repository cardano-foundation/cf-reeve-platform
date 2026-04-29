package org.cardanofoundation.lob.app.blockchain_publisher.domain.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonProperty;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class BlockfrostIpfsResponse {

    private String name;
    @JsonProperty("ipfs_hash")
    private String ipfsHash;
    private String size;

}
