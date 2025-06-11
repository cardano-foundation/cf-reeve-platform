package org.cardanofoundation.lob.app.support.reactive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalTaskRunner {

    @Transactional
    public void runAfterTransaction(Runnable task) {
        task.run();
    }
}
