package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

public record L1TransactionCreatorConfig(boolean useIpfs, int metadataLabel, boolean debugStoreOutputTx) {}
