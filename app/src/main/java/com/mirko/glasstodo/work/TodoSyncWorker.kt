package com.mirko.glasstodo.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.di.ServiceLocator
import com.mirko.glasstodo.domain.isPermanent
import com.mirko.glasstodo.widget.TodoGlanceWidget
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Safety net ONLY. While the app process is alive, RealtimeSync is the live path — do not also poll,
 * or the two writers fight and the widget flickers. This worker exists for the process-dead case:
 *
 *  1. refreshes the token (we disabled `enableLifecycleCallbacks`, so nobody else does it),
 *  2. pulls once into Room,
 *  3. re-renders the widget.
 *
 * v1 polled every 15 minutes and re-fetched over raw OkHttp on the widget's binder thread.
 */
class TodoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        val store = ServiceLocator.store(applicationContext)   // also guarantees SupabaseClient.init()
        AuthRepository().refreshSession()
        store.refresh()
        TodoGlanceWidget().updateAll(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e                                         // WorkManager stopped us; do not swallow it
    } catch (e: Exception) {
        // Offline (IOException), 5xx, and an expired token (401 — the next run refreshes it) are all
        // worth retrying with backoff. Only a permanent 4xx data failure is worth giving up on.
        if (e.isPermanent()) Result.failure() else Result.retry()
    }

    companion object {
        private const val NAME = "todo_widget_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodoSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)  // KEEP = no duplicate chains
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
