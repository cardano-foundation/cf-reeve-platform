package org.cardanofoundation.utils;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Constants {
    public static final String vLEIServer =
            "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi";
    public static final String keriUrl = "http://localhost:3901";
    public static final String keriBootUrl = "http://localhost:3903";
    public static final String reeveIdentifierBran = "0ADF2TpptgqcDE5IQUF1H";

    // Schema identifiers
    public static final String QVI_SCHEMA_SAID = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    public static final String LE_SCHEMA_SAID = "ENPXp1vQzRF6JwIuS-mp2U8Uf1MoADoP_GqQ62VsDZWY";
    public static final String REEVE_SCHEMA_SAID = "EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik";

    // Schema URLs
    public static final String QVI_SCHEMA_URL = vLEIServer + "/" + QVI_SCHEMA_SAID;
    public static final String LE_SCHEMA_URL = vLEIServer + "/" + LE_SCHEMA_SAID;
    public static final String REEVE_SCHEMA_URL = vLEIServer + "/" + REEVE_SCHEMA_SAID;

    // Test data
    public static final String provenantLEI = "5493001KJTIIGC8Y1R17";
    public static final String CFLEI = "123456789ABCDEF12345"; // dummy LEI for CF
    public static final String REEVE_LEI = "987654321FEDCBA98765"; // dummy LEI for Reeve

    // Shared instances
    public static final ObjectMapper objectMapper = new ObjectMapper();
    public static final List<String> witnessIds =
            List.of("BBilc4-L3tFUnfM_wJr4S4OJanAv_VmF_dJNN6vkf2Ha",
                    "BLskRTInXnMxWaGqcpSyMgo0nYbalW99cGZESrz3zapM",
                    "BIKKuvBwpmDVA4Ds-EpL5bt9OqPzWPja2LigFYZN2YfX");
}
