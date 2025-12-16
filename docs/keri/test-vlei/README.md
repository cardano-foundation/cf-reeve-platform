# Test vLEI Project

This project demonstrates the complete end-to-end flow for creating and managing vLEI (verifiable Legal Entity Identifier) credentials using KERI and anchoring them on the Cardano blockchain. It uses demo credentials and test environments for development and testing purposes.

## Overview

This is a comprehensive demo implementation that showcases:
- Setting up KERI clients and identifiers
- Creating credential registries
- Issuing vLEI credentials (QVI, LE, and custom Reeve credentials)
- IPEX (Issuance and Presentation EXchange) protocol flows
- Anchoring credentials on Cardano blockchain
- Verifying vLEI credentials

## Project Structure

```
test-vlei/
├── src/main/java/org/cardanofoundation/
│   ├── BuildTx.java              # Cardano transaction building
│   ├── CreateVlei.java            # vLEI credential creation flow
│   ├── Main.java                  # Main entry point
│   ├── VerifyVlei.java            # Credential verification
│   ├── domain/                    # Domain models
│   │   ├── Aid.java
│   │   ├── ClientAidPair.java
│   │   ├── CredentialComponents.java
│   │   ├── CredentialInfo.java
│   │   ├── CredentialSerializationData.java
│   │   ├── CredentialType.java
│   │   ├── EventDataAndAttachement.java
│   │   ├── Notification.java
│   │   └── ParentCredentialInfo.java
│   └── utils/                     # Utility classes
│       ├── Constants.java
│       └── UtilFunctions.java
├── credential-data.json           # Credential configuration
├── oobis.json                     # OOBI (Out-of-Band Introduction) data
├── summit-credential.json         # Summit-specific credential data
├── summit-oobis.json              # Summit-specific OOBIs
├── pom.xml                        # Maven project configuration
└── README.md                      # This file
```

## Prerequisites

- Java 24+
- Maven 3.6+
- Access to KERI services (Keria staging environment)
- Cardano backend service (local devnet or testnet)
- Cardano wallet with sufficient ADA for transactions

## Configuration Files

### credential-data.json
Contains the configuration for credentials to be issued, including schema SAIDs, issuer AIDs, and credential attributes.

### oobis.json
Contains Out-of-Band Introduction (OOBI) data for resolving KERI identifiers and schemas. This includes URLs for QVI, LE, and custom schema resolution.

### summit-credential.json & summit-oobis.json
Summit-specific configurations for demonstration purposes.

## Key Components

### CreateVlei.java
Main credential creation flow that:
1. Sets up issuer and receiver KERI clients
2. Creates credential registries
3. Resolves schemas and OOBIs
4. Issues credentials through IPEX protocol
5. Handles credential chain (QVI → LE → custom credentials)

### VerifyVlei.java
Verifies vLEI credentials by:
1. Parsing credential chains from CESR format
2. Validating credential signatures
3. Checking credential status in registries
4. Verifying credential schema compliance

### BuildTx.java
Handles Cardano transaction construction with:
1. Metadata attachment (label 170 for KERI data)
2. Transaction signing
3. Submission to Cardano network

## Credential Flow

The project demonstrates a complete credential chain:

```
Root of Trust (External)
    ↓
QVI (Qualified vLEI Issuer) Credential
    ↓
LE (Legal Entity) Credential
    ↓
Custom Reeve Credential
```

Each credential in the chain:
- Has a unique SAID (Self-Addressing Identifier)
- References its parent credential
- Is signed by the issuer's KERI identifier
- Is anchored in a KERI credential registry
- Can be optionally anchored on Cardano blockchain

## IPEX Protocol

The project implements the IPEX (Issuance and Presentation EXchange) protocol:

1. **Offer**: Issuer offers a credential to recipient
2. **Grant**: Issuer grants the credential
3. **Admit**: Recipient admits (accepts) the credential
4. **Notification**: System notifies participants of credential status changes

## Cardano Integration

Credentials are anchored on Cardano using metadata label `170`:

```json
{
  "170": {
    "t": "AUTH_BEGIN",
    "s": "<CESR_ENCODED_CREDENTIAL_CHAIN>",
    "v": {
      "v": "1.0",
      "k": "KERI10",
      "a": "ACDC10"
    },
    "m": {
      "l": ["1447"],
      "LEI": "<LEGAL_ENTITY_IDENTIFIER>"
    }
  }
}
```

## Testing with Demo Credentials

This project uses demo credentials for testing:
- Demo QVI and LE credentials are pre-configured
- Test KERI identifiers are created dynamically
- Local Cardano devnet can be used for transaction testing
- All credentials use test schemas and registries

## Development Notes

### Constants.java
Update this file with your environment-specific values:
- KERI service URLs
- Cardano backend URLs
- Network configuration (mainnet/testnet/devnet)
- API keys and credentials

### Security Considerations

⚠️ **This is a demo project for testing purposes:**
- Uses test credentials and identifiers
- Demo mnemonic should never be used in production
- Hardcoded values should be externalized in production
- Test KERI identifiers have no real-world authority

## Troubleshooting

### "OOBI resolution failed"
- Check network connectivity to KERI services
- Verify OOBI URLs in configuration files
- Ensure schemas are published and accessible

### "Credential issuance failed"
- Verify issuer has valid parent credentials
- Check credential registry is properly initialized
- Ensure IPEX message structure is correct

### "Transaction submission failed"
- Verify Cardano backend service is running
- Check wallet has sufficient ADA
- Ensure metadata structure is valid

### "Credential verification failed"
- Check credential chain integrity
- Verify all signatures are valid
- Ensure credential registry contains the credential

## Dependencies

Key dependencies (see pom.xml for complete list):
- Signify Java 0.1.3-af01d93-SNAPSHOT
- Cardano Client Lib 0.7.0-beta2
- Jackson for JSON processing
- JUnit for testing

## Related Documentation

For standalone scripts that demonstrate specific aspects of KERI-Cardano integration, see the [parent directory README](../old/README.md) which covers:
- `AttestJsonMetadata.java` - Attesting to existing on-chain metadata
- `CreateProvenantCredential.java` - Creating provenant credentials

## Resources

- [KERI Documentation](https://keri.one/)
- [vLEI Specification](https://www.gleif.org/en/lei-solutions/gleifs-digital-strategy-for-the-lei/introducing-the-verifiable-lei-vlei)
- [ACDC (Authentic Chained Data Containers)](https://trustoverip.github.io/tswg-acdc-specification/)
- [IPEX Protocol](https://github.com/WebOfTrust/IETF-IPEX)
- [Cardano Metadata Standards](https://cips.cardano.org/)

## Contributing

This is a demonstration project. For production implementations:
1. Externalize all configuration
2. Implement proper secret management
3. Add comprehensive error handling
4. Add monitoring and logging
5. Implement proper key management
6. Follow security best practices

## License

[Specify your license here]
