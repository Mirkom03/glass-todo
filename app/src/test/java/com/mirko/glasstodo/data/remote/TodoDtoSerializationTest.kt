package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.data.SupabaseJson
import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTO is upserted as-is by `drainPending`. kotlinx omits any field equal to its default unless
 * `encodeDefaults` is on, and PostgREST's ON CONFLICT DO UPDATE only writes the columns it receives.
 *
 * With encodeDefaults off, an offline un-tick (`done = false`) never reaches the server: it is
 * dropped from the JSON and the row stays `done = true` forever. Same for clearing a note. These
 * tests pin the serializer the real client is built with.
 */
class TodoDtoSerializationTest {

    private fun encoded(dto: TodoDto): JsonObject =
        Json.parseToJsonElement(SupabaseJson.encodeToString(dto)) as JsonObject

    @Test
    fun anUnTickIsActuallySent_notOmittedAsADefault() {
        val json = encoded(TodoDto(id = "1", user_id = "u1", title = "a", done = false))
        assertTrue("done ausente => el des-tick se pierde en drainPending", "done" in json)
        assertEquals(JsonPrimitive(false), json["done"])
    }

    @Test
    fun clearingANoteIsSentAsAnExplicitNull() {
        val json = encoded(TodoDto(id = "1", user_id = "u1", title = "a", notes = null))
        assertTrue("notes ausente => borrar la nota no se sincroniza", "notes" in json)
        assertEquals(JsonNull, json["notes"])
    }

    @Test
    fun clearingATagIsSentAsAnExplicitNull() {
        val json = encoded(TodoDto(id = "1", user_id = "u1", title = "a", project = null))
        assertEquals(JsonNull, json["project"])
    }

    @Test
    fun theDefaultPriorityIsSent() {
        val json = encoded(TodoDto(id = "1", user_id = "u1", title = "a", priority = 0))
        assertEquals(JsonPrimitive(0), json["priority"])
    }

    @Test
    fun unknownServerColumnsAreIgnoredOnDecode() {
        // public.todos also has completed_at, which the DTO deliberately does not model.
        val row = """{"id":"1","user_id":"u1","title":"a","completed_at":null}"""
        assertEquals("1", SupabaseJson.decodeFromString<TodoDto>(row).id)
    }

    @Test
    fun notesSurvivesTheRoundTrip() {
        val entity = TodoEntity(id = "1", userId = "u1", title = "a", notes = "confirmar el precio")
        assertEquals("confirmar el precio", entity.toDto().toEntity(SyncStatus.SYNCED).notes)
    }
}
