# Panel de detalle de tarea — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El círculo marca la tarea; el resto de la fila abre una hoja con la tarea entera (descripción editable, título, etiqueta, urgencia, borrar), idéntica en la app y en el widget.

**Architecture:** Un composable stateless `TaskDetailContent` lo pintan dos anfitriones: un `ModalBottomSheet` dentro de `TodoScreen`, y una `TaskDetailActivity` translúcida que el widget lanza con `actionStartActivity`. La fila (app y widget) pasa de un `clickable` a dos zonas hermanas disjuntas. Debajo, un campo `notes` nuevo de punta a punta (Postgres → Room → DTO → UI) y un `update()` optimista en `TodoStore` con el mismo settle/rollback guardado que ya usa `toggle()`.

**Tech Stack:** Kotlin 2.2.20 · Compose BOM 2026.06.01 (material3 1.4.0) · Glance 1.1.1 · Room 2.8.3 · supabase-kt 3.6.0 · Robolectric 4.14.1 + Roborazzi 1.43.1

Spec: `docs/2026-07-10-panel-detalle-tarea-design.md`. Rama: `feat/panel-detalle-tarea`.

## Global Constraints

- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`. Todo el código debe compilar y correr en API 26.
- **Una Activity translúcida NO puede declarar `android:screenOrientation`.** En API 26 lanza `IllegalStateException: Only fullscreen opaque activities can request orientation`.
- **Nunca `androidx.glance.appwidget.CheckBox` ni `Switch` en una fila del widget.** Un `CompoundButton` es clickable y focusable por defecto a nivel de `View` y se come el touch. El círculo es siempre una `Image` con `.clickable`.
- **Nunca el overload `clickable { }` (lambda) dentro del bucle de `items` sin `key` único por fila** (bug `b/282445798`: la clave es `currentCompositeKeyHash` y colisiona). Usar siempre `actionRunCallback<T>(params)` o `actionStartActivity<T>(params)`, que son tipadas.
- El widget **no consulta `GlanceTheme` en ningún sitio**. Importa los tokens de `ui/theme/Theme.kt` como `ColorProvider` fijos. (Así salió lavanda Material You en v1.3.0.)
- `TodoStore` escribe **siempre en Room primero** (optimista, `PENDING`), empuja después, y hace settle/rollback **guardado**: un push que completa tarde no puede pisar una escritura más nueva.
- Español con acentos y ñ en todo el texto de UI. Comentarios de código en inglés, como el resto del repo.
- Build local (no a ciegas contra el CI):
  ```bash
  export JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10"
  export ANDROID_HOME="C:/Users/mirko/tools/android-sdk"
  ./gradlew testDebugUnitTest --console=plain
  ```

---

### Task 1: La columna `notes` en Postgres

Va **primero**: los clientes en v1.3.3 ignoran una columna que no conocen, así que añadirla no rompe nada y desbloquea todo lo demás.

**Files:**
- Create: `supabase/002_todos_notes.sql`

**Interfaces:**
- Consumes: nada.
- Produces: la columna `public.todos.notes` (`text`, nullable), sobre la que se apoyan las Tasks 4, 5 y 6.

- [ ] **Step 1: Escribir la migración**

Crear `supabase/002_todos_notes.sql`:

```sql
-- Panel de detalle de tarea (v1.4.0) — la descripción libre de una tarea.
-- Nullable y sin default: cambio de catálogo instantáneo, sin reescritura de tabla.
-- RLS NO cambia: las 4 políticas de public.todos filtran por user_id y son agnósticas a columnas.
-- Realtime NO cambia: replica identity ya es FULL, así que la columna viaja en el WAL sola.
alter table public.todos add column notes text;
```

- [ ] **Step 2: Aplicarla a Supabase**

Aplicar por MCP contra el proyecto `wkjfnpjklmikswgofekq`, nombre de migración `add_todos_notes_column`, con el SQL de arriba.

- [ ] **Step 3: Verificar que la columna existe**

Ejecutar por MCP:

```sql
select column_name, data_type, is_nullable
  from information_schema.columns
 where table_schema = 'public' and table_name = 'todos' and column_name = 'notes';
```

Esperado: exactamente una fila — `notes | text | YES`. Si devuelve 0 filas, la migración no se aplicó: **parar aquí**.

- [ ] **Step 4: Verificar que RLS sigue intacta**

```sql
select polname, polcmd from pg_policy
 where polrelid = 'public.todos'::regclass order by polname;
```

Esperado: 4 filas (`owner deletes`/`d`, `owner inserts`/`a`, `owner reads`/`r`, `owner updates`/`w`).

- [ ] **Step 5: Commit**

```bash
git add supabase/002_todos_notes.sql
git commit -m "feat(db): la columna notes en public.todos — aplicada a wkjfnpjklmikswgofekq"
```

---

### Task 2: Exportar el esquema de Room (bootstrap del `1.json`)

`AppDatabase` está en `exportSchema = false`, así que el esquema de la v1 **nunca se exportó**. Sin `1.json` no se puede validar la migración 1→2 con `MigrationTestHelper`. Este task se hace **antes** de tocar la entidad, con `version = 1` todavía, para capturar el esquema actual.

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/data/local/AppDatabase.kt:13`
- Modify: `app/build.gradle.kts` (bloque `android { }` y bloque nuevo `ksp { }`)
- Create: `app/schemas/com.mirko.glasstodo.data.local.AppDatabase/1.json` (lo genera KSP)

**Interfaces:**
- Consumes: nada.
- Produces: `app/schemas/…/1.json`, que consume el `MigrationTestHelper` de la Task 3.

- [ ] **Step 1: Activar `exportSchema` sin tocar la versión**

En `app/src/main/java/com/mirko/glasstodo/data/local/AppDatabase.kt`, línea 13:

```kotlin
@Database(entities = [TodoEntity::class], version = 1, exportSchema = true)
```

- [ ] **Step 2: Decirle a KSP dónde escribir el esquema**

En `app/build.gradle.kts`, **después** del bloque `android { }` y antes de `dependencies { }`:

```kotlin
// Room exporta el esquema de cada versión a app/schemas/, versionado en git. Sin esto no existe
// el JSON de la v1 y MigrationTestHelper no puede validar la migración 1->2.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Y dentro de `android { }`, junto a `testOptions`, añadir el source set para que los tests JVM lean el esquema desde assets:

```kotlin
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
```

- [ ] **Step 3: Generar el esquema**

```bash
export JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10"
export ANDROID_HOME="C:/Users/mirko/tools/android-sdk"
./gradlew :app:kspDebugKotlin --console=plain
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verificar que el JSON existe y describe la v1**

```bash
ls app/schemas/com.mirko.glasstodo.data.local.AppDatabase/
python -c "import json;d=json.load(open('app/schemas/com.mirko.glasstodo.data.local.AppDatabase/1.json'));print(d['database']['version']);print([f['columnName'] for f in d['database']['entities'][0]['fields']])"
```

