# Glass Todo — v2 rebuild progress & handoff

**Status (2026-07-09): steps 1–7b DONE and green in CI. Continue from step 7c (Roborazzi goldens), then step 8 (the Glance widget — the actual tap-bug fix).**

Goal: rebuild the widget+app "the right way" so it's bug-free + self-verifying (the v1 widget had unreliable taps + an empty-widget bug). The FULL plan with copy-ready code for every step is in **`docs/v2-blueprint.md`** — read it. This file is the running progress + handoff. Deep research briefs are in `docs/v2-research.json`.

---

## Key facts
- **Repo:** github.com/Mirkom03/glass-todo (PUBLIC). Default branch `main`.
- **Supabase:** project `aide-studio` (ref `wkjfnpjklmikswgofekq`), reachable via the Supabase MCP. Table `public.todos` + owner-scoped RLS (**do NOT touch the RLS**). Realtime is now ENABLED on it (see step 5).
- **App login (email/password auth):** `mirko@glasstodo.app` / `GlassTodoMirko2026!` (dedicated user, uid `511b897e-afb2-4745-91f6-d8103ad4aefd`, has 4 seeded todos). NOT `mirkomilano2003@` (that is Mirko's CRM account — already exists, can't reuse).
- **CI:** `.github/workflows/android-ci.yml` = JVM tests (fast, the merge gate). `.github/workflows/release.yml` = tag `v*` → APK on Releases. Secrets `SUPABASE_URL` + `SUPABASE_ANON_KEY` already set on the repo.
- **Shipped app:** v1.0.4 (installed on Mirko's phone, works; its widget still has the tap bugs — that's what v2 fixes). v1.0.4 also has an in-app auto-updater, so once v2 ships as a higher version tag, the phone can update itself.
- **BUILD ENV: there is NO local Android SDK.** Everything is verified via CI. There is no emulator/device to test on except Mirko's phone. Build blind → CI verifies.

## The verify loop (how to continue every step)
```bash
cd C:/Users/mirko/glass-todo
# ...edit files...
git add -A   # NOT `commit -a` — that skips new files
git -c user.name="Mirkom03" -c user.email="mirkomilano2003@gmail.com" commit -m "..."
git push
# find + watch the CI run (match by SHA — `-L 1` can return the PREVIOUS run before yours registers):
SHA=$(git rev-parse --short HEAD)
gh run list -R Mirkom03/glass-todo --workflow android-ci.yml -L 6 \
  --json databaseId,headSha,status,conclusion -q ".[] | select(.headSha|startswith(\"$SHA\"))"
# on failure, read the real error:
gh run view "$RID" -R Mirkom03/glass-todo --log | grep -iE "e: file|error:|FAILED|expected|but was|Caused by|Unresolved|AAPT|BUILD FAILED"
```
Each step must end GREEN on `android-ci` before the next.

## Done (green, committed)
- **Step 1** — CI JVM test pipeline + `domain/ParseInput.kt` extracted & unit-tested (`ParseInputTest`). commit `ed7afc1`.
- **Step 2** — v2 deps in `gradle/libs.versions.toml` + `app/build.gradle.kts`: Room 2.8.3, KSP `2.2.20-2.0.2`, Glance 1.1.1, `realtime-kt`, DataStore 1.1.7, lifecycle 2.9.1 + test deps (Robolectric 4.14.1, Turbine, coroutines-test, room-testing). All resolve. commit `cec01e8`.
- **Step 3** — Room SSOT + offline-first repo + tests. Files: `domain/{TodoUi,SyncError}.kt`, `data/local/{TodoEntity,TodoDao,AppDatabase}.kt`, `data/remote/{TodoDto,TodoRemote}.kt`, `data/{AuthSource,TodoStore}.kt`, test `data/TodoStoreTest.kt`. commit `ecf9929`.
- **Step 4** — Remote + client. commit `4dcf0b7`, CI run `29009524041` green.
  - `data/remote/TodoRemoteImpl.kt` (Postgrest CRUD; `RestException.statusCode` → our `RemoteException`).
  - `data/SupabaseClient.kt`: `enableLifecycleCallbacks = false` (THE empty-widget fix) + `install(Realtime)`.
  - `data/SupabaseAuthSource.kt` implements `AuthSource`.
  - `TodoDto` now maps `created_at` (v1 dropped it, so ordering churned on every sync) + `TodoDtoMappingTest`.
  - `TodoStore.reconcile()`: **local PENDING rows win** over a server snapshot (an unpushed offline add is not in the snapshot; deleting it would lose data). `TodoDao.deleteMissing` excludes PENDING.
