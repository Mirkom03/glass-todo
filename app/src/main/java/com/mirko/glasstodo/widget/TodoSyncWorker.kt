package com.mirko.glasstodo.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TodoSyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseTodosRest.fetch()                 // (optionally persist to Room here)
            TodoWidgetProvider.notifyAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()                            // 401 => token expired, refreshed next run
        }
    }
}
