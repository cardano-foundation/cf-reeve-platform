package org.cardanofoundation.domain;

import java.util.List;
import java.util.Map;

public record CredentialSerializationData(List<String> prefix, EventDataAndAttachement vcp, EventDataAndAttachement iss, List<Map<String, Object>> acdc) {
    
}
