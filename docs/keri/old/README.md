# KERI Integration Scripts

This directory contains Java scripts for integrating KERI (Key Event Receipt Infrastructure) attestations with the Cardano blockchain.

## Scripts Overview

### 1. AttestJsonMetadata.java

This script retrieves JSON metadata from an existing Cardano transaction, creates a KERI attestation for it, and submits a new transaction containing both the original metadata and the KERI attestation.

#### Purpose
- Attest to JSON metadata already stored on-chain
- Create cryptographic proof linking KERI identifiers to Cardano transaction data
- Establish verifiable provenance for on-chain metadata

#### Prerequisites
- JBang installed (`brew install jbang` on macOS)
- Access to a KERI service (Reeve)
- Blockfrost API key for Cardano network access
- Cardano wallet mnemonic for transaction signing

#### Configuration

Before running, update the following constants in the script:

```java
// Transaction to attest
private static final String transactionHash = "YOUR_TX_HASH_HERE";

// Wallet mnemonic
private static final String mnemonic = "your twenty four word mnemonic phrase here";

// Network configuration
private static final Network network = Networks.mainnet(); // or Networks.testnet()
private static final BackendService backendService = new BFBackendService(
    "https://cardano-mainnet.blockfrost.io/api/v0/", 
    "YOUR_BLOCKFROST_API_KEY");

// KERI identifier 
private static final String IDENTIFIER_BRAN = "0ADF2TpptgqcDE5IQUF1H";
```

#### How It Works

1. **Retrieve Metadata**: Fetches JSON metadata with label `1447` from the specified transaction
2. **Create Digest**: Serializes the metadata and creates a BLAKE3-256 digest
3. **KERI Attestation**: Creates a KERI interaction event attesting to the digest
4. **Build Transaction**: Constructs metadata structure with:
   - Label `170`: KERI attestation data (type, identifier, digest, sequence number, version)
   - Label `1447`: Original JSON metadata
5. **Submit**: Signs and submits the transaction to Cardano

#### Usage

```bash
jbang AttestJsonMetadata.java
```

#### Output Metadata Structure

```json
{
  "170": {
    "t": "ATTEST",
    "i": "KERI_IDENTIFIER_PREFIX",
    "d": "QB64_ENCODED_DIGEST",
    "s": "SEQUENCE_NUMBER",
    "v": {
      "v": "1.0"
    }
  },
  "1447": {
    // Original JSON metadata
  }
}
```

---

### 2. CreateProvenantCredential.java

This script creates a complete vLEI (verifiable Legal Entity Identifier) credential flow on Cardano, including KERI credential issuance and on-chain authorization.

#### Purpose
- Establish KERI-based vLEI credentials
- Create credential registries
- Handle IPEX (Issuance and Presentation EXchange) protocol for credential issuance
- Anchor vLEI credentials on Cardano blockchain

#### Prerequisites
- JBang installed
- Access to KERI service and vLEI issuance server
- Cardano backend service (Blockfrost or local node)
- Wallet mnemonic for transaction signing
- Valid issuer AID and parent credential (for production use)

#### Configuration

Update these constants before running:

```java
// Issuer and credential details (MUST be updated)
private static final String ISSUER_AID = "YOUR_ISSUER_AID";
private static final String PARENT_CREDENTIAL_ID = "YOUR_PARENT_CREDENTIAL_ID";
private static final String LEI = "YOUR_LEGAL_ENTITY_IDENTIFIER";

// Wallet configuration
private static final String mnemonic = "your wallet mnemonic here";
private static final Network network = Networks.testnet();
private static final BackendService backendService = new BFBackendService(
    "http://localhost:8081/api/v1/", 
    "Dummy Key");

// KERI identifier (optional)
private static final String IDENTIFIER_BRAN = "0ADF2TpptgqcDE5IQUF1H";
```

#### How It Works

1. **Setup KERI Client**: Connects to KERI service and creates/retrieves client identity
2. **Create AID**: Establishes Autonomic Identifier (AID) with witness configuration
3. **Create Registry**: Sets up credential registry for vLEI issuance
4. **Resolve Schemas**: Resolves QVI, LE, and Reeve credential schemas via OOBIs
5. **IPEX Flow**: 
   - Waits for credential grant notifications
   - Builds admit arguments
   - Submits admit to accept credential
6. **Build Transaction**: Creates Cardano transaction with:
   - Label `170`: Authorization metadata including credential chain, schema SAID, and LEI
   - Credential data in CESR format

#### Usage

```bash
jbang ReceiveCredentialWithIdentifier.java
```

**Note**: This script requires interaction with a vLEI issuer. The issuer must grant credentials before the script can complete the IPEX admit flow.

#### Transaction Metadata Structure

```json
{
  "170": {
    "t": "AUTH_BEGIN",
    "s": "CREDENTIAL_CESR_DATA",
    "v": {
      "v": "1.0",
      "k": "KERI10",
      "a": "ACDC10"
    },
    "m": {
      "l": ["1447"],
      "LEI": "LEGAL_ENTITY_IDENTIFIER"
    }
  }
}
```

---

## Dependencies

Both scripts use:
- **Signify Java**: KERI implementation (`org.cardanofoundation:signify:0.1.3-af01d93-SNAPSHOT`)
- **Cardano Client Lib**: Cardano transaction building (`com.bloxbean.cardano:cardano-client-lib:0.7.0-beta2`)
- **Blockfrost Backend**: Cardano network interaction (`com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.0-beta2`)

Dependencies are automatically managed by JBang based on the `//DEPS` declarations in each script.

## Security Considerations

⚠️ **Important Security Notes**:
- Never commit mnemonics or API keys to version control
- Use environment variables or secure configuration management for secrets
- Test scripts thoroughly on testnet before using on mainnet
- The example mnemonic in the scripts is for testing only - use your own secure wallet
- Keep KERI identifiers and credentials secure

## Troubleshooting

### Common Issues

**"Client connection failed"**
- Verify KERI service URLs are accessible
- Check network connectivity
- Ensure KERI service is running

**"Transaction failed"**
- Check wallet has sufficient ADA for transaction fees
- Verify Blockfrost API key is valid
- Ensure network configuration matches backend service

**"No grant notifications received"** (CreateProvenantCredential)
- Verify issuer AID is correct
- Ensure credential grant was sent by issuer
- Check OOBI resolution succeeded

**"Registry already exists"**
- This is usually safe to ignore - the script will continue with existing registry

---

## Demo Project

> **Note**: For a complete end-to-end demonstration of the vLEI credential flow with demo credentials, see the [test-vlei](../test-vlei/README.md) project. This includes a full working example with test credentials for development and testing purposes.

## Resources

- [KERI Protocol](https://keri.one/)
- [vLEI Ecosystem](https://www.gleif.org/en/lei-solutions/gleifs-digital-strategy-for-the-lei/introducing-the-verifiable-lei-vlei)
- [Cardano Client Lib Documentation](https://github.com/bloxbean/cardano-client-lib)
- [Signify Java](https://github.com/cardano-foundation/signify-java)
