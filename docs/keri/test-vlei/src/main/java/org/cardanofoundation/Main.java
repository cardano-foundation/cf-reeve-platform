package org.cardanofoundation;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.utils.UtilFunctions;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {

    public static void main(String[] args) throws Exception {
        
        CreateVlei.createVlei("credential-data.json");
        
        VerifyVlei.verifyVlei("credential-data.json", "oobis.json");
        BuildTx.buildAndPublishTx("credential-data.json");
        
        
        // ObjectMapper objectMapper = new ObjectMapper();
        // SignifyClient verifierClient = UtilFunctions.getOrCreateClient("");
        // String[] oobis = objectMapper.readValue(new File("oobis.json"), String[].class);
        // List<String> resolved = new java.util.ArrayList<>();
        // for (String oobi : oobis) {
        //     Object resolve = verifierClient.oobis().resolve(oobi, null);
        //     Operation<Object> wait = verifierClient.operations().wait(Operation.fromObject(resolve));
        //     LinkedHashMap<String, Object> response = (LinkedHashMap<String, Object>) wait.getResponse();
        //     resolved.add((String) response.get("i"));
        //     System.out.println(wait.isDone());
        // }

        // Object object = verifierClient.keyStates().query("ELpX1-q7jYWgVxl317Kn_-CSLXfAxIsYJzDNe5_xC-HH", "5");
        // Operation<Object> wait = verifierClient.operations().wait(Operation.fromObject(object));
        // LinkedHashMap<String, Object> response = (LinkedHashMap<String, Object>) wait.getResponse();
        // Object object2 = response.get("d");

        
        // System.out.println("Resolved OOBIs: " + resolved);
    }

}