Esperado: `1.json` existe; la versión imprime `1`; las columnas son exactamente
`['id', 'userId', 'title', 'project', 'priority', 'done', 'createdAt', 'updatedAt', 'deleted', 'syncStatus']` — **sin `notes`**. Si `notes` ya aparece, la Task 3 se coló antes: revertir y rehacer.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/mirko/glasstodo/data/local/AppDatabase.kt app/schemas
git commit -m "build(room): exportar el esquema — captura la v1 antes de migrar a la v2"
```

---

### Task 3: `notes` en Room y la migración 1→2

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/data/local/TodoEntity.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/domain/TodoUi.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/di/ServiceLocator.kt:33`
- Create: `app/src/test/java/com/mirko/glasstodo/data/local/Migration1To2Test.kt`
- Create: `app/schemas/com.mirko.glasstodo.data.local.AppDatabase/2.json` (lo genera KSP)

**Interfaces:**
- Consumes: `app/schemas/…/1.json` (Task 2).
- Produces:
  - `TodoEntity.notes: String?`
  - `TodoUi.notes: String?`
  - `com.mirko.glasstodo.data.local.MIGRATION_1_2: Migration`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/mirko/glasstodo/data/local/Migration1To2Test.kt`:

```kotlin
package com.mirko.glasstodo.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app shipped v1.3.3 with `version = 1` and no migrations registered at all. Bumping the schema
 * without a Migration throws `A migration from 1 to 2 was required but not found` when Room opens the
 * database — a launch crash for every installed user. This test is the only thing standing there.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration1To2_addsNotesAsNull_andKeepsExistingRows() {
        helper.createDatabase(DB, 1).use { db ->
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
        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        db.query("SELECT title, notes FROM todos WHERE id = 't1'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Comprar pan", c.getString(0))   // la fila sobrevive
            assertNull(c.getString(1))                    // y estrena notes = NULL
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
```

- [ ] **Step 2: Ejecutar el test y comprobar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "*Migration1To2Test*" --console=plain
```

Esperado: FAIL en compilación — `Unresolved reference: MIGRATION_1_2`.

- [ ] **Step 3: Añadir el campo a la entidad**

En `app/src/main/java/com/mirko/glasstodo/data/local/TodoEntity.kt`, dentro de `TodoEntity`, **después** de `val done: Boolean = false,`:

```kotlin
    val notes: String? = null,           // free-text description, edited from the detail sheet
```

y en `fun TodoEntity.toUi()` añadir el mapeo, después de `done = done,`:

```kotlin
    notes = notes,
```

- [ ] **Step 4: Añadir el campo al modelo de UI**

En `app/src/main/java/com/mirko/glasstodo/domain/TodoUi.kt`:

```kotlin
package com.mirko.glasstodo.domain

/** The single UI/domain model shown by both the app and the widget. */
data class TodoUi(
    val id: String,
    val title: String,
    val project: String?,
    val done: Boolean,
    val pending: Boolean = false,   // true while a local write hasn't reached the server yet
    val priority: Int = 0,          // higher = more urgent; drives the sort order
    val notes: String? = null,      // the detail sheet's description; null and "" both mean "empty"
)
```

> `notes` va en `TodoUi` aunque el widget no lo pinte: sin él, un cambio de solo-notas produce una
> lista estructuralmente idéntica y el `StateFlow` no recompone — el dato llegaría a Room y no a la UI.

- [ ] **Step 5: Subir la versión y escribir la migración**

`app/src/main/java/com/mirko/glasstodo/data/local/AppDatabase.kt` entero:

```kotlin
package com.mirko.glasstodo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromSync(s: SyncStatus): String = s.name
    @TypeConverter fun toSync(s: String): SyncStatus = SyncStatus.valueOf(s)
}

/**
 * v1 -> v2 adds the task description. Additive and nullable, so no row is rewritten and every
 * existing task simply gets `notes = NULL`.
 *
 * `String?` in the entity makes Room expect a nullable TEXT column: the DDL here must say exactly
 * `TEXT` and never `TEXT NOT NULL`, or the identity-hash check fails when the database is opened.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `notes` TEXT")
    }
}

@Database(entities = [TodoEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
}
```

- [ ] **Step 6: Registrar la migración donde se construye la base de datos**

En `app/src/main/java/com/mirko/glasstodo/di/ServiceLocator.kt`, importar

```kotlin
import com.mirko.glasstodo.data.local.MIGRATION_1_2
```

y cambiar la línea 33 por:

```kotlin
        // Sin addMigrations, subir la versión del esquema crashea al abrir la DB en cada instalación
        // que venga de v1.3.3. Nunca fallbackToDestructiveMigration: borraría las tareas PENDING.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
```

- [ ] **Step 7: Ejecutar el test y comprobar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "*Migration1To2Test*" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 1 test, 0 fallos. Se genera `app/schemas/…/2.json`.

- [ ] **Step 8: Ejecutar toda la suite — nada debe romperse**

```bash
./gradlew testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. (`TodoStoreTest` usa `inMemoryDatabaseBuilder`, que crea la v2 directamente y no pasa por la migración.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/data/local/ app/src/main/java/com/mirko/glasstodo/domain/TodoUi.kt app/src/main/java/com/mirko/glasstodo/di/ServiceLocator.kt app/src/test/java/com/mirko/glasstodo/data/local/Migration1To2Test.kt app/schemas
git commit -m "feat(data): notes en la entidad + migración Room 1->2, con test que la valida"
```

---

### Task 4: `encodeDefaults = true` — el des-tick que se perdía en el sync

`supabase-kt` construye su `Json` activando solo `ignoreUnknownKeys`; `encodeDefaults` queda en el `false` de kotlinx. Así que `toDto()` **omite del JSON** todo campo igual a su default: `done = false`, `project = null`, `priority = 0`, y ahora `notes = null`.

`TodoStore.drainPending()` reenvía con `remote.upsert(row.toDto())`, y un `ON CONFLICT DO UPDATE` solo escribe las columnas presentes en el payload. Un des-tick hecho sin red se pierde: el servidor conserva `done = true`.

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/data/SupabaseClient.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/data/remote/TodoDto.kt`
- Create: `app/src/test/java/com/mirko/glasstodo/data/remote/TodoDtoSerializationTest.kt`

**Interfaces:**
- Consumes: `TodoEntity.notes` (Task 3).
- Produces:
  - `com.mirko.glasstodo.data.SupabaseJson: kotlinx.serialization.json.Json` — el `Json` real que usa el cliente, expuesto para poder testearlo.
  - `TodoDto.notes: String?`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/mirko/glasstodo/data/remote/TodoDtoSerializationTest.kt`:

```kotlin
package com.mirko.glasstodo.data.remote

import com.mirko.glasstodo.data.SupabaseJson
import com.mirko.glasstodo.data.local.SyncStatus
import com.mirko.glasstodo.data.local.TodoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
```

- [ ] **Step 2: Ejecutar el test y comprobar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "*TodoDtoSerializationTest*" --console=plain
```

Esperado: FAIL en compilación — `Unresolved reference: SupabaseJson` y `No value passed for parameter 'notes'`… (el DTO aún no tiene `notes`).

- [ ] **Step 3: Añadir `notes` al DTO**

`app/src/main/java/com/mirko/glasstodo/data/remote/TodoDto.kt` — en la data class, después de `val done: Boolean = false,`:

```kotlin
    val notes: String? = null,
```

y en los dos mapeos:

```kotlin
fun TodoDto.toEntity(status: SyncStatus) = TodoEntity(
    id = id, userId = user_id, title = title, project = project,
    priority = priority, done = done, notes = notes, syncStatus = status,
    createdAt = parseTimestampMillis(created_at) ?: System.currentTimeMillis(),
)

fun TodoEntity.toDto() = TodoDto(
    id = id, user_id = userId, title = title, project = project, priority = priority, done = done,
    notes = notes,
    created_at = Instant.ofEpochMilli(createdAt).toString(),
)
```

- [ ] **Step 4: Exponer y arreglar el `Json` del cliente**

En `app/src/main/java/com/mirko/glasstodo/data/SupabaseClient.kt` añadir los imports:

```kotlin
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
```

Añadir, **antes** de `object SupabaseClient`:

```kotlin
/**
 * The serializer the real client is built with, exposed so tests can pin its behaviour.
 *
 * `encodeDefaults = true` is load-bearing, not cosmetic. supabase-kt's default Json only turns on
 * ignoreUnknownKeys, so kotlinx drops every field that equals its default — `done = false`,
 * `project = null`, `notes = null`. `drainPending()` replays a PENDING row with `upsert(toDto())`,
 * and PostgREST's ON CONFLICT DO UPDATE only writes the columns it was sent: an offline un-tick was
 * silently dropped and the server kept `done = true`.
 *
 * The cost is that a replayed row now overwrites every column it carries (last write wins). Single
 * user, few devices, and the window is one failed push — see §5.5 of the design doc.
 */
val SupabaseJson: Json = Json {
    ignoreUnknownKeys = true    // public.todos has completed_at, which the DTO does not model
    encodeDefaults = true
}
```

y dentro de `createSupabaseClient(...) { … }`, como **primera** línea del bloque:

```kotlin
            defaultSerializer = KotlinXSerializer(SupabaseJson)
```

- [ ] **Step 5: Ejecutar el test y comprobar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "*TodoDtoSerializationTest*" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 6 tests, 0 fallos.

- [ ] **Step 6: Ejecutar toda la suite**

```bash
./gradlew testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Presta atención a `TodoDtoMappingTest`: sigue verde porque `notes` es nullable con default.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/data/ app/src/test/java/com/mirko/glasstodo/data/remote/TodoDtoSerializationTest.kt
git commit -m "fix(sync): encodeDefaults=true — un des-tick offline se perdía en el drain [BUG DE PROD]"
```

---

### Task 5: `update()` — editar una tarea, offline-first

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/data/local/TodoDao.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/data/remote/TodoRemote.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/data/remote/TodoRemoteImpl.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/data/TodoStore.kt`
- Modify: `app/src/test/java/com/mirko/glasstodo/data/TodoStoreTest.kt`

**Interfaces:**
- Consumes: `TodoEntity.notes`, `TodoDto.notes` (Tasks 3 y 4).
- Produces:
  - `TodoRemote.update(id: String, title: String, project: String?, priority: Int, notes: String?)`
  - `TodoStore.update(id: String, title: String, project: String?, priority: Int, notes: String?)`
  - `TodoDao.settleUpdate(...)`, `TodoDao.rollbackUpdate(...)`

- [ ] **Step 1: Escribir los tests que fallan**

En `app/src/test/java/com/mirko/glasstodo/data/TodoStoreTest.kt`, añadir a `FakeRemote` (dentro de la clase, junto a `setDone`):

```kotlin
        val updated = mutableListOf<TodoDto>()
        override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
            calls += "update"; boom()
            rows.replaceAll {
                if (it.id == id) it.copy(title = title, project = project, priority = priority, notes = notes) else it
            }
            updated += TodoDto(id = id, user_id = "u1", title = title, project = project, priority = priority, notes = notes)
        }
```

y añadir al final de la clase `TodoStoreTest`, antes de la última llave:

```kotlin
    // --- update(): editar título / etiqueta / urgencia / descripción desde la hoja de detalle ---

    private fun seed(notes: String? = null) = TodoEntity(
        id = "1", userId = "u1", title = "viejo", project = "casa", priority = 0,
        notes = notes, syncStatus = SyncStatus.SYNCED,
    )

    @Test fun update_isOptimistic_visibleBeforeTheServerAnswers() = runTest {
        db.todoDao().upsert(seed())
        val remote = FakeRemote()
        store(remote).update("1", "nuevo", "aide", 2, "una descripción")

        val e = db.todoDao().byId("1")!!
        assertEquals("nuevo", e.title)
        assertEquals("aide", e.project)
        assertEquals(2, e.priority)
        assertEquals("una descripción", e.notes)
        assertEquals(SyncStatus.SYNCED, e.syncStatus)     // settled: el push fue bien
        assertEquals(listOf("update"), remote.calls)
    }

    @Test fun update_clearsANoteAndATag_sendingRealNulls() = runTest {
        db.todoDao().upsert(seed(notes = "algo que borrar"))
        val remote = FakeRemote()
        store(remote).update("1", "viejo", null, 0, null)

        val e = db.todoDao().byId("1")!!
        assertNull(e.notes)
        assertNull(e.project)
        assertNull(remote.updated.single().notes)
        assertNull(remote.updated.single().project)
    }

    @Test fun update_rollsBack_onPermanentError() = runTest {
        db.todoDao().upsert(seed(notes = "original"))
        runCatching { store(FakeRemote(failStatus = 400)).update("1", "nuevo", "aide", 2, "cambiada") }

        val e = db.todoDao().byId("1")!!
        assertEquals("viejo", e.title)                    // revertido campo a campo
        assertEquals("casa", e.project)
        assertEquals(0, e.priority)
        assertEquals("original", e.notes)
        assertEquals(SyncStatus.SYNCED, e.syncStatus)     // y con el estado de sync que tenía antes
    }

    @Test fun update_keepsOptimistic_onTransientError() = runTest {
        db.todoDao().upsert(seed())
        runCatching { store(FakeRemote(failStatus = 503)).update("1", "nuevo", "casa", 0, "n") }

        val e = db.todoDao().byId("1")!!
        assertEquals("nuevo", e.title)                    // se conserva — el drain lo reintenta
        assertEquals(SyncStatus.PENDING, e.syncStatus)
    }

    @Test fun update_onMissingRow_isANoOp() = runTest {
        val remote = FakeRemote()
        store(remote).update("no-existe", "x", null, 0, null)
        assertEquals(emptyList<String>(), remote.calls)
    }

    @Test fun update_isANoOp_whenNothingChanged() = runTest {
        db.todoDao().upsert(seed(notes = "igual"))
        val remote = FakeRemote()
        store(remote).update("1", "viejo", "casa", 0, "igual")
        assertEquals(emptyList<String>(), remote.calls)   // no se toca la red por una edición vacía
    }

    /**
     * The 2026-07-09 lesson, applied to edits: a push that completes late may only settle ITS OWN
     * value. Here the first edit's push hangs while a second edit lands; when it resolves it must not
     * mark the row SYNCED, because the row no longer holds what that push carried.
     */
    @Test fun update_aSlowFirstPushDoesNotClobberASecondEdit() = runTest {
        db.todoDao().upsert(seed())
        val arrived = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val base = FakeRemote()
        val remote = object : TodoRemote by base {
            var first = true
            override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
                if (first) { first = false; arrived.complete(Unit); gate.await() }
                base.update(id, title, project, priority, notes)
            }
        }
        val s = store(remote)

        val slow = launch { s.update("1", "primera", "casa", 0, null) }
        arrived.await()
        s.update("1", "segunda", "casa", 0, null)         // la segunda edición gana
        gate.complete(Unit)
        slow.join()

        val e = db.todoDao().byId("1")!!
        assertEquals("segunda", e.title)                  // la última acción del usuario manda
        assertEquals(SyncStatus.SYNCED, e.syncStatus)
    }
```

y añadir el import que falta al principio del fichero:

```kotlin
import org.junit.Assert.assertNull
```

- [ ] **Step 2: Ejecutar y comprobar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "*TodoStoreTest*" --console=plain
```

Esperado: FAIL en compilación — `'update' overrides nothing` y `Unresolved reference: update`.

- [ ] **Step 3: Ampliar el contrato remoto**

`app/src/main/java/com/mirko/glasstodo/data/remote/TodoRemote.kt`, añadir después de `setDone`:

```kotlin
    /**
     * A targeted UPDATE of the fields the detail sheet owns. Deliberately NOT `upsert(dto)`: a full
     * DTO would also carry `done`, so a slow edit could clobber a toggle made on another device.
     */
    suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?)
```

- [ ] **Step 4: Implementarlo contra Postgrest**

`app/src/main/java/com/mirko/glasstodo/data/remote/TodoRemoteImpl.kt`, añadir después de `setDone`:

```kotlin
    override suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) {
        remote {
            // PostgrestUpdate has no set(column, null): passing a null through the reified `set` would
            // not compile. `setToNull` emits a real JSON null, which is what lets the sheet CLEAR a
            // note or a tag — an omitted column would just keep the old server value.
            val rows = client.from(TABLE).update({
                set("title", title)
                set("priority", priority)
                if (project == null) setToNull("project") else set("project", project)
                if (notes == null) setToNull("notes") else set("notes", notes)
            }) {
                select()
                filter { eq("id", id) }
            }.decodeList<TodoDto>()
            requireAffected(rows, "update")
        }
    }
