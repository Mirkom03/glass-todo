package com.mirko.glasstodo.data

import com.mirko.glasstodo.BuildConfig
import io.github.jan.supabase.SupabaseClient as KtClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object SupabaseClient {
    lateinit var client: KtClient
        private set

    fun init() {
        if (::client.isInitialized) return
        client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY   // publishable/anon; NEVER the secret key
        ) {
            install(Auth) {
                // Default lifecycle callbacks stop auto-refresh and set SessionStatus.Initializing on
                // ON_STOP -> a backgrounded widget/worker reads a null token -> ANON -> RLS returns
                // nothing (the real "empty widget" root cause; verified in 3.6.0 setupPlatform.kt).
                // Off = the session stays Authenticated in memory; WE own refresh instead
                // (TodoSyncWorker + on-resume call refreshCurrentSession()).
                enableLifecycleCallbacks = false
                alwaysAutoRefresh = true      // refresh job keeps running while the process is alive
            }
            install(Postgrest)
            install(Realtime)
        }
    }

    // Blocking accessors for the widget's binder-thread / worker context.
    // MUST await session load — the persisted session loads async, and the widget
    // often queries before it's ready (=> null token => anon => RLS returns nothing).
    fun currentAccessTokenBlocking(): String? = runBlocking(Dispatchers.IO) {
        runCatching {
            init()
            client.auth.awaitInitialization()
            client.auth.currentAccessTokenOrNull()
        }.getOrNull()
    }

    fun currentUserIdBlocking(): String? = runBlocking(Dispatchers.IO) {
        runCatching {
            init()
            client.auth.awaitInitialization()
            client.auth.currentUserOrNull()?.id
        }.getOrNull()
    }
}
