# Glass Todo — v2 rebuild progress & handoff

**Status (2026-07-09): steps 1–9 DONE and GREEN in CI (`7255e73`, run `29013250728`). All four shipping bugs are fixed and covered by tests. What remains is optional: 7c (Roborazzi goldens), 10 (polish), 11 (emulator tier), 12 (hardening) — then bump the version and tag a release.**

Goal: rebuild the widget+app "the right way" so it's bug-free + self-verifying (the v1 widget had unreliable taps + an empty-widget bug). The FULL plan with copy-ready code for every step is in **`docs/v2-blueprint.md`** — read it. This file is the running progress + handoff. Deep research briefs are in `docs/v2-research.json`.

---

## Key facts
- **Repo:** github.com/Mirkom03/glass-todo (PUBLIC). Default branch `main`.
- **Supabase:** project `aide-studio` (ref `wkjfnpjklmikswgofekq`), reachable via the Supabase MCP. Table `public.todos` + owner-scoped RLS (**do NOT touch the RLS**). Realtime is now ENABLED on it (see step 5).
- **App login (email/password auth):** `mirko@glasstodo.app` / `GlassTodoMirko2026!` (dedicated user, uid `511b897e-afb2-4745-91f6-d8103ad4aefd`, has 4 seeded todos). NOT `mirkomilano2003@` (that is Mirko's CRM account — already exists, can't reuse).
- **CI:** `.github/workflows/android-ci.yml` = JVM tests (fast, the merge gate). `.github/workflows/release.yml` = tag `v*` → APK on Releases. Secrets `SUPABASE_URL` + `SUPABASE_ANON_KEY` already set on the repo.
- **Shipped app:** v1.0.4 (installed on Mirko's phone, works; its widget still has the tap bugs — that's what v2 fixes). v1.0.4 also has an in-app auto-updater, so once v2 ships as a higher version tag, the phone can update itself.
- **BUILD ENV: there IS a local toolchain now (2026-07-09).** No more blind builds.
  ```bash
  export JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10"
  export ANDROID_HOME="C:/Users/mirko/tools/android-sdk"
  cd C:/Users/mirko/glass-todo && ./gradlew testDebugUnitTest --console=plain
  ```
  Installed under `C:/Users/mirko/tools/` (self-contained, no admin, not on PATH): Temurin JDK 17, Gradle 8.11.1, Android SDK (`platforms;android-36`, `build-tools;35.0.0`, `platform-tools`). `local.properties` carries `sdk.dir` (gitignored). The repo now has a Gradle wrapper, so `./gradlew` is enough. First run takes ~12 min (dependency + Robolectric jar downloads); after that it is seconds. CI remains the merge gate, but **run the suite locally before pushing** — it is faster and it survives GitHub outages.
- There is still no emulator/device except Mirko's phone, so the JVM tier is what you can actually run.

