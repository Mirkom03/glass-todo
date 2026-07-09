# GLASS TODO v2 — UNIFIED REBUILD BLUEPRINT

Author: Android tech lead. Scope: full rebuild of `C:/Users/mirko/glass-todo` keeping the RLS/DB and the visual identity, fixing the four shipping bugs (unreliable widget taps, empty widget under background/anon race, no offline, 15-min polling) and the meta-bug (no tests, so regressions ship).

---

## 1. DECISION RATIONALE + HONEST CRITIQUE OF v1

### 1.1 The architecture we are committing to

```
┌─────────────── ONE PROCESS, ONE SOURCE OF TRUTH ───────────────┐
│                                                                 │
│  Supabase (Postgres + RLS, UNCHANGED)                           │
│     ▲ writes (Postgrest, supabase-kt)   │ realtime stream       │
│     │                                   ▼ (postgres_changes)    │
│  TodoRepository ── optimistic ──►  ROOM (SSOT, Flow) ◄── realtime│
│     ▲                                   │  ▲                     │
│     │                                   │  │                     │
│  ViewModel/StateFlow                    │  │ collectAsState      │
│     ▲                                   │  │ (inside provide-    │
│     │ collectAsStateWithLifecycle       │  │  Content)           │
│  Compose UI (optimistic, states)   Glance widget (reliable taps) │
│                                                                 │
│  WorkManager CoroutineWorker: token refresh + safety-net pull   │
│                     (only mechanism the widget relies on when    │
│                      the app process is dead)                    │
└─────────────────────────────────────────────────────────────────┘
```

The single decision that resolves every v1 bug is **Room as the one place a write lands that BOTH the UI and the widget observe**. Every other choice hangs off it.

### 1.2 The five pillar decisions and why

| Decision | Chosen | Rejected | Why (root-cause it kills) |
|---|---|---|---|
| Widget layer | **Jetpack Glance 1.1.1** | classic RemoteViews + `RemoteViewsService.Factory` | v1's unreliable taps are caused by `setPendingIntentTemplate` + `setOnClickFillInIntent` fill-in-intent extra-merging, which silently fails across OEM launchers → `onReceive` hits `getStringExtra(EXTRA_ID) ?: return` → checkbox looks dead. Glance's per-item `actionRunCallback<T>()` + `actionParametersOf()` is type-safe and individually registered — no merging, no trampoline. Callbacks run in a WorkManager worker (~10 min budget) not a ~10 s BroadcastReceiver. |
| App data layer | **Room 2.8.3 offline-first SSOT, DAO exposes `Flow`** | one-shot `repo.list()` into composable `mutableStateOf` | v1 is online-only, non-reactive: a widget/other-device edit never reaches the open screen; airplane mode = blank screen; toggle failures are swallowed with no rollback; rotate = re-fetch + lost input text. Room `Flow` makes the UI a pure function of one observable store. |
| Sync | **supabase-kt Realtime → Room** (`postgresChangeFlow`/`selectAsFlow`) | 15-min WorkManager poll + raw-OkHttp `SupabaseTodosRest` | Live sync off one WebSocket instead of polling; deletes the divergent hand-rolled JSON REST layer that duplicated token logic. WorkManager demoted to a low-frequency safety net + token refresh for the app-dead case. |
| Presentation | **ViewModel + `StateFlow` via `stateIn(WhileSubscribed(5000))`, immutable `UiState`, optimistic writes** | composable-local state + `rememberCoroutineScope()` | Survives rotation/process death; explicit loading/empty/error (v1 collapses "network failed" and "empty" into one blank screen); optimistic add/toggle/delete with rollback in the repository, not hand-mutated in the composable. |
| Quality gate | **CI: Roborazzi (JVM screenshot) + Glance unit test + one UIAutomator emulator test** | "looks fine on my screenshot" | Mirko cannot run Android locally. The JVM tier (unit + Turbine + Compose + Roborazzi + Glance-logic) runs on GitHub runners with no emulator and catches ~80% of bugs in ~30 s; a cached-AVD emulator job proves end-to-end widget pin+tap. This is the fix for "no tests so bugs ship." |

### 1.3 Honest critique of v1 (what is broken, what is actually right)

**Genuinely broken — must fix:**
1. **Widget taps (root cause):** fill-in-intent merging into a shared mutable template. Non-deterministic across launchers. *(Brief 1)*
2. **Empty widget under RLS (misdiagnosed in v1 comments):** it is **not** primarily an `awaitInitialization` race — `currentAccessTokenBlocking()` already awaits. The real cause is supabase-kt Auth's default `enableLifecycleCallbacks=true`, which **clears the in-memory session on `ON_STOP`**. A background WorkManager/binder refresh then reads a null token → falls back to ANON → RLS returns nothing. Fix = `enableLifecycleCallbacks=false` + own the refresh. *(Brief 3)*
3. **No offline / no reactivity:** `TodoScreen` holds `remember{ mutableStateOf<List<Todo>>() }`, pulled once in `LaunchedEffect(Unit){ repo.list() }`. No Room anywhere in the read path. *(Briefs 2, 3)*
4. **Swallowed failures / lost updates:** toggle hand-mutates the list then `scope.launch{ runCatching{ repo.setDone() } }` eats the error — server rejects, UI stays toggled, DB never changed, next reload silently reverts. No rollback. *(Brief 2)*
5. **`runBlocking` network on the binder thread** in `onDataSetChanged` — ANR-class stalls, dropped widget updates. *(Brief 4)*
6. **Double round-trip add:** `repo.add()` then `tasks = repo.list()`; offline nothing appears. *(Brief 2)*
7. **Zero automated tests.** *(Brief 5)*

