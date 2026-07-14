package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolveRecipientsRequest extends BaseRequest {

    @NotEmpty(message = "At least one recipient is required.")
    private List<String> recipientAccountIds;

    /**
     * Optional: which of the CALLER'S OWN keys in this organisation get a slot — i.e. which of their
     * devices can reopen the document later ("choose a key to encrypt with", contract §0 step 4.2).
     * Null or empty means all of them, which is the right default and the previous behaviour. It can
     * never mean "none": the sender is always a recipient of their own document.
     */
    @Nullable
    private List<String> senderKeyIds;
}
