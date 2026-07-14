package org.cardanofoundation.lob.app.document_vault.architecture;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Blueprint B5 / invariant I5: no API schema may accept or return plaintext content, DEKs, KEKs,
 * PRF outputs, or private/unwrapped keys. These rules are a naming-discipline gate: any new DTO
 * field that even looks like secret material fails CI and forces an explicit review.
 * Allowed by design: plaintextHash (commitment), wrappedDek (encrypted), publicKey/ephemeralPub.
 */
@AnalyzeClasses(packages = "org.cardanofoundation.lob.app.document_vault")
class NoSecretMaterialArchTest {

    /** Bare names that always denote secret material. Compared case-insensitively. */
    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "dek", "kek", "plaintext", "privatekey", "prf", "prfoutput", "secret", "unwrappedkey", "contentkey");

    private static final ArchCondition<JavaField> NOT_BE_SECRET_MATERIAL =
            new ArchCondition<>("not be named like secret material (blueprint I5)") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    String name = field.getName().toLowerCase();
                    boolean forbidden = FORBIDDEN_FIELD_NAMES.contains(name)
                            || (name.contains("plaintext") && !name.equals("plaintexthash"))
                            || name.contains("privatekey")
                            || name.contains("unwrapped");
                    if (forbidden) {
                        events.add(SimpleConditionEvent.violated(field,
                                "Field %s.%s looks like secret material — forbidden by blueprint I5"
                                        .formatted(field.getOwner().getName(), field.getName())));
                    }
                }
            };

    @ArchTest
    static final ArchRule apiDtosCarryNoSecretMaterial = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage(
                    "..domain.request..", "..domain.view..", "..domain.card..", "..domain.events..")
            .should(NOT_BE_SECRET_MATERIAL);

    @ArchTest
    static final ArchRule entitiesCarryNoSecretMaterial = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain.entity..")
            .should(NOT_BE_SECRET_MATERIAL);

    private static final String ENVELOPE_VIEW =
            "org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView";

    /**
     * Ciphertext leaves the API through exactly ONE view: DocumentEnvelopeView (the authorized
     * envelope-fetch endpoint, blueprint D2). Every other view stays ciphertext-free. Exact-name
     * match (class or its nested records, "$"-separated) — a substring match could be bypassed
     * by naming a new view "...DocumentEnvelopeViewX".
     */
    @ArchTest
    static final ArchRule onlyTheEnvelopeViewExposesCiphertext = ArchRuleDefinition.noFields()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain.view..")
            .and().areDeclaredInClassesThat(DescribedPredicate.describe(
                    "outside DocumentEnvelopeView",
                    javaClass -> !javaClass.getFullName().equals(ENVELOPE_VIEW)
                            && !javaClass.getFullName().startsWith(ENVELOPE_VIEW + "$")))
            .should().haveNameMatching("(?i).*ciphertext.*");
}
