package org.cardanofoundation.domain;

import java.util.List;
import java.util.Map;

public record CredentialData(String prefix, EventDataAndAttachement vcp, EventDataAndAttachement iss, List<Map<String, Object>> acdc) {
    
}
