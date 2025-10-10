package org.cardanofoundation;

public class Main {

    public static void main(String[] args) throws Exception {
        
        // CreateVlei.createVlei("credential-data.json");
        VerifyVlei.verifyVlei("credential-data.json", "oobis.json");
    }

}