```

- [ ] **Step 5: Las consultas guardadas del DAO**

`app/src/main/java/com/mirko/glasstodo/data/local/TodoDao.kt`, añadir después de `rollbackToggle`:

```kotlin
    // Same guard as settleToggle, widened to every field the detail sheet writes. `IS` (not `=`) is
    // SQLite's null-safe equality: `project = NULL` is never true, so `=` would never match a cleared tag.
    @Query(
        """
        UPDATE todos SET syncStatus = 'SYNCED'
        WHERE id = :id AND title = :title AND project IS :project
          AND priority = :priority AND notes IS :notes AND syncStatus = 'PENDING'
        """
    )
    suspend fun settleUpdate(id: String, title: String, project: String?, priority: Int, notes: String?)

    @Query(
        """
        UPDATE todos
           SET title = :prevTitle, project = :prevProject, priority = :prevPriority,
               notes = :prevNotes, syncStatus = :prevStatus
         WHERE id = :id AND title = :attemptedTitle AND project IS :attemptedProject
           AND priority = :attemptedPriority AND notes IS :attemptedNotes AND syncStatus = 'PENDING'
        """
    )
    suspend fun rollbackUpdate(
        id: String,
        prevTitle: String, prevProject: String?, prevPriority: Int, prevNotes: String?,
        prevStatus: SyncStatus,
        attemptedTitle: String, attemptedProject: String?, attemptedPriority: Int, attemptedNotes: String?,
    )
