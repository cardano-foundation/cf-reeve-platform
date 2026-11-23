<div align="center">
  <hr />
  <h1 align="center" style="border-bottom: none">Reeve | Cardano Foundation</h1>

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/cardano-foundation/cardano-wallet/blob/master/LICENSE)
[![Conventional Commits](https://img.shields.io/badge/Conventional%20Commits-1.0.0-%23FE5196?logo=conventionalcommits&logoColor=white)](https://conventionalcommits.org)
![GitHub release (with filter)](https://img.shields.io/github/v/release/cardano-foundation/cf-lob-platform)
![Discord](https://img.shields.io/discord/1022471509173882950)

[![FOSSA Status](https://app.fossa.com/api/projects/custom%2B45571%2Fgithub.com%2Fcardano-foundation%2Fcf-lob-platform.svg?type=shield&issueType=license)](https://app.fossa.com/projects/custom%2B45571%2Fgithub.com%2Fcardano-foundation%2Fcf-lob-platform?ref=badge_shield&issueType=license)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=cardano-foundation_cf-lob-platform&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=cardano-foundation_cf-lob-platform)

[![Build](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/build.yml/badge.svg)](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/build.yml)
[![Build with tests](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/build-with-tests.yaml/badge.svg)](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/build-with-tests.yaml)
[![e2e Tests](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/e2e-tests.yml/badge.svg)](https://github.com/cardano-foundation/cf-lob-platform/actions/workflows/e2e-tests.yml)


<hr />
</div>
In a time where trust and transparency hold paramount importance, blockchain emerges as a powerful solution, enabling a fundamental transformation in financial data management. 
Reeve, also known as Ledger on the Blockchain (LOB), empowers organizations to securely record and share critical financial data on the Cardano blockchain, ensuring its integrity and verifiability for all stakeholders. 

## Project Overview
The Reeve platform is designed to integrate traditional accounting systems with blockchain technology, ensuring transparency, immutability, and enhanced security for financial records. By leveraging the Cardano blockchain, the platform offers a decentralized approach to financial data management, facilitating trust and efficiency in accounting processes.

Learn more about Reeve: [Reeve - Ledger on the Blockchain](https://www.cardanofoundation.org/blog/boosting-transparency-on-chain-financial-report)
### Key Features
- **Enhanced auditability:** The recording of transactions on-chain, immutable and tamper-proof, creates a verifiable trail of the financial activities and makes audits faster, less expensive and more reliable.
- **Strengthened Accountability:** Creates an immutable and transparent record of all financial activities, holding management teams accountable for their actions and decisions
- **Increased Trust:** Transparent sharing of data instills confidence in stakeholders and generates new opportunities for showcase commitment to responsible management
- **Improved Efficiency:** Reduces the cost and complexity of managing financial data, by creating a single source of truth, that can be used for automated reporting and streamlined working processes, freeing resources for innovation and growth initiatives.
- **Modular Software Architecture:** Facilitates scalability and extensibility, allowing for the integration of additional features and functionalities as needed

## Repository Structure
The repository is organized into several modules, each responsible for specific functionalities within the platform:

- **Accounting Reporting Core:** This module serves as the core component for accounting and reporting functionalities. It manages the processing and generation of financial reports, ensuring data integrity and compliance with accounting standards.
- **Blockchain Common:** Contains shared utilities and configurations for blockchain interactions. This module provides common interfaces and services that facilitate communication with the Cardano blockchain, ensuring consistency across different components.
- **Blockchain Publisher:** Responsible for publishing financial transactions and reports to the blockchain. It ensures that all relevant data is accurately and securely recorded on the decentralized ledger.
- **Blockchain Reader:** Handles the retrieval and reading of data from the blockchain. This module allows other components to access and verify financial records stored on the blockchain, supporting transparency and auditability.
- **Netsuite Altavia ERP Adapter:** An adapter designed to integrate the Reeve platform with NetSuite Altavia ERP systems. It facilitates seamless data exchange between the ERP and the blockchain, ensuring synchronization of financial information.
- **cf-application:** The deployable Spring Boot application (Kafka wiring, configuration profiles, healthcheck). It depends on the platform modules as in-repo projects and is built into the `cf-reeve-backend` image. It is not published to Maven Central.
- **cf_netsuite_altavia_erp_connector:** The Cardano Foundation specific NetSuite field convertors used by `cf-application`. Not published to Maven Central.
- **_backend-services/cf-reeve-ledger-follower-app:** Built into the `cf-reeve-follower-app` image.

The local run stack lives at the repository root as well: the `docker-compose*.yml` files, `keycloak-config/`, `keycloak-migrations/`, `keri-config/`, `yaci-config/`, `monitoring/`, `init-scripts/`, `certs/` and the `.envTemplate` / `.envMultiReplica` env files. The `postman/` and `playwright/` folders hold the e2e suites.
- **Organisation:** Manages organizational data and structures within the platform. This module handles information related to different entities, departments, and roles, ensuring proper access control and data management.
- **Document Vault:** Backend half of passkey-gated end-to-end-encrypted document exchange. Stores an organisation-scoped addressbook of member encryption keys (with notification e-mail and a visible custody tier), opaque wrapped-key records for multi-device sync, and uploaded ciphertext envelopes with per-recipient key slots. New recipients are added by importing an Ed25519-signed key card issued by the Indexer — the signature is the trust anchor, never the importer's word. Documents can be published by a manager or admin: the encrypted envelope is pinned to IPFS and a manifest is anchored on Cardano L1 (metadata label 1447, type DOCUMENT); published documents are immutable and are verified independently by the Indexer. The server can never read document content, never accepts private key material, and e-mail addresses never leave the operator's custody (all enforced by architecture tests).
- **Support:** Provides support utilities and services for the platform. This includes logging, error handling, and other auxiliary functions that enhance the platform's robustness and maintainability.
- **_backend-services/cf-lob-ledger-follower-app:** A backend service responsible for monitoring the blockchain ledger. It tracks new transactions and updates, ensuring that the platform remains synchronized with the latest state of the blockchain.

- **Notification Gateway (Coming soon):** Manages the notification system within the platform. It handles the dispatching of alerts and notifications to users or other systems based on specific events or triggers within the Reeve platform.

## Architecture
This repository uses [Spring Modulith](https://github.com/spring-projects/spring-modulith) architecture to organize the codebase into modules. The architecture is designed to promote modularity, scalability, and maintainability, allowing for the independent development and deployment of different components.
Through the use of modules it is possible to split the architecture into different layers, each responsible for specific functionalities within the platform. This separation of concerns enables better code organization, easier testing, and improved code reuse across different components.
![Architecutre](docs/images/architecture.png)

## How to build
This project uses Gradle as the build tool. To build the project, follow these steps:
```
git clone git@github.com:cardano-foundation/cf-reeve-platform.git
cd cf-reeve-platform
./gradlew clean build
```

## How to run

### DB migrations
The database migrations live in the private [cf-reeve-db-migrations](https://github.com/cardano-foundation/cf-reeve-db-migrations) repository.
They are a separate git checkout inside the application module (ignored by this repository), exactly as before:
```bash
git clone git@github.com:cardano-foundation/cf-reeve-db-migrations.git \
  cf-application/src/main/resources/db/migration/postgresql/cf-reeve-db-migrations
```
CI checks the same repository out into the same path before building the backend image and running the e2e suites.

### Start the whole stack
From the repository root:
```bash
docker compose --profile frontend up -d --build
```
The `--build` flag rebuilds the `api` and `publisher` images from the current source (application **and** platform modules).
This starts:
- `frontend`: The frontend application
- `api`: The REST API (accounting core, organisation, reporting, funding, ...)
- `publisher`: The blockchain publisher, ERP adapters and blockchain reader
- `postgres`: The database
- `yaci-devkit` / `yaci-viewer`: A single-node Cardano dev chain and its explorer
- `keycloak`: The identity provider
- `kafka`: The message broker

### Multi-replica configuration
Use [`.envMultiReplica`](.envMultiReplica) to run multiple API and publisher replicas locally:
```bash
docker compose --env-file .envMultiReplica --profile frontend --profile traefik up -d
```
Empty exposed-port values let Docker Compose allocate a port per replica; access the application through Traefik at [http://reeve.localhost](http://reeve.localhost).

### How to develop / debug
Start the stack as described above, then stop the container you want to run from your IDE and start the application with Gradle.
Platform modules are in-repo projects of `cf-application`, so changes to any module are picked up directly; there is no publish-to-Maven-local step.
```bash
sudo vim /etc/hosts # add the line: 127.0.0.1 kafka (only once)
docker compose up -d --build
docker container stop api
cp cf-application/.env.template cf-application/.env # adjust to your preferences
export SPRING_PROFILES_ACTIVE=dev--yaci-dev-kit
./gradlew :cf-application:bootRun # or run/debug LobServiceApp from your IDE
```
Build the application without Kafka support with `KAFKA_ENABLED=false ./gradlew :cf-application:build`.

### OAuth2 resource server
The backend uses Spring Boot's OAuth2 resource-server configuration. Docker Compose provides local defaults for:

- `OAUTH2_ISSUER_URI` (`http://localhost:8080/realms/reeve-master`)
- `OAUTH2_JWK_SET_URI` (the Keycloak URL reachable from the backend container)
- `OAUTH2_AUDIENCE` (`reeve-api`)
- `OAUTH2_AUTHORITIES_CLAIM_NAME` (`roles`)
- `OAUTH2_AUTHORITY_PREFIX` (`ROLE_`)
- `OAUTH2_PRINCIPAL_CLAIM_NAME` (`preferred_username`)

Override these variables when using a different OpenID Connect provider or externally hosted realm. The local realm emits the `reeve-api` audience and a flat `roles` claim so Spring Boot can perform issuer, audience, and authority validation without a custom JWT decoder.

Keycloak's startup import skips realms that already exist. Apply the non-destructive, idempotent migrations to an existing `reeve-master` realm while Keycloak is running: see [`keycloak-migrations/keycloak-config-cli/`](keycloak-migrations/keycloak-config-cli/).

### e2e tests
- Postman: `newman run postman/Reeve_Integration.postman_collection.json --environment postman/Reeve_env.postman_environment.json`
- Playwright: see [`playwright/README.md`](playwright/README.md)

Both suites run on every pull request (`.github/workflows/e2e-tests.yml`); the Allure report is published to GitHub Pages under `allure-report/`.

## Releases
- Every build publishes the `cf-reeve-backend` and `cf-reeve-follower-app` Docker images.
- Release tags additionally publish the platform library modules (`cf-lob-platform-*`) to Maven Central and dispatch the deployment event to the private deployment repository.

## Documentation

| Link                                           | Audience   |
|------------------------------------------------|------------|
| [On-chain Format](docs/onChainFormat.md)       | Developers |
| [Dataflow within the System](docs/dataflow.md) | Developers |
| [Code Of Conduct](CODE-OF-CONDUCT.md)          | Developers |
| [Contributing](CONTRIBUTING.md)                | Developers |
| [Security](SECURITY.md)                        | Developers |

<hr/>