**Actually right in v1 — preserve, do not "fix":**
- **Haze topology is correct.** Only the two static bars call `hazeEffect`; `TaskRow` is a cheap translucent card. This is the #1 liquid-glass perf rule and v1 follows it. Keep it. *(Brief 6)*
- **The `+` add button is already correct** (`getActivity` + `FLAG_IMMUTABLE`, avoiding the Android-12 broadcast→activity trampoline ban). Glance's `actionStartActivity` preserves this correctness. *(Brief 1)*
- **`animateItem` uses a stable `key = { it.id }`;** checkbox animates via `graphicsLayer` scale (no relayout); aurora is lifecycle- and reduced-motion-gated. Keep all three. *(Brief 6)*
- **API-31 corner-radius tokens** in `values-v31/dimens.xml`. Keep, extend with Material You accent. *(Brief 6)*

**Deliberately NOT changing (avoid churn):** Haze stays **1.7.2** (2.0 is alpha and breaks the API surface); material3 stays **1.4.0 stable** with a hand-rolled `Motion.kt` (Expressive `MotionScheme` needs 1.5.0-alpha — optional, flagged §7); Room stays **2** (Room 3 is KSP-only/KMP, no benefit for an Android-only app); the glass aesthetic in the widget is **not** reproducible (RemoteViews/Glance cannot blur — accept a flat rounded ColorProvider).

### 1.4 Polish decisions folded in (Brief 6, cheap wins)
Haptics (`ToggleOn`/`ToggleOff`/`Confirm`), a shared `Motion.kt`, sealed `UiState` with shimmer/empty/error, glass depth (distinct materials + specular top edge + `HazeInputScale.Auto`), decorrelated aurora blobs, optimistic *add* (not just toggle), and widget Material You. These ride on the architecture rebuild rather than a separate pass.

---

## 2. FULL TARGET FILE TREE

```
glass-todo/
├─ .github/workflows/android-ci.yml            # NEW — JVM (required) + emulator jobs
├─ gradle/libs.versions.toml                   # EDIT — Room/KSP/Realtime/DataStore/test deps
├─ settings.gradle.kts
├─ build.gradle.kts                            # EDIT — ksp + roborazzi plugin aliases
├─ supabase/
│  └─ 001_realtime_publication.sql             # NEW — REPLICA IDENTITY FULL + publication (RLS unchanged)
└─ app/
   ├─ build.gradle.kts                         # EDIT — ksp(room-compiler), roborazzi, testOptions
   ├─ src/
   │  ├─ main/
   │  │  ├─ AndroidManifest.xml                # EDIT — GlanceAppWidgetReceiver, scope backup, drop REQUEST_INSTALL_PACKAGES if updater cut
   │  │  ├─ java/com/mirko/glasstodo/
   │  │  │  ├─ App.kt                          # EDIT — init ServiceLocator, schedule worker
   │  │  │  ├─ di/
   │  │  │  │  └─ ServiceLocator.kt            # NEW — singletons: DB, client, repo (shared app+widget)
   │  │  │  ├─ data/
   │  │  │  │  ├─ SupabaseClient.kt            # EDIT — install(Realtime), enableLifecycleCallbacks=false
   │  │  │  │  ├─ local/
   │  │  │  │  │  ├─ AppDatabase.kt            # NEW
   │  │  │  │  │  ├─ TodoDao.kt                # NEW — Flow reads + suspend writes
   │  │  │  │  │  └─ TodoEntity.kt             # NEW — SyncStatus, tombstone, client UUID PK
   │  │  │  │  ├─ remote/
   │  │  │  │  │  ├─ TodoRemote.kt             # NEW — thin supabase-kt Postgrest wrapper (interface)
   │  │  │  │  │  ├─ TodoRemoteImpl.kt         # NEW — replaces SupabaseTodosRest + TodoRepository REST
   │  │  │  │  │  └─ TodoDto.kt                # NEW — @Serializable row
   │  │  │  │  ├─ TodoRepository.kt            # REWRITE — offline-first SSOT, optimistic + rollback
   │  │  │  │  └─ RealtimeSync.kt              # NEW — selectAsFlow/postgresChangeFlow → Room
   │  │  │  ├─ domain/
   │  │  │  │  ├─ ParseInput.kt                # NEW — #proyecto regex, pure & unit-testable
   │  │  │  │  └─ SyncError.kt                 # NEW — isPermanent()/auth-fail vs data-fail
   │  │  │  ├─ ui/
   │  │  │  │  ├─ MainActivity.kt              # EDIT — gate on sessionStatus, ViewModel owner
   │  │  │  │  ├─ TodoViewModel.kt             # NEW
   │  │  │  │  ├─ TodoUiState.kt               # NEW — immutable UiState + TodoUi model
   │  │  │  │  ├─ TodoScreen.kt                # REWRITE — states + optimistic, no hand-mutation
   │  │  │  │  ├─ WidgetContent.kt             # NEW — plain @Composable? (see note) — Glance content lives in widget/
   │  │  │  │  ├─ TaskRow.kt                   # EDIT — haptics + Motion.kt springs
   │  │  │  │  ├─ AuroraBackground.kt          # EDIT — decorrelated blobs
   │  │  │  │  ├─ GlassSurface.kt              # EDIT — GlassLevel, specular edge, inputScale
   │  │  │  │  ├─ Motion.kt                    # EDIT — central spring specs
   │  │  │  │  ├─ Shimmer.kt                   # NEW — skeleton for Loading
   │  │  │  │  └─ theme/Theme.kt               # EDIT
   │  │  │  ├─ widget/
   │  │  │  │  ├─ TodoGlanceWidget.kt          # NEW — GlanceAppWidget, observes Room in provideContent
   │  │  │  │  ├─ TodoGlanceReceiver.kt        # NEW — GlanceAppWidgetReceiver
   │  │  │  │  ├─ ToggleTodoAction.kt          # NEW — ActionCallback, optimistic→push→updateAll
   │  │  │  │  ├─ WidgetGlanceContent.kt       # NEW — the widget's Glance @Composable (rendered by tests too)
   │  │  │  │  └─ QuickAddActivity.kt          # KEEP (route add through repository now)
   │  │  │  └─ work/
   │  │  │     └─ TodoSyncWorker.kt            # EDIT — refresh token + repo.refresh(), updateAll
   │  │  └─ res/
   │  │     ├─ xml/todo_widget_info.xml        # EDIT — initialLayout → glance loading layout
   │  │     ├─ values/colors.xml               # EDIT — widget_accent fallback
   │  │     ├─ values-v31/colors.xml           # NEW — @android:color/system_accent1_*
   │  │     └─ drawable/…                      # KEEP ic_add, tighten widget gradients
   │  ├─ test/                                 # JVM — runs with NO emulator
   │  │  └─ java/com/mirko/glasstodo/
   │  │     ├─ ParseInputTest.kt               # JUnit — logic
   │  │     ├─ TodoViewModelTest.kt            # Turbine — state emissions
   │  │     ├─ TodoRepositoryTest.kt           # in-memory Room + fake remote
   │  │     ├─ TodoScreenScreenshotTest.kt     # Roborazzi — loading/empty/content/error
   │  │     ├─ WidgetScreenshotTest.kt         # Roborazzi — widget content render
   │  │     └─ WidgetActionUnitTest.kt         # runGlanceAppWidgetUnitTest — taps wired
   │  └─ androidTest/                          # Emulator — advisory/nightly
   │     └─ java/com/mirko/glasstodo/
   │        ├─ TodoFlowEspressoTest.kt         # add→toggle in the app
   │        └─ WidgetPinAndTapTest.kt          # UIAutomator — pin widget, tap checkbox
```