## The verify loop (how to continue every step)
```bash
cd C:/Users/mirko/glass-todo
# ...edit files...
# 1) run the suite LOCALLY first (seconds, once warm):
JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10" ANDROID_HOME="C:/Users/mirko/tools/android-sdk" \
  ./gradlew testDebugUnitTest --console=plain
# 2) then push and let CI confirm:
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

- **Steps 8 + 9** — the Glance widget (the actual tap fix) + the worker demotion. commits `ed316bd` → `e5c66fc` → `326247a` → `489e81a` → `7255e73` (green, run `29013250728`).
  - **Root cause, read straight off v1's `TodoWidgetProvider.onReceive`:** every row shared ONE mutable `PendingIntent` template and relied on per-row fill-in extras merging into it. Launchers that drop the merge left `getStringExtra(EXTRA_ID) ?: return` — the checkbox did nothing. Glance registers one typed action per item: no template, nothing to merge.
  - `widget/WidgetGlanceContent.kt` — the single composable that the real widget AND the tests render.
  - `widget/TodoGlanceWidget.kt` — observes Room **inside `provideContent`**, so any write (app, widget tap, realtime) re-renders it. The widget never touches the network; v1 did `runBlocking` OkHttp on the binder thread (ANR-class).
  - `widget/ToggleTodoAction.kt` — reads the NEW checked value from `ToggleableStateKey` (never recomputed from a stale render), writes Room, pushes. Runs in a WorkManager worker (~10 min budget) instead of a ~10 s BroadcastReceiver.
  - `widget/TodoGlanceReceiver.kt` + manifest receiver; `todo_widget_info.xml` `initialLayout` → `@layout/glance_default_loading_layout`.
  - `test/.../WidgetActionUnitTest.kt` — proves each row carries its OWN id, that `done` renders per row, and that `+` starts an Activity (not a broadcast). No emulator, no launcher.
  - `ServiceLocator` calls `TodoGlanceWidget().updateAll(ctx)` on every realtime snapshot. `QuickAddActivity` writes through `TodoStore`.
  - **Step 9:** `work/TodoSyncWorker.kt` = 30-min safety net that owns `refreshSession()` + one pull + `updateAll`. The 15-min poll is gone. Scheduled from `TodoGlanceReceiver.onEnabled`, cancelled in `onDisabled`.
  - **DELETED:** `widget/{TodoWidgetProvider,TodoWidgetService,SupabaseTodosRest,TodoSyncWorker}.kt`, `res/layout/widget_todo*.xml`.

## ⚠️ When this ships to the phone — read this before installing

1. **You must UNINSTALL v1.0.4 first.** Up to v1.0.4 the release workflow shipped `assembleDebug`, signed with the throwaway debug keystore that AGP generates fresh on every CI runner. Verified: v1.0.3's certificate is `77435cc2…d8b8`, v1.0.4's is `4a05b39e…975a` — different keys. Android rejects an install over an app signed with a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which means **the in-app updater has never once updated in place**; every "update" was really an uninstall + reinstall.
   From v1.1.0 the APK is signed with a stable key (`CN=Mirko Milano Gumenyuk`, SHA-256 `e6828727…ac5d`), so v1.1.0 → v1.2.0 and onward WILL update in place. v1.1.0 itself is the last forced uninstall.
2. **The widget disappears and must be re-added.** The receiver class changed (`TodoWidgetProvider` → `TodoGlanceReceiver`); a provider rename is a brand-new widget as far as the launcher is concerned.
3. **You will have to sign in again** (`mirko@glasstodo.app`). Uninstalling clears the stored session. No todos are lost — they live in Supabase.

### 🔑 The signing keystore — back this up
- `C:/Users/mirko/keys/glass-todo-release.jks` + `glass-todo-release.pass` (the password, one line, no newline).
- Also mirrored into repo secrets: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`glasstodo`), `SIGNING_KEY_PASSWORD`.
- **If both the local file and the secret are lost, no future build can ever update an installed app** — the only recovery is uninstall + reinstall, forever. Copy the `.jks` and the `.pass` somewhere durable (password manager / OneDrive).
- The keystore is NOT in git and never should be: the repo is public.

## How to ship a release
```bash
# 1) bump in app/build.gradle.kts: versionCode + versionName   (already at 5 / "1.1.0")
# 2) run the suite locally, then tag:
git tag v1.1.0 && git push origin v1.1.0
```
`release.yml` then materialises the keystore from the secret, runs `assembleRelease`, **fails the job unless the APK carries the expected certificate DN** (so a mis-signed APK can never be published), and attaches `app-release.apk` to the GitHub Release. `Updater.check()` picks the first `.apk` asset and compares `tag_name` (minus the `v`) against `BuildConfig.VERSION_NAME`.

## ⚠️ Naming / transition rule
The offline-first repository is **`data/TodoStore.kt`**. The whole v1 stack (`TodoRepository`, `Todo`, the RemoteViews widget, `SupabaseTodosRest`) is GONE. Nothing left to transition.

## Next steps (blueprint §6 checklist; §3 has the code, §7 has version flags)
7c. **Roborazzi goldens**: add the roborazzi plugin + deps (§3.1/§3.2), `TodoScreenScreenshotTest` rendering `TodoScreenContent` in its four states, and `WidgetScreenshotTest` rendering `WidgetGlanceContent`. Record the goldens **in CI** (`recordRoborazziDebug`, upload as an artifact, commit the PNGs) — goldens recorded on another machine will not match the CI renderer. CI then runs `verifyRoborazziDebug`. Watch out: `ShimmerList` runs an infinite animation; Robolectric freezes the clock, but pin `@Config(qualifiers = Pixel5)` + `@GraphicsMode(NATIVE)` anyway.
10. **Polish** (§3.15–3.16): haptics, `Motion.kt`, `GlassLevel`/specular edge, decorrelated aurora, widget Material You (`values-v31/colors.xml`). Re-record goldens.
11. **Emulator tier** (§5.2–5.3): `WidgetPinAndTapTest` (UIAutomator) + the `instrumented` CI job (advisory, not the merge gate). The JVM `WidgetActionUnitTest` already proves the actions are wired; this proves it end-to-end on a real launcher.
12. **Hardening** (optional): R8, backup rules, baseline profile.

**Ship it:** bump `versionCode`/`versionName` in `app/build.gradle.kts` (currently 4 / "1.0.4"), tag `v1.1.0` → `release.yml` builds the APK. v1.0.4's in-app updater will offer it. Re-add the widget to the home screen (see the receiver-rename note above).

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

### Glance 1.1.1 — verified against the frozen API txt (AndroidX emits no `1.1.1.txt`; the 1.1.x surface is `glance/*/api/1.1.0-beta01.txt`)
- **The blueprint had the assertion name wrong.** It is `assertHasRunCallbackClickAction<T>()`, NOT `assertHasActionRunCallbackClickAction`. There is no such symbol.
- `runGlanceAppWidgetUnitTest {}` (`androidx.glance.appwidget.testing.unit`) needs no emulator and no opt-in, but it **DOES need Robolectric**: `ActionParameters` is backed by a real `android.os.Bundle`, so on the plain JVM every test dies with `Method putInt in android.os.BaseBundle not mocked`. Annotate the class `@RunWith(RobolectricTestRunner::class)`. (The docs call it a "unit test" API, which reads as Robolectric-free. It isn't.) Inside: `provideComposable {}`, `onNode(matcher)`.
- `assertHasStartActivityClickAction<T : Activity>()` (reified, `androidx.glance.testing.unit`) — use this, not the `Intent` overload, or you need an Android context in a JVM test.
- `hasText(...)` matches on **substring**; `hasTextEqualTo(...)` is the exact one. `assertExists()` / `assertDoesNotExist()` are members of `GlanceNodeAssertion`.
- `CheckBox(checked, onCheckedChange: Action?, modifier, text, style, colors, maxLines)` — the `Action?` overload is the one that takes `actionRunCallback<T>()`. **BUT:** Glance wraps a checkbox's action in an internal `CompoundButtonAction`, and no public test API can see through it — `assertHasRunCallbackClickAction` / `hasRunCallbackClickAction` will never match a `CheckBox`. Put the action on the parent `Row` (`clickable(...)`) and leave `onCheckedChange = null`; then the wiring is assertable and the whole row is the tap target.
- Consequently `ToggleableStateKey` is unused here: the row action carries only the id and `ToggleTodoAction` flips whatever Room currently holds (`TodoStore.toggle(id)`), which is strictly safer than trusting a value baked into a possibly-stale widget render.
- `onNode(matcher)` fails if the matcher hits more than one node — that property is what makes `onNode(hasRunCallbackClickAction<T>(params_for_id_1)).assertExists()` a real proof that per-row actions are distinct.
- `ActionParameters.Key<T>.to(value)` is a **member** of `Key`, so it wins over kotlin's stdlib `to` — no import, no ambiguity, no accidental `kotlin.Pair`.
- `ToggleableStateKey` (`androidx.glance.appwidget.action`) carries the NEW checked value into the callback.
- `defaultWeight()` is a **RowScope/ColumnScope member**, not a top-level import. `cornerRadius()` lives in `glance-appwidget`, `background()`/`clickable()` in glance core.
- `GlanceTheme` is in **glance core** (`androidx.glance`); `glance-material3` is only needed to build dynamic M3 `ColorProviders`.
- `@layout/glance_default_loading_layout` is a real public resource of glance-appwidget — confirmed in its `res/layout/`.

### Room / SQLite
- SQLite explicitly allows `x NOT IN ()`, so `deleteMissing(emptyList())` is safe (it clears all SYNCED rows, which is the correct meaning of "the server has nothing").
- `SyncStatus` is persisted by name via a `TypeConverter`, so `WHERE syncStatus != 'PENDING'` in raw `@Query` SQL works.

## ⚠️ Anomaly to be aware of (2026-07-09)
Commit `e7139f8` ("feat(data): Realtime selectAsFlow…", `Co-Authored-By: Claude Haiku 4.5`) was **not** made by the session doing this work — something else committed an in-progress version of `RealtimeSync.kt` mid-edit. No auto-commit hook exists in `~/.claude/settings.json` and the repo has no git hooks, but several `claude.exe` processes were running, so a **second concurrent Claude Code session** is the likely author. Content-wise nothing was lost (the next commit superseded it), but if you see commits you did not make, check for another session before assuming corruption.
