package org.cardanofoundation.domain;

import java.util.Map;

public class CredentialComponents {
    public final Map<String, Object> sad;
        public final Map<String, Object> anc;
        public final Map<String, Object> iss;

        public CredentialComponents(Map<String, Object> sad, Map<String, Object> anc,
                Map<String, Object> iss) {
            this.sad = sad;
            this.anc = anc;
            this.iss = iss;
        }
}
