# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2025-09-18

### Major Features ✨

#### Financial Reporting & Analytics
- **Automated Report Generation**: Implemented automatic generation of Balance Sheet and Income Statement reports with configurable date ranges and opening balance support
- **Enhanced Financial Metrics**: Added comprehensive financial metrics including extraordinary income, financial income, tax expenses, and rental expenses calculations
- **Report Validation & Status Management**: New report validation system with status tracking (PENDING, APPROVED, PUBLISHED) and automated status updates
- **Public Financial Interface**: Added public API endpoints for published financial reports and organizational data transparency

#### User Management & Security
- **Enhanced Authentication**: Improved Keycloak integration with organization-based access control and JWT token validation
- **Role-Based Access Control**: Added new security roles including Admin and Accountant with granular permission management
- **User Audit Tracking**: Comprehensive audit logging for user actions with proper attribution in multi-user environments
- **Content Security Policy**: Configurable CSP headers for enhanced web security

#### Data Management & Processing
- **Chart of Accounts CRUD**: Full Create, Read, Update, Delete operations for Chart of Accounts with account type and subtype management
- **Reference & Event Code Management**: Complete CRUD operations for reference codes and event codes with parent-child relationships and activation status
- **Enhanced Transaction Processing**: Improved transaction validation, batch processing, and violation handling with better error reporting
- **Reconciliation Enhancements**: Advanced reconciliation features with filtering, source tracking, and comprehensive status reporting

### New Features 🎉

#### API & Integration
- **API Versioning**: Implemented API versioning system for backward compatibility
- **Pagination Support**: Added pagination to public API endpoints with configurable limits
- **OAuth 2.0 Support**: Enhanced NetSuite and blockchain integrations with OAuth 2.0 authentication
- **Mock Services**: Configurable mock services for development and testing environments

#### Blockchain & Publishing
- **Report Serialization**: Serialize financial reports to blockchain using API3 format with metadata versioning
- **Transaction Monitoring**: Enhanced blockchain transaction monitoring with rollback detection and status tracking
- **Publication Pipeline**: Improved publication workflow with better error handling and retry mechanisms
- **Ledger Integration**: Enhanced blockchain ledger follower application with network-specific configurations

#### User Experience
- **Dashboard Management**: Save and manage custom dashboards with user-specific configurations
- **Currency Selection**: Multi-currency support in transaction views and reports
- **Date Range Filtering**: Enhanced date filtering across transactions, reports, and reconciliation views
- **Real-time Status Updates**: Live status updates for batch processing and transaction states

### Improvements 🚀

#### Performance & Scalability  
- **Batch Processing Optimization**: Significantly improved batch processing performance with better memory usage and parallel processing
- **Database Query Optimization**: Optimized database queries for faster report generation and transaction retrieval  
- **Spring Modulith Archive Mode**: Implemented event archiving for better performance in high-volume environments
- **Caching Improvements**: Enhanced caching strategies for frequently accessed data

#### Code Quality & Maintenance
- **Test Coverage Enhancement**: Increased test coverage across all modules with comprehensive integration tests
- **Code Quality Tools**: Integrated SonarCloud analysis and added Spotless code formatting enforcement
- **Security Scanning**: Added FOSS security scanning to CI/CD pipeline
- **Documentation**: Improved documentation with clear API specifications and deployment guides

### Bug Fixes 🐛

#### Critical Fixes
- **Transaction Processing**: Fixed issues with transaction validation that were causing incorrect PENDING status assignments
- **Batch Statistics**: Corrected batch statistics calculation that was showing wrong transaction counts during reprocessing
- **Report Generation**: Fixed null pointer exceptions in report generation when opening balance data was missing
- **Reconciliation**: Fixed reconciliation count errors and improved transaction matching accuracy

#### Data Integrity
- **Database Consistency**: Fixed embedded object updates that weren't being persisted correctly
- **Foreign Key Violations**: Added proper validation to prevent primary key violations during batch imports
- **Decimal Precision**: Corrected rounding issues in financial calculations and database storage
- **Transaction Serialization**: Fixed JSON serialization issues for delayed dispatch strategies

#### User Interface & API
- **Authentication**: Fixed anonymous user access issues for public API endpoints  
- **Organization Validation**: Improved organization validation with better error messages
- **Field Mappings**: Corrected missing parameters in API responses for Chart of Accounts and other entities
- **Search Functionality**: Fixed SQL injection vulnerabilities in search queries with parameterized queries

### Technical Improvements 🔧

#### Infrastructure & Deployment
- **Docker Configuration**: Updated Docker configurations for multi-network blockchain deployment (mainnet, preprod, preview)
- **Environment Variables**: Normalized environment variable naming across different deployment profiles
- **Build Process**: Enhanced Gradle build process with SBOM generation and dependency management
- **CI/CD Pipeline**: Improved GitHub Actions workflow with parallel testing and security scanning

#### Development Experience
- **Code Generation**: Enhanced MapStruct configurations for better type safety and performance
- **Error Handling**: Improved error handling across all modules with better exception propagation
- **Logging**: Enhanced logging configuration for better debugging and monitoring
- **Testing**: Added mutation testing with PIT for better test quality assurance

### Migration Notes 📋

#### Breaking Changes
- **API Endpoints**: Some organization endpoints have changed from PUT to POST methods
- **Database Schema**: New tables and columns added for enhanced functionality (automatic migration included)
- **Configuration**: Updated configuration property names for better consistency

#### Recommended Actions
1. **Database Backup**: Always backup your database before upgrading
2. **Configuration Review**: Review and update configuration files for new properties
3. **API Client Updates**: Update API clients to handle new pagination and versioning
4. **User Role Assignment**: Review and assign new security roles to existing users

### Security Updates 🔒

- Fixed potential SQL injection vulnerabilities in search functionality
- Enhanced token validation and refresh mechanisms
- Improved input validation across all API endpoints
- Updated dependency versions to address security vulnerabilities
- Strengthened authentication flow with better session management

### Dependencies 📦

#### Major Updates
- Spring Boot updated to latest stable version
- Enhanced PostgreSQL driver with better performance
- Updated Cardano blockchain integration libraries
- Improved MapStruct version for better code generation

#### New Dependencies
- Added Spring Modulith for better modular architecture
- Integrated SonarCloud for code quality analysis
- Added Spotless for consistent code formatting
- Enhanced testing framework with additional assertion libraries

---

## [1.0.1] - 2025-07-14

### Bug Fixes
- Fixed pagination issues in public pages endpoint
- Improved report number serialization
- Enhanced database pagination performance
- Fixed transaction processing edge cases

## [1.0.0] - 2025-07-08

### Initial Release
- Core accounting and reporting functionality
- Blockchain integration for financial transparency
- Basic user management and authentication
- Transaction processing and batch handling
- Financial report generation (Balance Sheet, Income Statement)
- Public API for accessing published financial data

---

For detailed technical information and migration guides, please refer to the [documentation](./docs/) or visit our [Wiki](https://github.com/cardano-foundation/cf-reeve-platform/wiki).

**Full Changelog**: https://github.com/cardano-foundation/cf-reeve-platform/compare/1.0.1...1.1.0