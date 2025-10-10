package org.cardanofoundation;

import java.util.Map;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.cesr.Salter;

public class UtilFunctions {
    public static SignifyClient getOrCreateClient(String bran) throws Exception {

        if (bran == null || bran.isEmpty()) {
            bran = Coring.randomPasscode();
        }

        SignifyClient client = new SignifyClient(
                Constants.keriUrl, bran, Salter.Tier.low, Constants.keriBootUrl, null);
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
