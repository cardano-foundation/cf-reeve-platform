package org.cardanofoundation.lob.app.support.database;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SequenceResyncRunner implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;

    public SequenceResyncRunner(PlatformTransactionManager transactionManager) {
        // Run each resync in a separate transaction so one failure doesn't poison the whole run.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    @Override
    // This method will be called after the application context is loaded
    // This is needed since we are adding new data to the database via flyway; this way Hibernate will lose the context of the id sequence.
    // We sync the sequence with the max id in the table so inserting new data will not cause a conflict.
    public void run(ApplicationArguments args) {
        resyncSequence("organisation_chart_of_account_type", "id");
        resyncSequence("organisation_chart_of_account_sub_type", "id");
        // Reporting module
        resyncSequence("report_template_field", "id");
        resyncSequence("report_template_validation_rule", "id");
        resyncSequence("report_template_validation_rule_term", "id");
        // Add more entities as needed
    }

    private void resyncSequence(String tableName, String idColumn) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                String sql = String.format("""
                    SELECT setval(
                        pg_get_serial_sequence('%s', '%s'),
                        COALESCE((SELECT MAX(%s)::bigint FROM %s), 0::bigint),
                        true
                    )
                """, tableName, idColumn, idColumn, tableName);

                try {
                    entityManager.createNativeQuery(sql).getSingleResult();
                } catch (Exception e) {
                    // Mark this inner transaction for rollback so the connection isn't left in an aborted state.
                    status.setRollbackOnly();
                    System.err.println("Failed to resync sequence for table " + tableName + ": " + e.getMessage());
                }
            }
        });
    }
}