```

- [ ] **Step 6: El `update` optimista en el store**

`app/src/main/java/com/mirko/glasstodo/data/TodoStore.kt`, añadir después de `toggle`:

```kotlin
    /**
     * Edit the fields the detail sheet owns. Same shape as [toggle]: Room first (PENDING), push
     * after, settle/rollback GUARDED so a push that completes late cannot resurrect the values it
     * carried over a newer edit. A no-op edit never touches the network.
     */
    suspend fun update(id: String, title: String, project: String?, priority: Int, notes: String?) =
        withContext(io) {
            val prev = dao.byId(id) ?: return@withContext
            if (prev.title == title && prev.project == project &&
                prev.priority == priority && prev.notes == notes
            ) return@withContext

            dao.upsert(                                                                        // OPTIMISTIC
                prev.copy(
                    title = title, project = project, priority = priority, notes = notes,
                    syncStatus = SyncStatus.PENDING, updatedAt = now(),
                )
            )
            runCatching { remote.update(id, title, project, priority, notes) }
                .onSuccess { dao.settleUpdate(id, title, project, priority, notes) }
                .onFailure { err ->                                                            // ROLLBACK
                    if (err.isPermanent()) {
                        dao.rollbackUpdate(
                            id = id,
                            prevTitle = prev.title, prevProject = prev.project,
                            prevPriority = prev.priority, prevNotes = prev.notes,
                            prevStatus = prev.syncStatus,
                            attemptedTitle = title, attemptedProject = project,
                            attemptedPriority = priority, attemptedNotes = notes,
                        )
                    }
                    throw err
                }
        }
```

- [ ] **Step 7: Ejecutar los tests y comprobar que pasan**

```bash
./gradlew :app:testDebugUnitTest --tests "*TodoStoreTest*" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 0 fallos. Los 7 tests nuevos de `update_*` en verde.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/data/ app/src/test/java/com/mirko/glasstodo/data/TodoStoreTest.kt
git commit -m "feat(data): TodoStore.update — edición optimista con settle/rollback guardado"
```

---

### Task 6: `TaskDetailContent` — el composable que pintan los dos anfitriones

**Files:**
- Create: `app/src/main/java/com/mirko/glasstodo/ui/TaskDetailSheet.kt`
- Create: `app/src/main/java/com/mirko/glasstodo/ui/UrgencyChip.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/ui/TodoScreen.kt` (quitar el `UrgencyChip` privado)
- Create: `app/src/test/java/com/mirko/glasstodo/ui/TaskDetailScreenshotTest.kt`

**Interfaces:**
- Consumes: `TodoUi.notes` (Task 3), `TaskCheck` (`ui/TaskRow.kt`), `Urgency`, `urgencyColor`, tokens de `ui/theme/Theme.kt`.
- Produces:
  - `TaskDetailContent(task: TodoUi, onToggle: (Boolean) -> Unit, onSave: (title: String, project: String?, priority: Int, notes: String?) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier)` — stateless salvo el texto que se está escribiendo.
  - `UrgencyChip(level: Urgency, selected: Boolean, onClick: () -> Unit)` — `internal`, compartido por `AddDock` y por la hoja.

- [ ] **Step 1: Sacar `UrgencyChip` a su propio fichero**

Crear `app/src/main/java/com/mirko/glasstodo/ui/UrgencyChip.kt` con **exactamente** el cuerpo que hoy vive en `TodoScreen.kt` (líneas 312-330), cambiando `private` por `internal`:

```kotlin
package com.mirko.glasstodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Hairline