> Note on `WidgetGlanceContent.kt`: extract the widget's UI into ONE `@Composable` (Glance composables) so the real `TodoGlanceWidget` and `WidgetScreenshotTest`/`WidgetActionUnitTest` render the exact same code. This is what makes the widget testable.

---

## 3. KEY FILES — COPY-READY CODE

### 3.1 `gradle/libs.versions.toml` (additions)

```toml
[versions]
# --- keep existing: agp=8.9.1, kotlin=2.2.20, composeBom="2026.06.01",
#     supabaseBom="3.6.0", ktor="3.4.0", serialization="1.8.0", work="2.9.1", haze="1.7.2" ---
room       = "2.8.3"
ksp        = "2.2.20-2.0.2"      # MUST equal kotlin 2.2.20 (flag §7)
lifecycle  = "2.9.1"             # was 2.9.0 — bump: viewmodel-compose + runtime-compose
datastore  = "1.1.7"
roborazzi  = "1.67.0"
robolectric= "4.14.1"
turbine    = "1.2.1"
glance     = "1.1.1"             # stable, has CVE-2024-7254 fix (NOT 1.1.0, NOT 1.3.0-alpha)

[libraries]
# Room (KSP)
room-runtime  = { group = "androidx.room", name = "room-runtime",  version.ref = "room" }
room-ktx      = { group = "androidx.room", name = "room-ktx",      version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
# Lifecycle / DataStore
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose   = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose",   version.ref = "lifecycle" }
datastore-preferences       = { group = "androidx.datastore", name = "datastore-preferences",       version.ref = "datastore" }
# Glance
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }
# Supabase Realtime — the MISSING module (BOM-versioned, no explicit version)
supabase-realtime = { group = "io.github.jan-tennert.supabase", name = "realtime-kt" }
# Test — JVM
roborazzi              = { group = "io.github.takahirom.roborazzi", name = "roborazzi",              version.ref = "roborazzi" }
roborazzi-compose      = { group = "io.github.takahirom.roborazzi", name = "roborazzi-compose",      version.ref = "roborazzi" }
roborazzi-junit-rule   = { group = "io.github.takahirom.roborazzi", name = "roborazzi-junit-rule",   version.ref = "roborazzi" }
robolectric            = { group = "org.robolectric", name = "robolectric",                          version.ref = "robolectric" }
turbine                = { group = "app.cash.turbine", name = "turbine",                             version.ref = "turbine" }
glance-testing            = { group = "androidx.glance", name = "glance-testing",            version.ref = "glance" }
glance-appwidget-testing  = { group = "androidx.glance", name = "glance-appwidget-testing", version.ref = "glance" }
room-testing              = { group = "androidx.room",   name = "room-testing",             version.ref = "room" }
coroutines-test           = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version = "1.9.0" }
# Test — instrumented
androidx-uiautomator = { group = "androidx.test.uiautomator", name = "uiautomator", version = "2.3.0" }
espresso-core        = { group = "androidx.test.espresso", name = "espresso-core", version = "3.6.1" }

[plugins]
ksp       = { id = "com.google.devtools.ksp", version.ref = "ksp" }
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

### 3.2 `app/build.gradle.kts` (deltas)

```kotlin
plugins {
    // keep: android application, kotlin android, kotlin compose plugin
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}
android {
    defaultConfig {
        minSdk = 26        // Glance needs 23; Haze blur needs 31 (degrades gracefully 26–30)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // Roborazzi/Robolectric need merged resources
            all { it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware" }
        }
    }
    // release: enable R8 for a store build (v1 has isMinifyEnabled=false — flag §7)
}
dependencies {
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.lifecycle.viewmodel.compose); implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget); implementation(libs.glance.material3)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation(libs.supabase.realtime)   // NEW — realtime-kt

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi); testImplementation(libs.roborazzi.compose); testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation(libs.glance.testing); testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.room.testing); testImplementation(libs.turbine); testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.uiautomator); androidTestImplementation(libs.espresso.core)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

### 3.3 `data/SupabaseClient.kt` — the widget-empty fix + Realtime

```kotlin
object SupabaseClient {
    val client: SupabaseClient = createSupabaseClient(
        BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth) {
            // DEFAULT true registers Android lifecycle callbacks that CLEAR the in-memory
            // session on ON_STOP -> a backgrounded widget/worker reads a null token -> ANON
            // -> RLS returns nothing (the real "empty widget" cause). Turn OFF and own refresh.
            enableLifecycleCallbacks = false
            alwaysAutoRefresh = true          // autoLoadFromStorage stays true (default)
        }
        install(Postgrest)                    // 3.6+ auto-retries transient errors (not auth)
        install(Realtime)                     // requires realtime-kt (was missing in v1)
    }
}
```

