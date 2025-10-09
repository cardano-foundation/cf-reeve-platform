package org.cardanofoundation.domain;

import org.cardanofoundation.signify.app.clienting.SignifyClient;
import com.fasterxml.jackson.databind.JsonNode;

public record ClientAidPair(SignifyClient client, Aid aid, boolean rulesNeeded) {
	
}