/** Shared by the add dock and the detail sheet: the same three levels must look the same in both. */
@Composable
internal fun UrgencyChip(level: Urgency, selected: Boolean, onClick: () -> Unit) {
    val accent = urgencyColor(level)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) accent else Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            level.label,
            color = if (selected) Chalk else Chalk3,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
```

Y **borrar** de `TodoScreen.kt` la función `private fun UrgencyChip(...)` completa (líneas 312-330) junto con los imports que dejen de usarse (`border`, `Color`, `clip` sólo si ya no se usan en el resto del fichero — `clip` y `Color` sí se siguen usando en `Header` y `AddDock`, `border` no).

- [ ] **Step 2: Escribir el screenshot test que falla**

Crear `app/src/test/java/com/mirko/glasstodo/ui/TaskDetailScreenshotTest.kt`:

```kotlin
package com.mirko.glasstodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.ui.theme.Ink
import com.mirko.glasstodo.ui.theme.ListoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet's content, rendered on the JVM. Not a golden-diff gate — it is how the design gets looked
 * at at all, since there is no emulator here. Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class TaskDetailScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, task: TodoUi) {
        compose.setContent {
            ListoTheme {
                TaskDetailContent(
                    task = task,
                    onToggle = {},
                    onSave = { _, _, _, _ -> },
                    onDelete = {},
                    modifier = Modifier.fillMaxSize().background(Ink),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/detail_$name.png")
    }

    @Test fun withNotes() = capture(
        "with_notes",
        TodoUi("1", "Llamar a Texlink", "texlink", done = false, priority = 2,
            notes = "Confirmar el precio del informe antes del viernes. Si acepta, arrancar tracking."),
    )

    @Test fun withoutNotes() = capture(
        "without_notes",
        TodoUi("2", "Copiar top-10 URLs a GSC", "drinks", done = false, priority = 0),
    )

    @Test fun done() = capture(
        "done",
        TodoUi("3", "Widget Glance", "glass-todo", done = true, priority = 2, notes = "Entregado en v1.3.3."),
    )
}
```

- [ ] **Step 3: Ejecutar y comprobar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "*TaskDetailScreenshotTest*" --console=plain
```

Esperado: FAIL en compilación — `Unresolved reference: TaskDetailContent`.

- [ ] **Step 4: Escribir `TaskDetailContent` y `TaskDetailSheet`**

Crear `app/src/main/java/com/mirko/glasstodo/ui/TaskDetailSheet.kt`:

```kotlin
package com.mirko.glasstodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mirko.glasstodo.domain.TodoUi
import com.mirko.glasstodo.domain.Urgency
import com.mirko.glasstodo.ui.theme.Chalk
import com.mirko.glasstodo.ui.theme.Chalk2
import com.mirko.glasstodo.ui.theme.Chalk3
import com.mirko.glasstodo.ui.theme.Cyan
import com.mirko.glasstodo.ui.theme.Hairline
import com.mirko.glasstodo.ui.theme.Ink

/**
 * The whole task, on one surface. Rendered by TWO hosts — a ModalBottomSheet inside the app, and the
 * translucent TaskDetailActivity the widget launches — so app and widget cannot drift apart. That
 * drift is exactly what v1.3.0 shipped and v1.3.1 had to undo.
 *
 * Stateless except for the text being typed. Nothing is committed until [onSave]; the check is the
 * one control that writes straight through, because a tick is not a draft.
 */
@Composable
fun TaskDetailContent(
    task: TodoUi,
    onToggle: (Boolean) -> Unit,
    onSave: (title: String, project: String?, priority: Int, notes: String?) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var project by rememberSaveable(task.id) { mutableStateOf(task.project.orEmpty()) }
    var notes by rememberSaveable(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var priority by rememberSaveable(task.id) { mutableIntStateOf(task.priority) }
    val urgency = Urgency.of(priority)

    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp)) {

        // The tick lives on the circle here too — the whole point of the redesign.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaskCheck(task.done) { onToggle(!task.done) }
            Spacer(Modifier.width(14.dp))
            Text(
                if (task.done) "Hecha" else "Pendiente",
                color = if (task.done) Cyan else Chalk3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.14.em,
            )
        }

        Spacer(Modifier.height(18.dp))

        Field(value = title, onValue = { title = it }, placeholder = "Título", size = 22.sp)

        Spacer(Modifier.height(4.dp))

        Field(value = project, onValue = { project = it }, placeholder = "Etiqueta", size = 13.sp, prefix = "#")

        Spacer(Modifier.height(18.dp))
        Rule()
        Spacer(Modifier.height(18.dp))

        Text("URGENCIA", color = Chalk3, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.14.em)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Urgency.entries.forEach { level ->
                UrgencyChip(level = level, selected = urgency == level, onClick = { priority = level.priority })
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("DESCRIPCIÓN", color = Chalk3, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.14.em)
        Spacer(Modifier.height(4.dp))
        Field(
            value = notes,
            onValue = { notes = it },
            placeholder = "Lo que haga falta recordar",
            size = 15.sp,
            singleLine = false,
            modifier = Modifier.heightIn(min = 96.dp),
        )

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDelete) {
                Text("Borrar", color = Chalk3, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title.trim(), project.blankToNull(), priority, notes.blankToNull()) },
            ) {
                Text("Guardar", color = if (title.isNotBlank()) Cyan else Chalk3, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** "" and null both mean "no tag" / "no description"; the store and the server only speak null. */
private fun String.blankToNull(): String? = trim().takeIf { it.isNotBlank() }

@Composable
private fun Rule() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(Hairline))

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    size: androidx.compose.ui.unit.TextUnit,
    prefix: String = "",
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValue,
        singleLine = singleLine,
        placeholder = { Text("$prefix$placeholder", color = Chalk3, fontSize = size) },
        prefix = if (prefix.isEmpty()) null else { { Text(prefix, color = Chalk3, fontSize = size) } },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = size),
        // No card, no box: the field sits on the ground, exactly like the add dock.
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Chalk,
            unfocusedTextColor = Chalk,
            cursorColor = Cyan,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The in-app host. The Activity host (widget/TaskDetailActivity) renders the SAME
 * [TaskDetailContent]; only the dismiss wiring differs (there it is `finish()`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    task: TodoUi,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: (title: String, project: String?, priority: Int, notes: String?) -> Unit,
    onDelete: () -> Unit,
) {
    // The default drag handle stays: it is the affordance that says "arrástrame para cerrar".
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
    ) {
        TaskDetailContent(task = task, onToggle = onToggle, onSave = onSave, onDelete = onDelete)
    }
}
```

Imports que hay que añadir al fichero y que no están en la lista de arriba: `androidx.compose.foundation.background`. Y quitar `Chalk2` de los imports: no se usa.

- [ ] **Step 5: Ejecutar y comprobar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "*TaskDetailScreenshotTest*" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 3 tests. Se escriben 3 PNG en `app/build/outputs/roborazzi/detail_*.png`.

- [ ] **Step 6: Mirar los PNG**

Abrir `app/build/outputs/roborazzi/detail_with_notes.png`, `detail_without_notes.png` y `detail_done.png`. Comprobar: fondo Ink, el círculo cian relleno solo en `done`, la etiqueta con `#`, los tres chips de urgencia, y que «Guardar» está en cian.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/ui/ app/src/test/java/com/mirko/glasstodo/ui/TaskDetailScreenshotTest.kt
git commit -m "feat(ui): TaskDetailContent — la hoja de la tarea, un composable para los dos anfitriones"
```

---

### Task 7: La app — fila en dos zonas y la hoja

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/ui/TaskRow.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/ui/TodoScreen.kt`
- Modify: `app/src/main/java/com/mirko/glasstodo/ui/TodoViewModel.kt`
- Modify: `app/src/test/java/com/mirko/glasstodo/ui/ScreenshotTest.kt`

**Interfaces:**
- Consumes: `TaskDetailSheet` (Task 6), `TodoStore.update` (Task 5).
- Produces:
  - `TaskRow(task: TodoUi, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier)`
  - `TodoViewModel.update(id, title, project, priority, notes)`
  - `TodoScreenContent(state, onToggle, onAdd, onOpen, onErrorShown)`

- [ ] **Step 1: La fila cede el tap al detalle**

`app/src/main/java/com/mirko/glasstodo/ui/TaskRow.kt` — cambiar la firma y el `clickable`, y corregir el comentario que ahora es mentira:

```kotlin
/**
 * No card. The row sits on the ground. The circle ticks; the rest of the row opens the task — the
 * same split the widget makes, so the two surfaces teach the same gesture.
 * Urgency is a rule on the leading edge, and never on a done task.
 */
@Composable
fun TaskRow(task: TodoUi, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val urgency = Urgency.of(task.priority)
    val showUrgency = urgency != Urgency.NORMAL && !task.done

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .alpha(if (task.pending) 0.55f else 1f)     // written locally, not yet on the server
            .padding(start = 20.dp, end = 24.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
```

El resto del cuerpo no cambia: `TaskCheck(task.done, onToggle)` ya recibe su propio `onToggle`, y en Compose el hijo clickable consume el tap antes que la fila.

En `TaskCheck`, dar el blanco de 48dp sin crecer el círculo (aquí sí existe la API que Glance no tiene):

```kotlin
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(22.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }   // graphicsLayer, NOT offset → no relayout
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
```

con el import `androidx.compose.material3.minimumInteractiveComponentSize`.

- [ ] **Step 2: El ViewModel gana `update`**

`app/src/main/java/com/mirko/glasstodo/ui/TodoViewModel.kt`, después de `toggle`:

```kotlin
    fun update(id: String, title: String, project: String?, priority: Int, notes: String?) =
        viewModelScope.launch {
            runCatching { store.update(id, title, project, priority, notes) }.onFailure {
                if (it is CancellationException) throw it
                if (it.isPermanent()) errors.value = "No se pudo guardar; cambios revertidos"
            }
        }
```

- [ ] **Step 3: La pantalla abre la hoja**

`app/src/main/java/com/mirko/glasstodo/ui/TodoScreen.kt`:

En `TodoScreen(vm)`:

```kotlin
@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    TodoScreenContent(
        state = state,
        onToggle = { id, done -> vm.toggle(id, done) },
        onAdd = { raw, urgency -> vm.add(raw, urgency) },
        onOpen = { id -> openId = id },
        onErrorShown = { vm.errorShown() },
    )

    // Read the task from the SAME flow the list reads: an edit lands in Room and the sheet re-renders
    // with it. Holding a copy here would go stale the moment realtime pushed a change.
    val open = openId?.let { id -> state.todos.firstOrNull { it.id == id } }
    if (open != null) {
        TaskDetailSheet(
            task = open,
            sheetState = sheetState,
            onDismiss = { openId = null },
            onToggle = { done -> vm.toggle(open.id, done) },
            onSave = { title, project, priority, notes ->
                vm.update(open.id, title, project, priority, notes)
                openId = null
            },
            onDelete = {
                vm.delete(open.id)
                openId = null
            },
        )
    }
}
```

con los imports nuevos:

```kotlin
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
```

y anotar `TodoScreen` con `@OptIn(ExperimentalMaterial3Api::class)`.

En `TodoScreenContent`, añadir el parámetro `onOpen: (String) -> Unit = {}` después de `onAdd`, y pasarlo a las dos llamadas de `TaskRow`:

```kotlin
                items(pending, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { onToggle(task.id, true) },
                        onOpen = { onOpen(task.id) },
                        modifier = Modifier.animateItem(
```

```kotlin
                    items(done, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { onToggle(task.id, false) },
                            onOpen = { onOpen(task.id) },
                            modifier = Modifier.animateItem(
```

- [ ] **Step 4: Arreglar el screenshot test existente**

`app/src/test/java/com/mirko/glasstodo/ui/ScreenshotTest.kt`, línea 41:

```kotlin
                TodoScreenContent(state = state, onToggle = { _, _ -> }, onAdd = { _, _ -> }, onOpen = {})
```

- [ ] **Step 5: Ejecutar toda la suite**

```bash
./gradlew testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Si `ScreenshotTest` no compila, falta el `onOpen` del paso 4.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/ui/ app/src/test/java/com/mirko/glasstodo/ui/ScreenshotTest.kt
git commit -m "feat(app): el círculo marca, la fila abre la tarea"
```

---

### Task 8: El widget — fila en dos zonas y la Activity translúcida

**Files:**
- Modify: `app/src/main/java/com/mirko/glasstodo/widget/WidgetGlanceContent.kt` (`TodoRow`, líneas 318-393, y el comentario de 324-330)
- Create: `app/src/main/java/com/mirko/glasstodo/widget/TaskDetailActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/mirko/glasstodo/widget/WidgetScreenshotTest.kt`

**Interfaces:**
- Consumes: `TaskDetailContent` (Task 6), `TodoStore.update` (Task 5), `ToggleTodoAction` (existente).
- Produces: `TaskDetailActivity`, lanzable con `actionStartActivity<TaskDetailActivity>(actionParametersOf(TaskDetailActivity.idKey to id))`.

- [ ] **Step 1: Un scope de proceso para las escrituras que sobreviven a la hoja**

Una escritura lanzada desde la hoja tiene que sobrevivir al `finish()` de la Activity. `lifecycleScope` se cancela con ella; el scope de proceso que `ServiceLocator` ya tiene, no.

En `app/src/main/java/com/mirko/glasstodo/di/ServiceLocator.kt`, cambiar

```kotlin
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

por

```kotlin
    /** Process-lifetime scope. A write started from a sheet that then closes must not be cancelled. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

- [ ] **Step 2: La Activity que hospeda la hoja**

Crear `app/src/main/java/com/mirko/glasstodo/widget/TaskDetailActivity.kt`:

```kotlin
package com.mirko.glasstodo.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.updateAll
import com.mirko.glasstodo.data.AuthRepository
import com.mirko.glasstodo.data.TodoStore
import com.mirko.glasstodo.di.ServiceLocator
import com.mirko.glasstodo.ui.TaskDetailSheet
import com.mirko.glasstodo.ui.theme.ListoTheme
import kotlinx.coroutines.launch

/**
 * The widget's half of the detail sheet. A widget cannot host an editable field at all — EditText is
 * not on the RemoteViews whitelist, in any API level — so "open the task" has to mean "open an
 * Activity". This one is translucent, so the sheet rises over the home screen with the launcher still
 * visible behind it, and it renders the SAME TaskDetailContent the app does.
 *
 * The theme is declared in the manifest as @android:style/Theme.Translucent.NoTitleBar. It must NOT
 * declare android:screenOrientation: on API 26 a translucent activity that requests an orientation
 * throws `Only fullscreen opaque activities can request orientation`.
 */
@OptIn(ExperimentalMaterial3Api::class)
class TaskDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Glance serialises ActionParameters as Intent extras, keyed by the ActionParameters.Key name.
        val id = intent.getStringExtra(ID_KEY_NAME)
        if (id.isNullOrBlank()) { finish(); return }

        val store = ServiceLocator.store(applicationContext)

        setContent {
            // `initial = emptyList()` means the first frame has no task yet. Only a LOADED list that
            // does not contain the id proves the row is gone (deleted from the app or another device).
            val todos by store.observeTodos().collectAsState(initial = emptyList())
            val task = todos.firstOrNull { it.id == id }
            val gone = task == null && todos.isNotEmpty()

            // finish() belongs in an effect, never in the composition itself.
            LaunchedEffect(gone) { if (gone) finish() }
            if (task == null) return@setContent

            val sheetState = rememberModalBottomSheetState()
            val scope = rememberCoroutineScope()

            // A gesture, a scrim tap or back already ran sheetState.hide() before Material3 invokes
            // onDismissRequest, so finish() there does not cut the exit animation. The BUTTONS bypass
            // those paths, so they have to animate the sheet out by hand.
            fun hideThenFinish() = scope.launch {
                runCatching { sheetState.hide() }
                finish()
            }

            ListoTheme {
                TaskDetailSheet(
                    task = task,
                    sheetState = sheetState,
                    onDismiss = { finish() },
                    onToggle = { done -> commit { it.toggle(id, done) }; hideThenFinish() },
                    onSave = { title, project, priority, notes ->
                        commit { it.update(id, title, project, priority, notes) }
                        hideThenFinish()
                    },
                    onDelete = { commit { it.delete(id) }; hideThenFinish() },
                )
            }
        }
    }

    /**
     * Launched from a widget, so the process may be cold with a token that expired hours ago;
     * Postgrest would fall back to the anon key and RLS would silently drop the write.
     *
     * ServiceLocator.appScope, not lifecycleScope: this Activity is about to finish, and a write
     * cancelled halfway would leave Room optimistic and the server untouched.
     */
    private fun commit(block: suspend (TodoStore) -> Unit) {
        val app = applicationContext
        ServiceLocator.appScope.launch {
            runCatching { AuthRepository().ensureFreshSession() }
            runCatching { block(ServiceLocator.store(app)) }
            TodoGlanceWidget().updateAll(app)
        }
    }

    companion object {
        const val ID_KEY_NAME = "todo_id"
        val idKey = ActionParameters.Key<String>(ID_KEY_NAME)
    }
}
```

- [ ] **Step 3: La fila del widget, en dos zonas**

`app/src/main/java/com/mirko/glasstodo/widget/WidgetGlanceContent.kt` — reemplazar `TodoRow` entera (líneas 318-393) por:

```kotlin
@Composable
private fun TodoRow(todo: TodoUi, showTag: Boolean = true, compact: Boolean = false) {
    // The Row itself is NOT clickable: two sibling zones cover disjoint rectangles, so which action
    // fires never depends on parent/child touch dispatch.
    //
    // Inside a LazyColumn, Glance compiles BOTH zones to RemoteViews.setOnClickFillInIntent against
    // the collection's single (MUTABLE) setPendingIntentTemplate — the same path the toggle already
    // takes in production today. Each zone gets its own view id and its own unique data-Uri, so the
    // two fill-ins cannot be confused for one another.
    //
    // Each action carries the user's INTENT (the negation of what this row SHOWS), never just the id:
    // deriving the new value from Room at tap time inverted the tap whenever Room had moved under a
    // stale render («no puedo destickearlo», 2026-07-09).
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

        // Urgency reads as a rule on the leading edge. Normal shows nothing — the absence of a
        // signal IS the signal — and a done task never shouts, whatever its urgency was.
        val urgency = Urgency.of(todo.priority)
        if (urgency != Urgency.NORMAL && !todo.done) {
            Spacer(
                GlanceModifier
                    .width(3.dp)
                    .height(22.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(urgencyColor(urgency)))
            )
        } else {
            Spacer(GlanceModifier.width(3.dp))
        }

        // ZONE 1 — the tick. Glance has no minimumInteractiveComponentSize: the touch target IS the
        // rendered box. Height is the scarce resource on a home screen and width is not, so the zone
        // is paid for in width (39dp) and in the row's own vertical padding, never in row height.
        //
        // Deliberately an Image, never Glance's CheckBox: on API 31+ a CompoundButton is clickable and
        // focusable at the View level and swallows the touch of the whole row (the v1.1.0 bug).
        Box(
            modifier = GlanceModifier
                .width(39.dp)
                .padding(vertical = 6.dp)
                .clickable(
                    actionRunCallback<ToggleTodoAction>(
                        actionParametersOf(
                            ToggleTodoAction.idKey to todo.id,
                            ToggleTodoAction.doneKey to !todo.done,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(if (todo.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
                contentDescription = if (todo.done) DONE_ICON_DESCRIPTION else PENDING_ICON_DESCRIPTION,
                modifier = GlanceModifier.size(20.dp),
            )
        }

        // ZONE 2 — everything else opens the task. actionStartActivity is the one action that needs no
        // trampoline: the fill-in points straight at the Activity.
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(vertical = 6.dp)
                .clickable(
                    actionStartActivity<TaskDetailActivity>(
                        actionParametersOf(TaskDetailActivity.idKey to todo.id)
                    )
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    todo.title,
                    // Compact placements: one full-width line per task, or a single wrapped title eats
                    // the only row that fits and the widget glances as nothing.
                    maxLines = if (compact) 1 else 2,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = if (todo.done) chalk3 else chalk,
                        fontSize = 14.sp,
                        textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    ),
                )
                if (showTag) {
                    todo.project?.takeIf { it.isNotBlank() }?.let { tag ->
                        Spacer(GlanceModifier.width(8.dp))
                        // A typographic label, not a coloured chip: the accent belongs to the check.
                        Text(
                            tag.uppercase(),
                            maxLines = 1,
                            style = TextStyle(color = chalk3, fontSize = 9.sp),
                        )
                    }
                }
            }
        }
    }
}
```

Añadir los imports que faltan a ese fichero:

```kotlin
import androidx.glance.layout.Box
```

`actionStartActivity`, `Alignment`, `Spacer`, `padding`, `size` ya están importados.

> **Geometría:** hoy el título arranca en 11 + 20 + 10 = **41dp** desde el borde de la `Row`
> (o 3 + 8 + 20 + 10 con la barra de urgencia). Con `Spacer(3dp) + Box(39dp)` arranca en **42dp**, y el
> círculo de 20dp queda centrado en el Box: de 12,5dp a 32,5dp, contra 11–31dp de hoy. **1,5dp a la
> derecha.** Si el screenshot del paso 6 lo delata, bajar el `Box` a 36dp.

- [ ] **Step 4: Borrar el comentario que es falso**

En el mismo fichero, el bloque de comentario de las antiguas líneas 324-330 («THE tap fix… Glance registers one typed action per row — no template, nothing to merge») ya no existe: lo sustituye el comentario del paso 3. Corregir también `AndroidManifest.xml` (líneas 28-32), sustituyendo el comentario del `<receiver>` por:

```xml
        <!-- Glance widget. Replaces the v1 RemoteViews collection widget (TodoWidgetProvider +
             TodoWidgetService), whose per-row taps rode a hand-rolled PendingIntent template plus
             per-row fill-in extras and failed silently on some launchers.
             Glance ALSO uses template + fill-in inside a LazyColumn — verified in the 1.1.1 bytecode,
             ApplyActionKt.applyAction branches on isLazyCollectionDescendant(). The difference is that
             it builds the template correctly (FLAG_MUTABLE), and it binds one fill-in per view id, so
             a row can carry two independent actions: the circle ticks, the rest opens the task. -->