### 3.4 `data/local/TodoEntity.kt` + `TodoDao.kt` + `AppDatabase.kt`

```kotlin
enum class SyncStatus { SYNCED, PENDING }

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,               // CLIENT UUID -> offline creates exist before server sees them
    val userId: String,
    val title: String,
    val project: String? = null,
    val priority: Int = 0,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,             // tombstone -> soft-delete (rule 6)
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TodoEntity>>          // UI's ONLY read path
    @Query("SELECT * FROM todos WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAllBlockingSnapshot(): List<TodoEntity> // widget cold-start local read (fast)
    @Query("SELECT * FROM todos WHERE syncStatus = 'PENDING'")
    suspend fun pending(): List<TodoEntity>            // WorkManager drains
    @Query("SELECT * FROM todos WHERE id = :id") suspend fun byId(id: String): TodoEntity?
    @Upsert suspend fun upsert(t: TodoEntity)
    @Upsert suspend fun upsertAll(t: List<TodoEntity>)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun hardDelete(id: String)
    @Query("DELETE FROM todos WHERE id NOT IN (:keepIds)") suspend fun deleteMissing(keepIds: List<String>)
}

@Database(entities = [TodoEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun todoDao(): TodoDao }
```

### 3.5 `data/TodoRepository.kt` — offline-first, optimistic + rollback

```kotlin
class TodoRepository(
    private val dao: TodoDao,
    private val remote: TodoRemote,          // supabase-kt Postgrest wrapper (replaces SupabaseTodosRest)
    private val auth: AuthSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeTodos(): Flow<List<TodoUi>> = dao.observeAll().map { it.map(TodoEntity::toUi) }

    suspend fun refresh() = withContext(io) {                 // pull remote -> reconcile Room
        val uid = auth.requireUid()
        val rows = remote.list(uid)
        dao.upsertAll(rows.map { it.toEntity(SyncStatus.SYNCED) })
        dao.deleteMissing(rows.map { it.id })                 // drop rows deleted elsewhere
    }

    suspend fun add(title: String, project: String?) = withContext(io) {
        val e = TodoEntity(id = UUID.randomUUID().toString(), userId = auth.requireUid(),
            title = title, project = project, syncStatus = SyncStatus.PENDING)
        dao.upsert(e)                                         // (1) OPTIMISTIC: row visible instantly (Flow)
        runCatching { remote.insert(e.toRemote()) }
            .onSuccess { dao.upsert(e.copy(syncStatus = SyncStatus.SYNCED)) }
            .onFailure { throw it }                           // stays PENDING -> WorkManager retries
    }

    suspend fun toggle(id: String, done: Boolean) = withContext(io) {
        val prev = dao.byId(id) ?: return@withContext
        dao.upsert(prev.copy(done = done, syncStatus = SyncStatus.PENDING, updatedAt = now())) // OPTIMISTIC
        runCatching { remote.setDone(id, done) }
            .onSuccess { dao.upsert(prev.copy(done = done, syncStatus = SyncStatus.SYNCED)) }
            .onFailure { err -> if (err.isPermanent()) dao.upsert(prev); throw err }            // ROLLBACK
    }

    suspend fun delete(id: String) = withContext(io) {
        val prev = dao.byId(id) ?: return@withContext
        dao.upsert(prev.copy(deleted = true, syncStatus = SyncStatus.PENDING))                  // OPTIMISTIC soft-delete
        runCatching { remote.delete(id) }
            .onSuccess { dao.hardDelete(id) }
            .onFailure { err -> if (err.isPermanent()) dao.upsert(prev); throw err }            // ROLLBACK: reappears
    }
}
```

`SyncError.kt`:
```kotlin
fun Throwable.isPermanent(): Boolean = this is RestException &&
    statusCode !in listOf(401, 403) &&   // auth-fail: keep data, re-auth — NOT permanent for rollback
    statusCode in 400..499                // 4xx validation/constraint = permanent data-fail
```

### 3.6 `data/RealtimeSync.kt` — Realtime → Room (replaces 15-min poll)

```kotlin
class RealtimeSync(
    private val client: SupabaseClient,
    private val dao: TodoDao,
    private val scope: CoroutineScope,     // long-lived (App/repository), NOT a recomposing LaunchedEffect
) {
    fun start() = scope.launch {
        client.auth.awaitInitialization()  // ensure JWT loaded before subscribe, or the socket is anon (RLS empty)
        // selectAsFlow merges the initial select + postgres_changes, keyed by PK
        client.from("todos").selectAsFlow(TodoDto::id)
            .catch { /* keep last Room data on error */ }
            .collect { rows -> dao.upsertAll(rows.map { it.toEntity(SyncStatus.SYNCED) })
                               dao.deleteMissing(rows.map { it.id })
                               TodoGlanceWidget().updateAll(applicationContext) }  // push to widget too
    }
}
```

### 3.7 `ui/TodoUiState.kt` + `ui/TodoViewModel.kt`

```kotlin
data class TodoUiState(
    val todos: List<TodoUi> = emptyList(),
    val isLoading: Boolean = true,     // Room hasn't emitted yet — NOT an empty list
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,  // transient snackbar
) { val isEmpty get() = !isLoading && todos.isEmpty() }

data class TodoUi(val id: String, val title: String, val project: String?, val done: Boolean, val pending: Boolean)

class TodoViewModel(private val repo: TodoRepository) : ViewModel() {
    private val isSyncing = MutableStateFlow(false)
    private val errors    = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TodoUiState> =
        combine(repo.observeTodos(), isSyncing, errors) { todos, syncing, err ->
            TodoUiState(todos, isLoading = false, isSyncing = syncing, errorMessage = err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState(isLoading = true))

    init { refresh() }
    fun refresh() = viewModelScope.launch {
        isSyncing.value = true
        runCatching { repo.refresh() }.onFailure { errors.value = "Sin conexión — mostrando datos locales" }
        isSyncing.value = false
    }
    fun add(raw: String) = viewModelScope.launch {
        val parsed = parseInput(raw) ?: return@launch                 // pure, testable
        runCatching { repo.add(parsed.title, parsed.project) }
            .onFailure { errors.value = "Guardado local; se sincronizará al reconectar" }
    }
    fun toggle(id: String, done: Boolean) = viewModelScope.launch {
        runCatching { repo.toggle(id, done) }.onFailure { errors.value = "No se pudo actualizar; revertido" }
    }
    fun delete(id: String) = viewModelScope.launch {
        runCatching { repo.delete(id) }.onFailure { errors.value = "No se pudo borrar; restaurado" }
    }
    fun errorShown() { errors.value = null }
}
```

