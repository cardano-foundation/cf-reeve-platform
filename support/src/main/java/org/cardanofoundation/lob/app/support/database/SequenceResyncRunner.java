package org.cardanofoundation.lob.app.support.database;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SequenceResyncRunner implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    // This method will be called after the application context is loaded
    // This is needed sync we are adding new data to the database via flyway, this way hibernate will loose the context of the id sequence
    // This way we can sync the sequence with the max id in the table and inserting new data will not cause a conflict
    public void run(ApplicationArguments args) {
        resyncSequence("organisation_chart_of_account_type", "id");
        resyncSequence("organisation_chart_of_account_sub_type", "id");
        // Add more entities as needed
    }

    private void resyncSequence(String tableName, String idColumn) {
        String sql = String.format("""
            SELECT setval(
                pg_get_serial_sequence('%s', '%s'),
                COALESCE((SELECT MAX(%s) FROM %s), 0)
            )
        """, tableName, idColumn, idColumn, tableName);

        entityManager.createNativeQuery(sql).getSingleResult();
    }
}
