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
        BuildTx.buildAndPublishTx("credential-data.json");
    }

}