```

- [ ] **Step 5: Registrar la Activity**

En `app/src/main/AndroidManifest.xml`, después del bloque de `QuickAddActivity`:

```xml
        <!-- The detail sheet, opened by tapping a row in the widget. Translucent so the launcher stays
             visible behind the sheet. It must NOT declare android:screenOrientation: on API 26 a
             translucent activity that requests an orientation throws
             "Only fullscreen opaque activities can request orientation". -->
        <activity
            android:name=".widget.TaskDetailActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 6: Los screenshots del widget siguen verdes**

```bash
./gradlew :app:testDebugUnitTest --tests "*WidgetScreenshotTest*" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 6 tests. Abrir `app/build/outputs/roborazzi/widget_list.png` y `widget_small.png` y comprobar que **el círculo no se ha movido** respecto a la versión anterior y que la altura de fila es la misma.

- [ ] **Step 7: Toda la suite**

```bash
./gradlew testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mirko/glasstodo/widget/ app/src/main/java/com/mirko/glasstodo/di/ServiceLocator.kt app/src/main/AndroidManifest.xml
git commit -m "feat(widget): el círculo marca, el resto de la fila abre la tarea"
```

---

### Task 9: Versión, build de release y la verificación que ningún test puede firmar

**Files:**
- Modify: `app/build.gradle.kts:52-53`
- Modify: `V2-PROGRESS.md`

- [ ] **Step 1: Subir la versión**

`app/build.gradle.kts`:

```kotlin
        versionCode = 12
        versionName = "1.4.0"
