package top.pmh13.mctier.network

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Default application process: shares QuarkSupport's mutex, Keystore and encrypted ledger. */
class QuarkDailyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (QuarkSupport.get(applicationContext).backgroundDaily()) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No account identifiers, cookies or tickets in WorkManager input/output or logs.
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}

internal object QuarkDailyWork {
    const val NAME = "mctier-quark-daily"
    fun reconcile(context: Context, loggedIn: Boolean): Operation {
        val manager = WorkManager.getInstance(context)
        if (!loggedIn) {
            return manager.cancelUniqueWork(NAME)
        }
        // Periodic work survives ordinary process death and reboot. Android may defer it.
        // Foreground startup/resume additionally checks today's ledger immediately.
        // Two opportunities per day tolerate a deferred run; the ledger still permits
        // at most one submission per calendar day, with no resident polling service.
        val request = PeriodicWorkRequestBuilder<QuarkDailyWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(NAME)
            .build()
        return manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