### 3.8 `ui/TodoScreen.kt` — states + optimistic, no hand-mutation

```kotlin
@Composable
fun TodoScreen(vm: TodoViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbar.showSnackbar(it); vm.errorShown() }
    }
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.hazeSource(hazeState))
        when {
            state.isLoading -> ShimmerList(Modifier.hazeSource(hazeState))          // skeleton, not blank
            state.isEmpty   -> EmptyState(Modifier.align(Alignment.Center))         // illustrative
            else -> LazyColumn(Modifier.hazeSource(hazeState)) {
                items(state.todos, key = { it.id }) { t ->
                    TaskRow(task = t,
                        onToggle = { vm.toggle(t.id, !t.done) },   // fire-and-forget; Room+VM own state
                        onDelete = { vm.delete(t.id) },
                        modifier = Modifier.animateItem().alpha(if (t.pending) 0.6f else 1f))
                }
            }
        }
        GlassTopBar(Modifier.glass(hazeState, GlassLevel.Bar))
        GlassInputBar(input, { input = it }, onAdd = { vm.add(input); input = "" },
            Modifier.glass(hazeState, GlassLevel.Panel))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
```

### 3.9 `widget/WidgetGlanceContent.kt` — the shared, testable Glance UI

```kotlin
@Composable
fun WidgetGlanceContent(todos: List<TodoUi>) {
    Column(GlanceModifier.fillMaxSize()
        .background(GlanceTheme.colors.widgetBackground).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Tareas", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp,
                color = GlanceTheme.colors.onSurface))
            Spacer(GlanceModifier.defaultWeight())
            Image(provider = ImageProvider(R.drawable.ic_add), contentDescription = "Añadir tarea",
                modifier = GlanceModifier.size(38.dp).cornerRadius(19.dp).padding(9.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .clickable(actionStartActivity<QuickAddActivity>()))   // Activity, not broadcast (A12 safe)
        }
        Spacer(GlanceModifier.height(10.dp))
        if (todos.isEmpty()) {
            Text("Sin tareas · toca + para añadir",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
        } else LazyColumn {
            items(todos, itemId = { it.id.hashCode().toLong() }) { t -> TodoRow(t) }
        }
    }
}

@Composable
private fun TodoRow(t: TodoUi) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = t.done,
            onCheckedChange = actionRunCallback<ToggleTodoAction>(   // THE reliable-tap fix: per-item, type-safe
                actionParametersOf(ToggleTodoAction.idKey to t.id)),
            text = t.title + (t.project?.let { "  #$it" } ?: ""),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp,
                textDecoration = if (t.done) TextDecoration.LineThrough else null))
    }
}
```

### 3.10 `widget/TodoGlanceWidget.kt` — observes Room in provideContent

```kotlin
class TodoGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(
        DpSize(180.dp,110.dp), DpSize(250.dp,110.dp), DpSize(250.dp,250.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = ServiceLocator.repository(context)
        provideContent {
            // MUST collect INSIDE provideContent so any Room write re-renders the widget.
            val todos by repo.observeTodos().collectAsState(initial = emptyList())
            GlanceTheme { WidgetGlanceContent(todos) }
        }
    }
}
```

### 3.11 `widget/ToggleTodoAction.kt` — optimistic → push → updateAll

```kotlin
class ToggleTodoAction : ActionCallback {
    companion object { val idKey = ActionParameters.Key<String>("todo_id") }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[idKey] ?: return
        val nowChecked = parameters[ToggleableStateKey] ?: return  // Glance injects the NEW value (no stale recompute)
        val repo = ServiceLocator.repository(context)
        // repo.toggle writes Room optimistically (Flow re-emits) then pushes to Supabase (~10min budget here).
        runCatching { repo.toggle(id, nowChecked) }
        TodoGlanceWidget().updateAll(context)                      // force re-render across instances
    }
}
```

### 3.12 `widget/TodoGlanceReceiver.kt` + manifest

```kotlin
class TodoGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoGlanceWidget()
    override fun onEnabled(context: Context) { TodoSyncWorker.schedule(context) }
}
```
```xml
<receiver android:name=".widget.TodoGlanceReceiver" android:exported="false">
    <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
    <meta-data android:name="android.appwidget.provider" android:resource="@xml/todo_widget_info"/>
</receiver>
```
`res/xml/todo_widget_info.xml`: keep yours, set `android:initialLayout="@layout/glance_default_loading_layout"`.

### 3.13 `work/TodoSyncWorker.kt` — token refresh + safety-net pull (app-dead case)

```kotlin
class TodoSyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        SupabaseClient.client.auth.refreshCurrentSession()      // we own refresh (lifecycle callbacks OFF)
        ServiceLocator.repository(applicationContext).refresh() // pull -> Room
        TodoGlanceWidget().updateAll(applicationContext)
        Result.success()
    } catch (e: IOException) { Result.retry() }                 // exponential backoff

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TodoSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "todo_widget_refresh", ExistingPeriodicWorkPolicy.KEEP, req)  // KEEP = no duplicate chains
        }
    }
}
```
> Safety net only — realtime is the live path while the app is alive. This worker keeps the widget's token fresh and Room warm when the app process is dead. Do NOT run both realtime and a frequent poll for the foreground app (double-write flicker).

### 3.14 `di/ServiceLocator.kt` — one repo shared by app + widget

