package org.cardanofoundation;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.cardanofoundation.domain.CredentialSerializationData;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.core.States.HabState;
import org.cardanofoundation.utils.Constants;
import org.cardanofoundation.utils.UtilFunctions;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VerifyVlei {

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static SignifyClient verifierClient;

    public static void verifyVlei(String fileName, String oobiFile) throws Exception {
        CredentialSerializationData csd = null;
        try {
            csd = objectMapper.readValue(new File(fileName), CredentialSerializationData.class);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Prefix: " + csd.prefix());
        System.out.println("VCP Events: " + csd.vcp().events().size());
        System.out.println("VCP Attachments: " + csd.vcp().attachements().size());

        verifierClient = UtilFunctions.getOrCreateClient(""); // creating client with random bran

        List<String> oobis = resolveOobis(oobiFile);


        Optional<HabState> optional = verifierClient.identifiers().get(oobis.getFirst());
        verifyVcps(csd);
        System.out.println("Querying key states to ensure all events are processed by verifier");
        for (String prefix : csd.prefix()) {
            Object query = verifierClient.keyStates().query(prefix, "1");
            verifierClient.operations().wait(Operation.fromObject(query));
        }
        System.out.println("All key states are processed by verifier");

        // Re-resolve schemas before credential verification
        System.out.println("Re-resolving schemas...");
        List<String> schemaOobis = List.of(
            Constants.QVI_SCHEMA_URL,
            Constants.LE_SCHEMA_URL,
            Constants.REEVE_SCHEMA_URL
        );
        
        for (String schemaOobi : schemaOobis) {
            try {
                Object resolve = verifierClient.oobis().resolve(schemaOobi, null);
                Operation<Object> wait = verifierClient.operations().wait(Operation.fromObject(resolve));
                System.out.println("Re-resolved schema: " + schemaOobi + " -> " + wait.isDone());
            } catch (Exception e) {
                System.out.println("Failed to re-resolve schema: " + schemaOobi + " - " + e.getMessage());
            }
        }
        System.out.println("Schema re-resolution completed");

        // Verify each credential in the chain (ISS + ACDC pairs)
        for (int i = 0; i < Math.min(csd.iss().events().size(), csd.acdc().size()); i++) {
            Map<String, Object> issEvent = csd.iss().events().get(i);
            Map<String, Object> acdcEvent = csd.acdc().get(i);
            String issAttachment = csd.iss().attachements().get(i);

            String credentialType = 
                    Constants.QVI_SCHEMA_SAID.equals(acdcEvent.get("s")) ? "QVI"
                    : Constants.LE_SCHEMA_SAID.equals(acdcEvent.get("s")) ? "LE" : "Unknown";
            Serder acdcSerder = new Serder(acdcEvent);
            Serder issSerder = new Serder(issEvent);

            Object credentialVerifyOp =
                    verifierClient.credentials().verify(acdcSerder, issSerder, issAttachment);

            try {
                CompletableFuture<Operation<?>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return verifierClient.operations()
                                .wait(Operation.fromObject(credentialVerifyOp));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                Operation<?> credentialOperation = future.get(30, TimeUnit.SECONDS);
                System.out.println("✓ " + credentialType + " credential #" + (i + 1)
                        + " verification completed successfully");
                System.out
                        .println("  Credential Operation Status: " + credentialOperation.isDone());

                        // verifier can get the credential right after verification
                        if (credentialOperation.isDone()) {
                            // Get and print credential details
                            Object credentialResponse = credentialOperation.getResponse();
                            System.out.println("  Credential Response: " + credentialResponse);
                            
                            // Try to get the credential from the verifier client
                            try {
                                String credentialId = (String) acdcEvent.get("d");
                                Optional<Object> credential = verifierClient.credentials().get(credentialId);
                                if (credential.isPresent()) {
                                    System.out.println("  Retrieved Credential ID: " + credentialId);
                                    System.out.println("  Credential Details: " + credential.get());
                                } else {
                                    System.out.println("  Credential not found in verifier for ID: " + credentialId);
                                }
                            } catch (Exception e) {
                                System.out.println("  Error retrieving credential: " + e.getMessage());
                            }
                        }
            } catch (TimeoutException e) {
                System.out.println("⚠ " + credentialType + " credential #" + (i + 1)
                        + " verification timed out after 30 seconds");
            } catch (Exception e) {
                System.out.println("⚠ " + credentialType + " credential #" + (i + 1)
                        + " verification failed: " + e.getMessage());
            }
        }

        // Verify the credential is available from verifier
        // Optional<Object> verifiedLeCredential =
        //         verifierClient.credentials().get(csd.prefix(), false);
        // if (verifiedLeCredential.isPresent()) {
        //     System.out.println("✓ Verified LE credential is retrievable from verifier");
        // } else {
        //     System.out.println("⚠ Verified LE credential is NOT retrievable from verifier");
        // }
    }

    private static void verifyVcps(CredentialSerializationData csd)
            throws IOException, InterruptedException {
        // Verify each VCP event (registry) in the chain
        for (int i = 0; i < csd.vcp().events().size(); i++) {
            Map<String, Object> vcpEvent = csd.vcp().events().get(i);
            String vcpAttachment = csd.vcp().attachements().get(i);
            Serder vcpSerder = new Serder(vcpEvent);
            Object registryVerifyOp = verifierClient.registries().verify(vcpSerder, vcpAttachment);

            try {
                CompletableFuture<Operation<?>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return verifierClient.operations()
                                .wait(Operation.fromObject(registryVerifyOp));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                Operation<?> registryOperation = future.get(30, TimeUnit.SECONDS);
                System.out.println("✓ VCP #" + (i + 1) + " verification completed successfully");
                System.out.println("  Registry Operation Status: " + registryOperation.isDone());
            } catch (TimeoutException e) {
                System.out
                        .println("⚠ VCP #" + (i + 1) + " verification timed out after 30 seconds");
            } catch (Exception e) {
                System.out.println("⚠ VCP #" + (i + 1) + " verification failed: " + e.getMessage());
            }
        }
    }

    private static List<String> resolveOobis(String oobiFile)
            throws IOException, StreamReadException, DatabindException, InterruptedException {
        String[] oobis = objectMapper.readValue(new File(oobiFile), String[].class);
        List<String> resolved = new java.util.ArrayList<>();
        for (String oobi : oobis) {
            Object resolve = verifierClient.oobis().resolve(oobi, null);
            Operation<Object> wait = verifierClient.operations().wait(Operation.fromObject(resolve));
            LinkedHashMap<String, Object> response = (LinkedHashMap<String, Object>) wait.getResponse();
            resolved.add((String) response.get("i"));
            System.out.println(wait.isDone());
        }
        return resolved;
    }

}
