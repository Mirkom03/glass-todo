package com.mirko.glasstodo.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app shipped v1.3.3 with `version = 1` and no migrations registered at all. Bumping the schema
 * without a Migration throws `A migration from 1 to 2 was required but not found` when Room opens the
 * database — a launch crash for every installed user. This test is the only thing standing there.
 *
 * Uses the driver-based MigrationTestHelper, not the legacy `createDatabase(name, version)` overload:
 * that one configures its SupportSQLiteDriver with the bare database name and then asks it to open an
 * absolute path, so it dies with `This driver is configured to open a database named …`.
 *
 * The schemas reach this test through the `debug` source set's assets (see app/build.gradle.kts):
 * Robolectric reads `android_merged_assets`, and there is no mergeDebugUnitTestAssets task.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @Test
    fun migration1To2_addsNotesAsNull_andKeepsExistingRows() {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                """
                INSERT INTO todos
                    (id, userId, title, project, priority, done, createdAt, updatedAt, deleted, syncStatus)
                VALUES
                    ('t1', 'u1', 'Comprar pan', 'casa', 2, 0, 100, 100, 0, 'SYNCED')
                """.trimIndent()
            )
        }

        // runMigrationsAndValidate also checks the resulting schema against 2.json: a column declared
        // `TEXT NOT NULL` here but `String?` in the entity would fail the identity hash on a device.
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            db.prepare("SELECT title, notes FROM todos WHERE id = 't1'").use { row ->
                assertTrue("la fila de la v1 no sobrevivió a la migración", row.step())
                assertEquals("Comprar pan", row.getText(0))
                assertTrue("notes debería estrenar NULL", row.isNull(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