- **Step 5** — Realtime → Room. commits `e7139f8` + `c63d899`, CI run `29010197937` green.
  - `data/RealtimeSync.kt`: `selectAsFlow(TodoDto::id)` → `TodoStore.reconcile`, plus **refetch-on-reconnect** (supabase-kt reconnects silently and never replays events missed during the outage).
  - `supabase/001_realtime_publication.sql` **APPLIED** to aide-studio (verified: `todos` in `supabase_realtime` publication, `relreplident = 'f'`). RLS untouched.
- **Step 6** — ViewModel. commit `c63d899` (same CI run), `ui/{TodoUiState,TodoViewModel}.kt` + `TodoViewModelTest` (Turbine).
- **Step 7a** — DI + the auth-gating bug fix. commits `4fbd79b` → `812806a` → `454d6f0` (two red CI rounds, see gotchas), CI green on `454d6f0`.
  - `di/ServiceLocator.kt`: one `TodoStore` shared by app + widget; `App.onCreate` builds it.
  - **Bug found while wiring it:** on a signed-out cold start `awaitInitialization()` returns anyway, `selectAsFlow` subscribed as **anon**, RLS returned `[]`, and `reconcile([])` would DELETE every local row. `RealtimeSync` now gates on `SessionStatus.Authenticated` (with `distinctUntilChanged`, so the hourly token refresh does not tear down the channel). 3 new tests cover it.
- **Step 7b** — app cutover onto the ViewModel + Room SSOT. commit `cd00e44`.
  - `ui/TodoScreen.kt` = stateful shell + **stateless `TodoScreenContent(state, onToggle, onAdd, onErrorShown)`** (this is what Roborazzi will render in 7c).
  - loading / empty / content / error are four distinct states (`ui/Shimmer.kt` = `ShimmerList` + `EmptyState`, snackbar for transient errors). No hand-mutation anywhere: a toggle goes VM → Room → back through the same Flow.
  - `TaskRow` takes `TodoUi`, dims `pending` rows. Input survives rotation (`rememberSaveable`).
  - `ui/MainActivity.kt` gates on `SessionStatus` (**`Initializing` is not "signed out"** — v1 flashed the login form) and calls `auth.refreshSession()` on every resume.
  - `data/AuthRepository.kt` replaces the auth half of the v1 repo. **DELETED:** `data/TodoRepository.kt`, `data/Todo.kt`.

## ⚠️ Naming / transition rule
The offline-first repository is **`data/TodoStore.kt`**. The v1 `TodoRepository`/`Todo` are GONE (step 7b). Still to delete at step 8: `widget/{TodoWidgetProvider,TodoWidgetService,SupabaseTodosRest}.kt` + `res/layout/widget_*.xml` (Glance replaces the RemoteViews widget; `SupabaseTodosRest` → `TodoRemoteImpl`). `widget/QuickAddActivity.kt` is kept but must be routed through `TodoStore` via `ServiceLocator`.

## Next steps (blueprint §6 checklist; §3 has the code, §7 has version flags)
7c. **Roborazzi goldens**: add the roborazzi plugin + deps (§3.1/§3.2) and `TodoScreenScreenshotTest` rendering `TodoScreenContent` in its four states. Record the goldens **in CI** (`recordRoborazziDebug`, upload as an artifact, commit the PNGs) — goldens recorded on another machine will not match the CI renderer. CI then runs `verifyRoborazziDebug`. Watch out: `ShimmerList` runs an infinite animation; Robolectric freezes the clock, but pin `@Config(qualifiers = Pixel5)` + `@GraphicsMode(NATIVE)` anyway.
8. **Glance widget** (§3.9–3.12) — the tap bug fix: `widget/{WidgetGlanceContent,TodoGlanceWidget,ToggleTodoAction,TodoGlanceReceiver}.kt`, manifest receiver, `todo_widget_info.xml` initialLayout → `@layout/glance_default_loading_layout`. Delete the v1 RemoteViews widget files. Wire `RealtimeSync.from(..., onSnapshot = { TodoGlanceWidget().updateAll(ctx) })` in `ServiceLocator` (the hook is already there). + `WidgetScreenshotTest` + `WidgetActionUnitTest` (`runGlanceAppWidgetUnitTest`).
9. **Worker** (§3.13): `work/TodoSyncWorker.kt` = `auth.refreshCurrentSession()` + safety-net pull @30min; remove the 15-min poll. **Required:** with `enableLifecycleCallbacks=false` WE own refresh — `MainActivity` already does it on resume (step 7b); the worker must do it for the app-process-dead case.
10. **Polish** (§3.15–3.16): haptics, `Motion.kt`, `GlassLevel`/specular edge, shimmer, widget Material You (`values-v31/colors.xml`). Re-record goldens.
11. **Emulator tier** (§5.2–5.3): `WidgetPinAndTapTest` (UIAutomator) + the `instrumented` CI job (advisory, not the merge gate).
12. **Hardening** (optional): R8, backup rules, baseline profile.

