package mp4.socllm;

import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rejects calls made inside a caller-owned SQL transaction before admission or provider work. */
public final class SocLlmTransactionBoundary {
    private SocLlmTransactionBoundary() {}

    public static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
    }
}
