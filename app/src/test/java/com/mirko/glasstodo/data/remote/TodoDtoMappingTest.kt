package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TodoDtoMappingTest {

    private val entity = TodoEntity(
        id = "0f1e2d3c-0000-4000-8000-000000000001",
        userId = "511b897e-afb2-4745-91f6-d8103ad4aefd",
        title = "Comprar pan",
        project = "casa",
        priority = 2,
        done = true,
        createdAt = 1_700_000_000_000L,
        syncStatus = SyncStatus.SYNCED,
    )

    @Test
    fun roundTrip_preservesEveryRemoteField() {
        val back = entity.toDto().toEntity(SyncStatus.SYNCED)
        assertEquals(entity.id, back.id)
        assertEquals(entity.userId, back.userId)
        assertEquals(entity.title, back.title)
        assertEquals(entity.project, back.project)
        assertEquals(entity.priority, back.priority)
        assertEquals(entity.done, back.done)
        assertEquals(entity.createdAt, back.createdAt)   // order-by-creation survives a sync cycle
    }

    @Test
    fun toEntity_parsesPostgresTimestamptz() {
        // PostgREST emits the offset form with microseconds, not Z
        val dto = TodoDto(
            id = "a", user_id = "u", title = "t",
            created_at = "2026-07-09T05:38:00.123456+00:00",
        )
        val expected = Instant.parse("2026-07-09T05:38:00.123Z").toEpochMilli()
        assertEquals(expected, dto.toEntity(SyncStatus.SYNCED).createdAt)
    }

    @Test
    fun toEntity_zuluSuffixAlsoParses() {
        val dto = TodoDto(id = "a", user_id = "u", title = "t", created_at = "2026-07-09T05:38:00Z")
        val expected = Instant.parse("2026-07-09T05:38:00Z").toEpochMilli()
        assertEquals(expected, dto.toEntity(SyncStatus.SYNCED).createdAt)
    }

    /**
     * Realtime does not go through PostgREST's JSON encoder: `postgres_changes` ships the column's
     * NATIVE text (`2026-07-09 05:38:00.123456+00` — a space instead of `T`, and a two-digit offset).
     * `OffsetDateTime.parse` rejects both, so `parseTimestampMillis` returned null and `toEntity` fell
     * back to `System.currentTimeMillis()`: every realtime snapshot restamped `createdAt` to now,
     * reshuffling the list (ORDER BY createdAt DESC) and then feeding that wrong value back to the
     * server on the next drain, which sends `created_at`.
     */
    @Test
    fun toEntity_parsesTheNativePostgresTextForm_asRealtimeEmitsIt() {
        val expected = Instant.parse("2026-07-09T05:38:00.123Z").toEpochMilli()
        val withMicros = TodoDto(id = "a", user_id = "u", title = "t", created_at = "2026-07-09 05:38:00.123456+00")
        assertEquals(expected, withMicros.toEntity(SyncStatus.SYNCED).createdAt)

        val whole = TodoDto(id = "a", user_id = "u", title = "t", created_at = "2026-07-09 05:38:00+00")
        assertEquals(
            Instant.parse("2026-07-09T05:38:00Z").toEpochMilli(),
            whole.toEntity(SyncStatus.SYNCED).createdAt,
        )
    }

    @Test
    fun toEntity_missingOrGarbageTimestampFallsBackToNow() {
        val before = System.currentTimeMillis()
        val missing = TodoDto(id = "a", user_id = "u", title = "t").toEntity(SyncStatus.SYNCED)
        val garbage = TodoDto(id = "a", user_id = "u", title = "t", created_at = "not-a-date")
            .toEntity(SyncStatus.SYNCED)
        val after = System.currentTimeMillis()
        assertTrue(missing.createdAt in before..after)
        assertTrue(garbage.createdAt in before..after)
    }

    @Test
    fun toEntity_appliesRequestedSyncStatus() {
        val dto = entity.toDto()
        assertEquals(SyncStatus.PENDING, dto.toEntity(SyncStatus.PENDING).syncStatus)
        assertEquals(SyncStatus.SYNCED, dto.toEntity(SyncStatus.SYNCED).syncStatus)
    }

    @Test
    fun parseTimestampMillis_nullAndGarbageReturnNull() {
        assertNull(parseTimestampMillis(null))
        assertNull(parseTimestampMillis("2026-99-99"))
    }
}
