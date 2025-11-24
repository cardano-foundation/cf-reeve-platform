package org.cardanofoundation.domain;

import java.util.List;
import java.util.Map;

public record EventDataAndAttachement(List<Map<String, Object>> events, List<String> attachements) {
    
}
