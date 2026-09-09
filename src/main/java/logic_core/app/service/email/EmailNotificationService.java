package logic_core.app.service.email;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Application-level email dispatch (Issue #20).
 *
 * <p><b>Delivery ordering invariant</b> — for security-sensitive flows the
 * call sequence is strictly:
 *
 * <pre>
 *   1. create/persist the OTP state
 *   2. the surrounding transaction commits
 *   3. this service schedules delivery (afterCommit)
 *   4. the executor delivers async through the adapter
 * </pre>
 *
 * <p>When a transaction is active, {@link #sendAfterCommit(EmailMessage)}
 * registers an {@code afterCommit} callback, so a rolled-back transaction
 * never dispatches an email. When no transaction is active the database write
 * has already committed and dispatch runs immediately (async).
 *
 * <p><b>Failure handling</b> — if the provider/network fails after the OTP
 * state was committed, the failure is logged with safe diagnostics and
 * counted, but the OTP stays valid per the existing expiry/attempt rules and
 * no credentials or raw codes are ever logged. The caller (use case) already
 * returned a generic success before delivery, which preserves
 * anti-enumeration.
 */
@Service
public class EmailNotificationService
{
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    @NonNull private final EmailDeliveryPort deliveryPort;

    private final AtomicLong deliveryFailures = new AtomicLong();

    public EmailNotificationService(
            @NonNull EmailDeliveryPort deliveryPort,
            @NonNull @Qualifier("emailTaskExecutor") Executor emailTaskExecutor)
    {
        this.deliveryPort = deliveryPort;
        // Point the dispatcher's production default at the bounded executor
        // (AsyncConfig). Tests may override via AsyncEmailDispatcher.setDelegate.
        AsyncEmailDispatcher.setDelegate(emailTaskExecutor::execute);
    }

    /**
     * Schedules delivery of {@code message} after the surrounding transaction
     * commits. See the class javadoc for the ordering invariant.
     */
    public void sendAfterCommit(EmailMessage message)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization()
                    {
                        @Override
                        public void afterCommit()
                        {
                            dispatchAsync(message);
                        }
                    }
            );
        }
        else
        {
            dispatchAsync(message);
        }
    }

    /**
     * Synchronous variant used by infrastructure/tests that must observe
     * delivery or failure deterministically.
     */
    public void sendSynchronously(EmailMessage message)
    {
        try
        {
            deliveryPort.send(message);
        }
        catch (EmailDeliveryException e)
        {
            deliveryFailures.incrementAndGet();
            log.warn("email delivery failed for type={} to={} — OTP state stays valid; {}",
                    message.type(), safeRecipient(message.to()), e.getMessage());
        }
    }

    private void dispatchAsync(EmailMessage message)
    {
        AsyncEmailDispatcher.dispatch(() -> sendSynchronously(message));
    }

    private static String safeRecipient(String email)
    {
        // Keep diagnostics useful without spilling a full address into logs.
        if (email == null || email.isBlank())
        {
            return "(blank)";
        }
        int at = email.indexOf('@');
        if (at <= 0)
        {
            return "(invalid-address)";
        }
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    /** Total async delivery failures since startup (safe diagnostics). */
    public long deliveryFailures()
    {
        return deliveryFailures.get();
    }

    /**
     * Scheduler seam so tests can force synchronous dispatch without touching
     * Spring wiring; production uses the virtual-thread/thread-pool dispatch
     * below. Calling {@link #setDelegate} with {@code null} restores the
     * default.
     */
    public static final class AsyncEmailDispatcher
    {
        private static volatile Consumer<Runnable> delegate =
                runnable -> CompletableFuture.runAsync(runnable);

        private AsyncEmailDispatcher()
        {
        }

        public static void dispatch(Runnable task)
        {
            delegate.accept(task);
        }

        public static void setDelegate(Consumer<Runnable> newDelegate)
        {
            AsyncEmailDispatcher.delegate = newDelegate == null
                    ? runnable -> CompletableFuture.runAsync(runnable)
                    : newDelegate;
        }
    }
}