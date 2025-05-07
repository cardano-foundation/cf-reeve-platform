package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import static jakarta.persistence.EnumType.STRING;

import java.util.Optional;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Enumerated;

import javax.annotation.Nullable;

import lombok.*;

import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@ToString
@Builder(toBuilder = true)
@AllArgsConstructor
public class L1SubmissionData {

    @Nullable
    private String transactionHash;

    @Nullable
    private Long absoluteSlot;

    @Nullable
    private Long creationSlot;

    @Nullable
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private BlockchainPublishStatus publishStatus;

    @Nullable
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private FinalityScore finalityScore;

    public Optional<String> getTransactionHash() {
        return Optional.ofNullable(transactionHash);
    }

    public Optional<Long> getAbsoluteSlot() {
        return Optional.ofNullable(absoluteSlot);
    }

    public Optional<Long> getCreationSlot() {
        return Optional.ofNullable(creationSlot);
    }

    public Optional<FinalityScore> getFinalityScore() {
        return Optional.ofNullable(finalityScore);
    }

    public Optional<BlockchainPublishStatus> getPublishStatus() {
        return Optional.ofNullable(publishStatus);
    }

    public void setFinalityScore(FinalityScore assuranceLevel) {
        this.finalityScore = assuranceLevel;
    }

    public void setPublishStatus(BlockchainPublishStatus publishStatus) {
        this.publishStatus = publishStatus;
    }

    public void setAbsoluteSlot(Long absoluteSlot) {
        this.absoluteSlot = absoluteSlot;
    }

    public void setCreationSlot(Long creationSlot) {
        this.creationSlot = creationSlot;
    }

    public void setTransactionHash(String transactionHash) {
        this.transactionHash = transactionHash;
    }

    public boolean isFinalized() {
        return finalityScore != null && finalityScore.equals(FinalityScore.FINAL);
    }

}
