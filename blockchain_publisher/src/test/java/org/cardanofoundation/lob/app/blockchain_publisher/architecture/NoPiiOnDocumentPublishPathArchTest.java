package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/** Spec B5 #3: the IPFS/L1 formats are generated exclusively from these classes — no PII fields allowed. */
@AnalyzeClasses(packages = {
        "org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents",
        "org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document"})
class NoPiiOnDocumentPublishPathArchTest {

    @ArchTest
    static final ArchRule publishPathCarriesNoPii = ArchRuleDefinition.noFields()
            .that().doNotHaveName("recipientKeyHash")
            .should().haveNameMatching("(?i).*(e?mail|recipient|account|label|file_?name|description|display).*")
            .because("recipientKeyHash is a sha256 of a PUBLIC key, deliberately published on-chain so the "
                    + "Indexer can filter by recipient (docs/onChainFormat.md). It is exempted by name rather "
                    + "than renamed, so the decision stays visible in review; every other matching field name "
                    + "is still forbidden.");
}
