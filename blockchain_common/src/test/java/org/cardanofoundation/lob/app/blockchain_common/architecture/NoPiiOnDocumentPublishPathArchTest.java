package org.cardanofoundation.lob.app.blockchain_common.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Follows the document publish path into {@code blockchain_common}.
 *
 * <p>The sibling rule in {@code blockchain_publisher} scans
 * {@code ...blockchain_publisher.service.publish.module.document} and its {@code domain.entity.documents}.
 * When {@code DocumentPublishCommand}, {@code DocumentIpfsSerialiser} and
 * {@code DocumentMetadataSerialiser} moved here — so the freeze could run in the user-facing tier
 * without the publisher — they left that scan's reach. Everything the document publish path emits is
 * public and permanent (an IPFS envelope and on-chain metadata), so losing the guard silently would be
 * the worst possible way to lose it. This restores identical coverage over their new home.
 *
 * <p>Keep the field-name pattern in sync with
 * {@code blockchain_publisher}'s {@code NoPiiOnDocumentPublishPathArchTest}.
 */
@AnalyzeClasses(packages = {
        "org.cardanofoundation.lob.app.blockchain_common.domain.events",
        "org.cardanofoundation.lob.app.blockchain_common.service_assistance"})
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
