package com.mirko.glasstodo.widget

import com.mirko.glasstodo.BuildConfig
import com.mirko.glasstodo.data.SupabaseClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class WidgetTodo(val id: String, val title: String, val done: Boolean = false)

object SupabaseTodosRest {
    private val URL = "${BuildConfig.SUPABASE_URL}/rest/v1/todos"
    private const val ANON = BuildConfig.SUPABASE_ANON_KEY
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    // User JWT if signed in (RLS TO authenticated), else anon (which returns nothing under locked RLS).
    private fun bearer(): String = SupabaseClient.currentAccessTokenBlocking() ?: ANON

    fun fetch(): List<WidgetTodo> {
        val r = Request.Builder()
            .url("$URL?select=id,title,done&order=created_at.desc")
            .header("apikey", ANON)
            .header("Authorization", "Bearer ${bearer()}")
            .build()
        http.newCall(r).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            return json.decodeFromString(it.body!!.string())
        }
    }

    fun setDone(id: String, done: Boolean) {
        val body = "{\"done\":$done}".toRequestBody("application/json".toMediaType())
        val r = Request.Builder().url("$URL?id=eq.$id").patch(body)   // ?id=eq. filter MANDATORY
            .header("apikey", ANON)
            .header("Authorization", "Bearer ${bearer()}")
            .header("Prefer", "return=minimal")
            .build()
        http.newCall(r).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
    }

    fun insert(title: String, userId: String) {
        val body = "{\"title\":${jsonStr(title)},\"user_id\":${jsonStr(userId)},\"done\":false}"
            .toRequestBody("application/json".toMediaType())
        val r = Request.Builder().url(URL).post(body)
            .header("apikey", ANON)
            .header("Authorization", "Bearer ${bearer()}")
            .header("Prefer", "return=minimal")
            .build()
        http.newCall(r).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
    }

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
