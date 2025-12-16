# QVI Suite Integration Tools

This directory contains jbang scripts which can be used to bootstrap Reeve attestations using a vLEI credential chain issued using the QVI Suite.

## Prerequisites
- JBang installed (`brew install jbang` on macOS)
- Blockfrost API key for Cardano network access
- Cardano wallet mnemonic for transaction signing

## High level outline
1. Run `CreateIdentifier.java`
2. Use the output of `CreateIdentifier.java` to perform the credential issuance from the QVI Suite
3. Run `ReceiveCredentialWithIdentifier.java` to accept the credential issuance, and write it on Cardano for discovery for Reeve attestations

## Creation of the identifier

```bash
jbang docs/keri/CreateIdentifier.java
```

Output:
```
~/dev/cf-reeve-platform on main *1 +1 !4 ❯ jbang docs/keri/CreateIdentifier.java                                                                                                                                                                                                       at 11:21:02
SLF4J: No SLF4J providers were found.
SLF4J: Defaulting to no-operation (NOP) logger implementation
SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details.
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/fergaloconnor/.m2/repository/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

=== Identifier Created Successfully ===
Identifier Prefix (AID): EMX98OU8oVERsvxPm-drm5na9hC3eDbU6giFX5SEioxd
OOBI: http://127.0.0.1:3902/oobi/EMX98OU8oVERsvxPm-drm5na9hC3eDbU6giFX5SEioxd/agent/EF0UV4AVGXl0pmJJXlkzej8mLhFd77Kfvcyj9hIkH3Sr
Passcode: A2lSGsKYW1uXB0jZjsF-w

Save these credentials - you'll need them to access this identifier!
```

Keep a note of the logged `OOBI` and `Passcode`.
- The `OOBI` will be used during the issuance within the QVI Suite
- The `Passcode` will be used as an environment variable when running `ReceiveCredentialWithIdentifier.java`

At this point, controllers of the legal entity KERI identifier in the QVI Suite can perform the issuance of the vLEI Cardano Metadata signer credential.
The credential should be issued to the OOBI printed above.

## Receiving the credential

Any time after issuing the credential from the QVI Suite, the second script may be ran to accept the credential and persist it on Cardano.
A number of environment variables must be set for this to work:
- The passcode printed above - this is a seed for secret key material and should be kept safe.
- The LEI number
- The Cardano mnemonic, which should have a funded address to sign the transaction submitted on-chain
- A Blockfrost project ID which can be used to submit the transaction to a node

```bash
export PASSCODE=A2lSGsKYW1uXB0jZjsF-w
export LEI=5493001KJTIIGC8Y1R12
export MNEMONIC="test test test test test test test test test test test test test test test test test test test test test test test sauce"
export BLOCKFROST_PROJECT_ID=<blockfrostkey>

jbang docs/keri/ReceiveCredentialWithIdentifier.java
```

## Advanced - re-submit

In case there is an issue submitting the transaction onto Cardano, we may need to retry after receiving the credential.
Using the same environment variables as above, `jbang docs/keri/advanced/PublishExistingCredential.java` may be used to fetch the already accepted credential and publish it on-chain again.
You will be prompted `yes/no` for final submission if a credential is found.