```

- [ ] **Step 2: Suite completa + APK**

```bash
export JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10"
export ANDROID_HOME="C:/Users/mirko/tools/android-sdk"
./gradlew testDebugUnitTest assembleDebug --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 0 fallos, y `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: `[no-automatizable]` — verificar los dos taps en el móvil**

Robolectric no monta un `AppWidgetHost`, y los fill-in intents los resuelve el proceso del launcher. Esto **no lo puede firmar ningún test**. Instalar el APK sobre la v1.3.3 ya instalada (misma firma, actualización en sitio) y comprobar, con el widget en el escritorio:

1. Tap en el **círculo** de una tarea **sin urgencia** → se marca. Vuelve a tocarlo → se desmarca.
2. Tap en el **círculo** de una tarea **con urgencia** (barra de color) → se marca. La barra desaparece.
3. Tap en el **título** de una tarea → sube la hoja, con el escritorio visible detrás.
4. En la hoja: escribir una descripción, «Guardar» → la hoja baja. Reabrir la tarea → la descripción está.
5. En la hoja: borrar la descripción entera, «Guardar», reabrir → sigue vacía (esto prueba el `setToNull`).
6. Arrastrar la hoja hacia abajo → se cierra sin cerrar la app.
7. La app ya instalada **arranca** (esto prueba la migración de Room sobre datos reales de la v1).

Si (1) o (3) fallan en el launcher, **parar**: es el riesgo que el spec marcó como no automatizable, y hay que investigar antes de publicar.

- [ ] **Step 4: Anotar el estado en el handoff**

En `V2-PROGRESS.md`, añadir bajo «EMPIEZA AQUÍ» una entrada con: v1.4.0, qué cambió el gesto, el bug de `encodeDefaults` arreglado, y el resultado literal de los 7 puntos del paso 3.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts V2-PROGRESS.md
git commit -m "release: v1.4.0 — el panel de detalle de tarea"
```

---

## Cobertura del spec

| Sección del spec | Task |
|---|---|
| §3.1 la hoja es una Activity | 8 |
| §3.2 tema translúcido sin XML, sin `screenOrientation` | 8 |
| §3.3 cierre de la hoja | 6, 8 |
| §4.1 fila del widget en dos zonas | 8 |
| §4.2 fila de la app en dos zonas | 7 |
| §5.1 columna en Postgres | 1 |
| §5.2 migración de Room | 2, 3 |
| §5.3 `encodeDefaults` | 4 |
| §5.4 `update` dirigido con `setToNull` | 5 |
| §5.5 last-write-wins aceptado | 4 (comentario en `SupabaseJson`) |
| §8 verificación manual | 9 |
