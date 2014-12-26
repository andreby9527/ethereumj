package org.ethereum.net.submit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.ethereum.core.Transaction;

/**
 * @author Roman Mandeleil
 * @since 23.05.2014
 */
public class TransactionExecutor {

    static {
        instance = new TransactionExecutor();
    }

    public static TransactionExecutor instance;
    private ExecutorService executor = Executors.newFixedThreadPool(1);

    public Future<Transaction> submitTransaction(TransactionTask task) {
        return executor.submit(task);
    }
}
