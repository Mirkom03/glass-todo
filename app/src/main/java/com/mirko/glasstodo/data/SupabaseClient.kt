package com.mirko.glasstodo.data

import com.mirko.glasstodo.BuildConfig
import io.github.jan.supabase.SupabaseClient as KtClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The serializer the real client is built with, exposed so tests can pin its behaviour.
 *
 * `encodeDefaults = true` is load-bearing, not cosmetic. supabase-kt's own default Json only turns on
 * ignoreUnknownKeys (verified in SupabaseClientBuilder.defaultSerializer), so kotlinx drops every
 * field that equals its default — `done = false`, `project = null`, `notes = null`. `drainPending()`
 * replays a PENDING row with `upsert(toDto())`, and PostgREST's ON CONFLICT DO UPDATE only writes the
 * columns it was sent: an offline un-tick was silently dropped and the server kept `done = true`.
 *
 * The cost is that a replayed row now overwrites every column it carries (last write wins). One user,
 * few devices, and the window is a single failed push — see §5.5 of docs/2026-07-10-panel-detalle-tarea-design.md.
 */
val SupabaseJson: Json = Json {
    ignoreUnknownKeys = true    // public.todos also has completed_at, which the DTO does not model
    encodeDefaults = true
}

object SupabaseClient {
    lateinit var client: KtClient
        private set

    fun init() {
        if (::client.isInitialized) return
        client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY   // publishable/anon; NEVER the secret key
        ) {
            defaultSerializer = KotlinXSerializer(SupabaseJson)
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