```kotlin
object ServiceLocator {
    @Volatile private var repo: TodoRepository? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun repository(context: Context): TodoRepository = repo ?: synchronized(this) {
        repo ?: build(context.applicationContext).also { repo = it }
    }
    private fun build(ctx: Context): TodoRepository {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "todos.db").build()
        val client = SupabaseClient.client
        val remote = TodoRemoteImpl(client)
        val auth = SupabaseAuthSource(client)
        val r = TodoRepository(db.todoDao(), remote, auth)
        RealtimeSync(client, db.todoDao(), appScope).start()   // one long-lived realtime collector
        return r
    }
}
```

### 3.15 `ui/TaskRow.kt` — haptics + Motion.kt (polish)

```kotlin
@Composable fun TaskCheck(checked: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (checked) 1f else 0.9f, Motion.spatialFast(), label = "scale")
    val bg by animateColorAsState(if (checked) Accent else Color.Transparent, Motion.effects(), label = "bg")
    Box(Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(CircleShape).background(bg)
        .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
        .clickable {
            haptics.performHapticFeedback(
                if (!checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle()
        }, contentAlignment = Alignment.Center) { /* AnimatedVisibility check */ }
}
```
`Motion.kt` (stay on material3 1.4.0 — hand-rolled specs mirroring Expressive; swap to `MaterialTheme.motionScheme` only if you adopt 1.5.0-alpha, §7):
```kotlin
object Motion {
    fun <T> spatialFast() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium) // overshoot ok
    fun <T> effects()     = spring<T>(dampingRatio = 1f,    stiffness = Spring.StiffnessMedium) // no overshoot
}
```

### 3.16 `ui/GlassSurface.kt` — glass depth (polish, keep Haze 1.7.2 API)

```kotlin
enum class GlassLevel { Bar, Panel }
@Composable fun Modifier.glass(state: HazeState, level: GlassLevel = GlassLevel.Panel) = this
    .hazeEffect(state, style = when (level) {
        GlassLevel.Bar   -> HazeMaterials.ultraThin(MaterialTheme.colorScheme.surface)
        GlassLevel.Panel -> HazeMaterials.thin(MaterialTheme.colorScheme.surface)
    }) {
        blurRadius = if (level == GlassLevel.Bar) 20.dp else 28.dp
        noiseFactor = 0.04f
        inputScale = HazeInputScale.Auto
        tints = listOf(HazeTint(Color(0x2227E0F0)))
    }
    .drawWithContent { drawContent(); drawLine(   // 1px specular top edge — sells the glass
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(0.35f), Color.Transparent)),
        Offset(0f,0f), Offset(size.width,0f), strokeWidth = 1.dp.toPx()) }
```

---

## 4. RLS / DB (UNCHANGED) + HOW THE WIDGET GETS THE TOKEN RELIABLY

### 4.1 RLS policies — DO NOT TOUCH
The existing `todos` table and its RLS policies (`user_id = auth.uid()` per-row) stay exactly as they are. No column additions are required server-side: the offline columns (`syncStatus`, `deleted` tombstone) are **local-only** in Room and never sent to Postgres. `TodoRemote.insert` sends the client-generated UUID as the row `id` (reconciled by keeping the same id server-side), `title`, `project`, `done`, `user_id`.

### 4.2 The ONLY server change — enable Realtime (`supabase/001_realtime_publication.sql`)
```sql
-- RLS is UNCHANGED. This only lets the realtime stream carry full rows and applies RLS to it.
alter publication supabase_realtime add table public.todos;
alter table public.todos replica identity full;   -- so UPDATE/DELETE payloads carry the row (decode + RLS filter)
```
Without `replica identity full`, DELETE/filtered-UPDATE payloads carry only the PK and `decodeRecord<TodoDto>()` on deletes fails. Without the publication line, the channel subscribes but never emits.

### 4.3 How the widget gets a valid auth token reliably (the empty-widget fix)
Three mechanisms in order of importance:

1. **`enableLifecycleCallbacks = false`** (§3.3). This is THE fix. The default clears the in-memory session on background; with it off the session stays in memory and is restored from encrypted storage at process init (`autoLoadFromStorage` stays true). A widget refreshed while the app is backgrounded now sees a real JWT, not ANON.
2. **We own refresh.** Because lifecycle auto-refresh is now off, `TodoSyncWorker` calls `auth.refreshCurrentSession()` on every run (and `MainActivity` on resume). Postgrest 3.6's session failsafe also force-refreshes an expired token on demand. Tokens never silently expire under the widget.
3. **Widget reads Room, not the network.** `provideGlance` collects `repo.observeTodos()` — a fast local read kept current by the app's realtime collector + the worker. The widget no longer needs a live token at render time at all; it only needs one when *writing* a toggle, which goes through the shared authenticated client inside an `ActionCallback` (~10 min budget). This structurally removes the "widget did its own tokened OkHttp fetch on the binder thread" failure mode.

For non-composable entry points that DO hit the network cold (the worker), guard with `client.auth.awaitInitialization()` before the first token read.

---

## 5. TEST PLAN + CI

Two-tier pyramid; **JVM tier is the required merge gate**, emulator tier is advisory/nightly (it flakes).

### 5.1 Tier 1 — JVM (no emulator, ~30–60 s, REQUIRED)

`ParseInputTest.kt` (logic that never needs a device):
```kotlin
class ParseInputTest {
    @Test fun extractsProject() {
        val r = parseInput("Comprar pan #casa")!!
        assertEquals("Comprar pan", r.title); assertEquals("casa", r.project)
    }
    @Test fun blankIsNull() { assertNull(parseInput("   ")) }
}
```

`TodoViewModelTest.kt` (Turbine — proves loading→content and no blank-on-error):
```kotlin
@Test fun emitsLoadingThenContent() = runTest {
    val repo = FakeRepo(flowOf(listOf(TodoUi("1","a",null,false,false))))
    TodoViewModel(repo).uiState.test {
        assertTrue(awaitItem().isLoading)                     // first frame = loading, NOT empty
        val s = awaitItem(); assertFalse(s.isLoading); assertEquals(1, s.todos.size)
        cancelAndConsumeRemainingEvents()
    }
}
```

