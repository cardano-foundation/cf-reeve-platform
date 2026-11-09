package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

/**
 * Who to encrypt to. Recipients come in two kinds and are named separately rather than as one opaque id
 * list: a colleague is an account, a contact is an addressbook entry, and "no key bound for account X"
 * and "no such addressbook entry Y" are different problems that should read differently. Collapsing them
 * would put back, at the API, the ambiguity that splitting the tables removed in storage.
 *
 * Both lists are optional individually; at least one must be non-empty.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolveRecipientsRequest extends BaseRequest {

    /** Keycloak subs of colleagues — the {@code recipientId} of an ORG_KEY from the recipients listing. */
    @Nullable
    private List<String> recipientAccountIds;

    /** Addressbook entry ids — the {@code recipientId} of an ADDRESSBOOK_ENTRY from the same listing. */
    @Nullable
    private List<String> recipientEntryIds;

    /**
     * Optional: which of the CALLER'S OWN keys in this organisation get a slot — i.e. which of their
     * devices can reopen the document later.
     * Null or empty means all of them, which is the right default and the previous behaviour. It can
     * never mean "none": the sender is always a recipient of their own document.
     */
    @Nullable
    private List<String> senderKeyIds;

    @JsonIgnore
    @AssertTrue(message = "At least one recipient is required.")
    public boolean isAnyRecipientNamed() {
        return !CollectionUtils.isEmpty(recipientAccountIds) || !CollectionUtils.isEmpty(recipientEntryIds);
    }

    public List<String> accountIdsOrEmpty() {
        return recipientAccountIds == null ? List.of() : recipientAccountIds;
    }

    public List<String> entryIdsOrEmpty() {
        return recipientEntryIds == null ? List.of() : recipientEntryIds;
    }
}
