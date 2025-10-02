import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.core.States.HabState;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;

/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS --enable-preview -source 24
//RUNTIME_OPTIONS --enable-preview

//DEPS org.cardanofoundation:signify:0.1.1
// @formatter:on

public class VerifyVLei {

    private static class DecentralizationInfo {

        public final String prefix;
        public final List<String> oobi;
        public final JsonNode vcp;
        public final JsonNode credentialChainAcdc;

        @JsonCreator
        public DecentralizationInfo(@JsonProperty("prefix") String prefix,
                @JsonProperty("oobi") List<String> oobi, @JsonProperty("vcp") JsonNode vcp,
                @JsonProperty("credentialChainAcdc") JsonNode credentialChainAcdc) {
            this.prefix = prefix;
            this.oobi = oobi;
            this.vcp = vcp;
            this.credentialChainAcdc = credentialChainAcdc;
        }
    }

    private static final String keriUrl = "http://localhost:3901";
    private static final String keriBootUrl = "http://localhost:3903";


    public static void main(String[] args) throws Exception {
        // SignifyClient client = getOrCreateClient("0ADF2TpptgqcDE5IQUF1H"); // was just for testing
        SignifyClient client = getOrCreateClient("");

        DecentralizationInfo info = new ObjectMapper().readValue(new File(
                "/Users/thkammer/Documents/dev/cardano/java/cf-lob-platform/docs/keri/decentralization_info.json"),
                DecentralizationInfo.class);
        info.oobi.forEach(oob -> {
            try {
                Object object = client.oobis().resolve(oob, "");
                client.operations().wait(Operation.fromObject(object));
            } catch (LibsodiumException | IOException | InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        });

        // 404 on this one - if the client is not authorized
        HabState habState = client.identifiers().get(info.prefix);
        Object object = client.credentials().get("EOMEtIExdp9EHzcgTsz2BtwpTXz7Tw08n58nOkXkTcDf", true);

        ObjectMapper mapper = new ObjectMapper();
        String writeValueAsString = mapper.writeValueAsString(object);
        System.out.println(writeValueAsString);
    }

    public static SignifyClient getOrCreateClient(String bran) throws Exception {

        if (bran == null || bran.isEmpty()) {
            bran = Coring.randomPasscode();
        }

        SignifyClient client = new SignifyClient(keriUrl, bran, Salter.Tier.low, keriBootUrl, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        System.out.println("Client: "
                + Map.of("agent", client.getAgent() != null ? client.getAgent().getPre() : null,
                        "controller", client.getController().getPre()));
        return client;
    }

}