`TodoRepositoryTest.kt` (in-memory Room + fake remote — proves optimistic + rollback):
```kotlin
@Test fun toggle_rollsBack_onPermanentError() = runTest {
    val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
    val repo = TodoRepository(db.todoDao(), FailingRemote(status = 400), FakeAuth)
    db.todoDao().upsert(entity(id="1", done=false))
    runCatching { repo.toggle("1", true) }
    assertEquals(false, db.todoDao().byId("1")!!.done)        // reverted, not left lying
}
```

`TodoScreenScreenshotTest.kt` (Roborazzi — catches visual/state regressions):
```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
class TodoScreenScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loading() = capture("loading") { TodoScreenStateless(TodoUiState(isLoading = true)) }
    @Test fun empty()   = capture("empty")   { TodoScreenStateless(TodoUiState(isLoading = false)) }
    @Test fun content() = capture("content") { TodoScreenStateless(TodoUiState(todos = sample, isLoading = false)) }
    @Test fun error()   = capture("error")   { TodoScreenStateless(TodoUiState(todos = sample, errorMessage = "Sin conexión")) }
    private fun capture(name: String, c: @Composable () -> Unit) {
        compose.setContent { GlassTheme { c() } }
        compose.onRoot().captureRoboImage("$name.png")
    }
}
```

`WidgetScreenshotTest.kt` (Roborazzi renders the SAME `WidgetGlanceContent`):
```kotlin
@Test fun widget_withTasks() = runTest {
    captureRoboImage("widget_tasks.png") { WidgetGlanceContent(sample) }
}
@Test fun widget_empty() = runTest {
    captureRoboImage("widget_empty.png") { WidgetGlanceContent(emptyList()) }
}
```

`WidgetActionUnitTest.kt` (proves taps are wired — no launcher, never flakes):
```kotlin
@Test fun checkbox_wiredToToggleCallback() = runGlanceAppWidgetUnitTest {
    provideComposable { WidgetGlanceContent(listOf(TodoUi("1","a",null,false,false))) }
    onNode(hasText("a")).assertHasActionRunCallbackClickAction(ToggleTodoAction::class.java)
}
@Test fun addButton_wiredToActivity() = runGlanceAppWidgetUnitTest {
    provideComposable { WidgetGlanceContent(emptyList()) }
    onNode(hasContentDescription("Añadir tarea")).assertHasStartActivityClickAction()
}
```
Record goldens once: `./gradlew recordRoborazziDebug`, commit the PNGs; CI runs `verifyRoborazziDebug`.

### 5.2 Tier 2 — Emulator (UIAutomator + Espresso, ~5–10 min, ADVISORY)

`WidgetPinAndTapTest.kt` (end-to-end proof the real bug is dead):
```kotlin
@RunWith(AndroidJUnit4::class)
class WidgetPinAndTapTest {
    private val device = UiDevice.getInstance(getInstrumentation())
    private val ctx = getInstrumentation().targetContext
    @Test fun pinWidget_thenTapToggle() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(ctx, TodoGlanceReceiver::class.java)
        if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(provider, null, null)
        device.wait(Until.findObject(By.textContains("Add").clickable(true)), 8_000)?.click()
        device.pressHome()
        val row = device.wait(Until.findObject(By.textContains("a")), 8_000)
        assertNotNull("widget not on home screen", row)
        row.click()                                   // tap must register (v1's flaky path)
        // assert the strike-through/checked state changed via a second findObject
    }
}
```
> If the launcher pin dialog is flaky in CI, bypass it with `AppWidgetHost` + `bindAppWidgetIdIfAllowed` (Brief 5). Lock the emulator image (`google_apis`, api 34) so `By.text` matches are stable.

### 5.3 CI — `.github/workflows/android-ci.yml`

```yaml
name: android-ci
on: [push, pull_request]
jobs:
  jvm:                       # REQUIRED merge gate — fast, deterministic, no emulator
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v5
      - name: Unit + Turbine + Room + Roborazzi verify + Glance-logic + lint
        run: ./gradlew testDebugUnitTest verifyRoborazziDebug lintDebug
      - if: failure()
        uses: actions/upload-artifact@v4
        with: { name: roborazzi-diffs, path: '**/build/outputs/roborazzi/**' }

  instrumented:              # ADVISORY (PR + nightly) — never the sole gate
    runs-on: ubuntu-latest
    strategy: { matrix: { api-level: [34] } }
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v5
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - name: AVD cache
        uses: actions/cache@v4
        id: avd-cache
        with:
          path: |
            ~/.android/avd/*
            ~/.android/adb*
          key: avd-${{ matrix.api-level }}-x86_64-google_apis
      - name: Create AVD snapshot (cache miss only)
        if: steps.avd-cache.outputs.cache-hit != 'true'
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          arch: x86_64
          target: google_apis
          force-avd-creation: false
          emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          script: echo "cached"
      - name: Instrumented (Espresso + UIAutomator widget)
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          arch: x86_64
          target: google_apis
          force-avd-creation: false
          emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          disable-animations: true
          script: ./gradlew connectedDebugAndroidTest
```
Set branch protection so only `jvm` is required.

---

## 6. ORDERED REBUILD CHECKLIST (each step CI-verifiable)

Incremental; every step ends green on the JVM job before the next.

