<div align="center">
  <hr />
  <h1 align="center" style="border-bottom: none">Reeve | Cardano Foundation</h1>

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/cardano-foundation/cardano-wallet/blob/master/LICENSE)
[![Conventional Commits](https://img.shields.io/badge/Conventional%20Commits-1.0.0-%23FE5196?logo=conventionalcommits&logoColor=white)](https://conventionalcommits.org)
![GitHub release (with filter)](https://img.shields.io/github/v/release/cardano-foundation/cf-lob)
![Discord](https://img.shields.io/discord/1022471509173882950)

[![FOSSA Status](https://app.fossa.com/api/projects/custom%2B45571%2Fgithub.com%2Fcardano-foundation%2Fcf-lob-platform.svg?type=shield&issueType=license)](https://app.fossa.com/projects/custom%2B45571%2Fgithub.com%2Fcardano-foundation%2Fcf-lob-platform?ref=badge_shield&issueType=license)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=cardano-foundation_cf-lob-platform&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=cardano-foundation_cf-lob-platform)

[![Build](https://github.com/cardano-foundation/cf-lob/actions/workflows/build.yml/badge.svg)](https://github.com/cardano-foundation/cf-lob/actions/workflows/build.yml)
[![Build with tests](https://github.com/cardano-foundation/cf-lob/actions/workflows/build.yml/badge.svg)](https://github.com/cardano-foundation/cf-lob/actions/workflows/build-with-tests.yaml)


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
- **Organisation:** Manages organizational data and structures within the platform. This module handles information related to different entities, departments, and roles, ensuring proper access control and data management.
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
This project offers the needed components to customize an own application. To run the application, follow these steps:
- Create your own Spring Boot application and include the needed dependencies from this project
- Implement for your ERP system the needed adapter to integrate the Reeve platform with your system (or if it's NetSuite Altavia ERP, use the provided adapter)
- Start your Spring Boot Application

**We will provide a sample application in the near future to demonstrate how to use the Reeve platform.**


## Documentation

| Link                                           | Audience   |
|------------------------------------------------|------------|
| [On-chain Format](docs/onChainFormat.md)       | Developers |
| [Dataflow within the System](docs/dataflow.md) | Developers |
| [Code Of Conduct](CODE-OF-CONDUCT.md)          | Developers |
| [Contributing](CONTRIBUTING.md)                | Developers |
| [Security](SECURITY.md)                        | Developers |

<hr/>