When v2 is done: bump versionCode/Name, tag a new `v*` → the release APK builds; Mirko installs (or the in-app updater does it).

## Gotchas learned (don't re-hit)
- **Robolectric config** (`app/src/test/resources/robolectric.properties`): `sdk=34` (4.14 has no SDK-36 jar) + `application=android.app.Application` (the real `App.onCreate` inits Supabase/Room, which throws on the JVM). Both are REQUIRED for any Robolectric/Roborazzi test.
- **`git commit -a` skips NEW files** → always `git add -A`. But note `git add -A` sweeps in every WIP file: stage deliberately if you want one step per commit.
- **Version stack that resolves:** AGP 8.9.1 (8.9.0 fails), compileSdk 36 + `android.suppressUnsupportedCompileSdk=36`, Compose BOM 2026.06.01, Kotlin 2.2.20, Haze 1.7.2 (do NOT bump to 2.0-alpha), supabase-kt 3.6.0.
- `animateColorAsState` lives in `androidx.compose.animation`, not `.core`.
- CI runner sometimes fails with "job was not acquired by Runner" = transient GitHub infra → just re-run (`gh run rerun <id>`).
- **`gh run list -L 1` right after a push often returns the PREVIOUS run.** Always select the run by `headSha`, or you will "verify" the wrong commit.
- **`UnconfinedTestDispatcher` / `StandardTestDispatcher` are factory FUNCTIONS, not classes.** Using one as a parameter type is `Unresolved reference`; the type is `kotlinx.coroutines.test.TestDispatcher`.
- **Testing anything that `launch`es Room work: `join()` the job.** `UnconfinedTestDispatcher` does NOT make it synchronous — Room's suspend DAO hops to its own query executor. Symptoms: `expected:<1> but was:<0>`, and worse, a coroutine that outlives the test hits the closed in-memory DB and the NEXT test dies with `UncaughtExceptionsBeforeTest` / `attempt to re-open an already-closed object: SQLiteDatabase`.
- **CI uploads `test-reports` on failure.** `gh run download <id> -n test-reports -D <dir>` then read `test-results/testDebugUnitTest/TEST-*.xml` — the `--log` output only says `FAILED`, the XML has the actual `expected:/but was:` and the stack.

### supabase-kt 3.6.0 — verified against the tagged source (not docs)
- `Auth`: `enableLifecycleCallbacks` (default `true`) is declared in commonMain `AuthConfigDefaults`; androidMain `setupPlatform.kt` is what clears the session on `ON_STOP`. `alwaysAutoRefresh` default `true`. Force refresh = `auth.refreshCurrentSession()`.
- `Postgrest` throws `PostgrestRestException` for **every** non-2xx (not the generic `BadRequestRestException` subclasses). The status code property is **`statusCode: Int`** on the base `RestException`. Network/IO failures throw `HttpRequestException` (an `IOException`), timeouts throw ktor's `HttpRequestTimeoutException` — neither is a `RestException`, so `isPermanent()` correctly treats them as transient.
- `selectAsFlow(TodoDto::id)` exists and is `@SupabaseExperimental` → needs `@OptIn(SupabaseExperimental::class)` (opt-in level is ERROR). Requires both `Postgrest` and `Realtime` installed.
- **Realtime never errors the flow on a socket drop** — it reconnects and re-subscribes silently, and the events missed during the outage are lost forever. `.catch{}` only sees the initial select/decode failures. Hence `refetchOnReconnect()`.
- `Realtime.Status` = `DISCONNECTED | CONNECTING | CONNECTED`, exposed as `client.realtime.status: StateFlow<Status>`.
- **Never subscribe realtime without an authenticated session** — anon + RLS = an empty snapshot that a naive `reconcile` turns into "delete everything". Gate on `SessionStatus.Authenticated`, and `distinctUntilChanged()` it (Authenticated re-emits on every token refresh).

### Room / SQLite
- SQLite explicitly allows `x NOT IN ()`, so `deleteMissing(emptyList())` is safe (it clears all SYNCED rows, which is the correct meaning of "the server has nothing").
- `SyncStatus` is persisted by name via a `TypeConverter`, so `WHERE syncStatus != 'PENDING'` in raw `@Query` SQL works.

## ⚠️ Anomaly to be aware of (2026-07-09)
Commit `e7139f8` ("feat(data): Realtime selectAsFlow…", `Co-Authored-By: Claude Haiku 4.5`) was **not** made by the session doing this work — something else committed an in-progress version of `RealtimeSync.kt` mid-edit. No auto-commit hook exists in `~/.claude/settings.json` and the repo has no git hooks, but several `claude.exe` processes were running, so a **second concurrent Claude Code session** is the likely author. Content-wise nothing was lost (the next commit superseded it), but if you see commits you did not make, check for another session before assuming corruption.