1. **CI skeleton first.** Add `android-ci.yml` (jvm job) + one trivial `ParseInputTest`. Make `jvm` a required check. → *proves the pipeline runs before any real code.*
2. **Version catalog + Gradle.** Add Room/KSP/lifecycle/datastore/glance/realtime + test deps (§3.1–3.2). Build only. → *`./gradlew assembleDebug` green; confirms KSP↔Kotlin and BOM resolve (§7).*
3. **Room layer.** `TodoEntity`/`TodoDao`/`AppDatabase` + `TodoRepositoryTest` (in-memory Room, fake remote) covering optimistic add/toggle/delete + rollback. → *repository tests green, zero UI.*
4. **Remote + client.** `TodoRemote`/`TodoRemoteImpl` (delete `SupabaseTodosRest`), `SupabaseClient` with `enableLifecycleCallbacks=false` + `install(Realtime)`. → *unit test the DTO mapping.*
5. **Realtime → Room.** `RealtimeSync` + run `001_realtime_publication.sql` on Supabase. → *manual smoke: edit a row in Supabase dashboard, confirm Room receives it (log).*
6. **ViewModel + UiState** + `TodoViewModelTest` (Turbine). → *state emissions green.*
7. **Compose screen rewrite** (`TodoScreen`, states, `MainActivity` gates on `sessionStatus`) + `TodoScreenScreenshotTest`, record goldens. → *Roborazzi verify green (loading/empty/content/error).*
8. **Glance widget.** `WidgetGlanceContent` + `TodoGlanceWidget` + `ToggleTodoAction` + `TodoGlanceReceiver`; wire `QuickAddActivity` through the repository; delete `TodoWidgetProvider`/`TodoWidgetService`/`widget_*.xml`. Add `WidgetScreenshotTest` + `WidgetActionUnitTest`. → *Glance render + action-wiring tests green.*
9. **Worker demotion.** `TodoSyncWorker` = token refresh + safety-net pull at 30 min; remove the 15-min poll. → *repository refresh test still green.*
10. **Polish pass** (haptics, `Motion.kt`, `GlassLevel`/specular edge, decorrelated aurora, shimmer, optimistic add, widget Material You `values-v31`). Re-record Roborazzi goldens intentionally. → *screenshot diffs reviewed like code.*
11. **Emulator tier.** Add `WidgetPinAndTapTest` + `TodoFlowEspressoTest` + the `instrumented` CI job (advisory). → *end-to-end widget tap proven on a real launcher.*
12. **Store hardening** (optional): enable R8, scope backup rules, drop `REQUEST_INSTALL_PACKAGES` if the self-updater is cut, add a baseline profile.

---

## 7. VERSION-SENSITIVE FLAGS TO VERIFY BEFORE/DURING BUILD

1. **KSP ↔ Kotlin lockstep.** `ksp = 2.2.20-2.0.2` MUST match Kotlin `2.2.20`. A mismatch fails the build hard. Verify the exact KSP suffix published for 2.2.20 (may be `-2.0.x`).
2. **Compose BOM `2026.06.01`** — your own toml flags "verify it resolves." Confirm it resolves in both `implementation` and `testImplementation(platform(...))`. The Roborazzi brief used `2026.06.00` for tests — align both to the same published BOM.
3. **supabase-kt `selectAsFlow` / realtime API.** `realtime-kt` is BOM-versioned by `3.6.0` (no explicit version). Confirm `selectAsFlow(TodoDto::id)` and `postgresChangeFlow<PostgresAction>` signatures against the 3.6.0 source before wiring (API has moved across versions). If `selectAsFlow` isn't present, fall back to the `postgresChangeFlow` + initial `select()` pattern (§ Brief 3 granular snippet).
4. **Glance `1.1.1` pin, not `1.1.0`** (CVE-2024-7254), **not `1.3.0-alpha`** (Wear/health, experimental). `glance-testing`/`glance-appwidget-testing` must match at `1.1.1`.
5. **`runGlanceAppWidgetUnitTest` 1-second default timeout** — if `WidgetGlanceContent` ever does async work in tests, pass a longer duration or it fails opaquely.
6. **material3 stays `1.4.0` stable.** `MaterialTheme.motionScheme` / `MotionScheme.expressive()` require `1.5.0-alpha` + `@ExperimentalMaterial3ExpressiveApi`. Blueprint hand-rolls `Motion.kt` to avoid alpha exposure; only opt into 1.5.0-alpha if you specifically want the real Expressive scheme.
7. **Haze stays `1.7.2`.** Do NOT bump to `2.0.0-alpha03` — it moves blur to a `haze-blur` module, wraps props in `blurEffect{}`, renames `HazeTint`→`HazeColorEffect`, removes `rememberHazeState(blurEnabled)`. Zero benefit, alpha risk.
8. **Haze blur is API 31+.** On minSdk 26–30 it degrades to a translucent scrim. Add a Roborazzi golden at an API-26 qualifier so the bars still look deliberate on ~1/4 of installs.
9. **Roborazzi goldens are environment-sensitive** (device qualifiers, DPI, JDK font rendering, `pixelCopyRenderMode`). Lock `@Config(qualifiers = Pixel5)` + `@GraphicsMode(NATIVE)` and record goldens in the SAME environment CI verifies in (ideally record once in CI) or you get spurious diffs.
10. **Emulator flags:** KVM udev step is mandatory (else software-render timeouts); `disable-animations: true` on the test run; `arch: x86_64` + `google_apis` (arm64 images on x86 runners are unsupported/slow). AVD cache key must include api-level + arch + target.
11. **`enableLifecycleCallbacks=false` means YOU own refresh.** If you forget the `refreshCurrentSession()` calls (worker + on-resume), tokens silently expire and the widget 401s — verify both call sites exist.
12. **Room `exportSchema = true`** needs a schema location arg (`room.schemaLocation`) in KSP args, or the build warns; set it if you want migration history.
13. **New Supabase API keys** (`sb_publishable_`/`sb_secret_`) replace anon/service by end of 2026 — keep the publishable/anon key client-side, never the secret. The existing code comment is correct.

---

**Bottom line:** the prior is sound and this blueprint commits to it fully. Room-as-SSOT is the keystone that makes the widget reliable (per-item Glance actions writing one observable store), live (realtime→Room, no poll), offline (Room reads + PENDING queue), and testable (pure logic + Roborazzi render of the exact widget composable). The one non-obvious correction folded in: the empty-widget bug is `enableLifecycleCallbacks`, not `awaitInitialization` — fix it at the client config, not with more awaiting.