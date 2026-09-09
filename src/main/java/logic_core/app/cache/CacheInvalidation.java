package logic_core.app.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * After-commit cache invalidation (V2.1 #19).
 *
 * <p><b>Transaction safety rule:</b> cache eviction must never happen before
 * the database commit that makes the new authoritative state visible. When the
 * caller runs inside an active Spring transaction, the eviction is registered
 * as an {@code afterCommit} callback — so a rolled-back transaction evicts
 * nothing. When no transaction is active, the database write (performed by the
 * {@code @Transactional} repository adapter) has already committed by the time
 * the use case reaches this point, so the eviction runs immediately.
 */
@Component
@RequiredArgsConstructor
public class CacheInvalidation
{
    private final CacheService cacheService;

    /** Evicts {@code key} after the enclosing transaction commits. */
    public void evictAfterCommit(String key)
    {
        runAfterCommit(() -> cacheService.evict(key));
    }

    /** Evicts every key matching {@code pattern} after commit (SCAN-based). */
    public void evictByPatternAfterCommit(String pattern)
    {
        runAfterCommit(() -> cacheService.evictByPattern(pattern));
    }

    private void runAfterCommit(Runnable action)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization()
                    {
                        @Override
                        public void afterCommit()
                        {
                            action.run();
                        }
                    }
            );
        }
        else
        {
            action.run();
        }
    }
}