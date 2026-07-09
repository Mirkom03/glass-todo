# Glass Todo — v2 rebuild progress & handoff

**Status (2026-07-09): steps 1–3 of 12 DONE and green in CI. Continue from step 4.**

Goal: rebuild the widget+app "the right way" so it's bug-free + self-verifying (the v1 widget had unreliable taps + an empty-widget bug). The FULL plan with copy-ready code for every step is in **`docs/v2-blueprint.md`** — read it. This file is the running progress + handoff. Deep research briefs are in `docs/v2-research.json`.

---

## Key facts
- **Repo:** github.com/Mirkom03/glass-todo (PUBLIC). Default branch `main`.
- **Supabase:** project `aide-studio` (ref `wkjfnpjklmikswgofekq`), reachable via the Supabase MCP. Table `public.todos` + owner-scoped RLS (**do NOT touch the RLS**).
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
# find + watch the CI run:
RID=$(gh run list -R Mirkom03/glass-todo --workflow android-ci.yml -L 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RID" -R Mirkom03/glass-todo --exit-status --interval 20
# on failure, read the real error:
gh run view "$RID" -R Mirkom03/glass-todo --log | grep -iE "e: file|error:|FAILED|expected|but was|Caused by|Unresolved|AAPT|BUILD FAILED"
```
Each step must end GREEN on `android-ci` before the next.

## Done (green, committed)
- **Step 1** — CI JVM test pipeline + `domain/ParseInput.kt` extracted & unit-tested (`ParseInputTest`). commit `ed7afc1`.
- **Step 2** — v2 deps in `gradle/libs.versions.toml` + `app/build.gradle.kts`: Room 2.8.3, KSP `2.2.20-2.0.2`, Glance 1.1.1, `realtime-kt`, DataStore 1.1.7, lifecycle 2.9.1 + test deps (Robolectric 4.14.1, Turbine, coroutines-test, room-testing). All resolve. commit `cec01e8`.
- **Step 3** — Room SSOT + offline-first repo + tests. Files: `domain/{TodoUi,SyncError}.kt`, `data/local/{TodoEntity,TodoDao,AppDatabase}.kt`, `data/remote/{TodoDto,TodoRemote}.kt`, `data/{AuthSource,TodoStore}.kt`, test `data/TodoStoreTest.kt` (optimistic add + rollback-on-permanent + keep-on-transient, all pass under Robolectric). commit `ecf9929`.

## ⚠️ Naming / transition rule
The new offline-first repository is **`data/TodoStore.kt`** (NOT `TodoRepository` — that class name is still used by the v1 UI/widget). Keep v1 files compiling until the cutover (steps 7–8), then rewrite UI+widget onto `TodoStore` via the ViewModel and DELETE the v1 files:
`data/TodoRepository.kt`, `ui/{TodoScreen,MainActivity,TaskRow}.kt` (rewrite), `widget/{TodoWidgetProvider,TodoWidgetService,SupabaseTodosRest}.kt`, `res/layout/widget_*.xml`. (Glance replaces the RemoteViews widget; `SupabaseTodosRest` → `TodoRemoteImpl`.) `widget/QuickAddActivity.kt` is kept but routed through `TodoStore`.

## Next steps (blueprint §6 checklist; §3 has the code, §7 has version flags)
4. **Remote + client** (§3.3, §3.5): `data/remote/TodoRemoteImpl.kt` (supabase-kt Postgrest; wrap errors as `RemoteException(status)`), edit `data/SupabaseClient.kt` → add `install(Realtime)` + **`enableLifecycleCallbacks = false`** (THE empty-widget fix — the real root cause, not `awaitInitialization`), `data/SupabaseAuthSource.kt` implements `AuthSource`. Unit-test DTO mapping.
5. **Realtime → Room** (§3.6): `data/RealtimeSync.kt`. Then run `docs`/§4.2 SQL on aide-studio via Supabase MCP `execute_sql`:
   `alter publication supabase_realtime add table public.todos;` and `alter table public.todos replica identity full;`
6. **ViewModel** (§3.7): `ui/{TodoUiState,TodoViewModel}.kt` + `TodoViewModelTest` (Turbine).
7. **Compose UI rewrite** (§3.8): rewrite `ui/TodoScreen.kt` (loading/empty/content/error states, optimistic via VM) + `ui/MainActivity.kt` (gate on `sessionStatus`, owns VM). ADD roborazzi plugin+deps (§3.1/§3.2) and `TodoScreenScreenshotTest` (Roborazzi); `./gradlew recordRoborazziDebug` once, commit PNG goldens; CI runs `verifyRoborazziDebug`.
8. **Glance widget** (§3.9–3.12) — the bug fix: `widget/{WidgetGlanceContent,TodoGlanceWidget,ToggleTodoAction,TodoGlanceReceiver}.kt` + `di/ServiceLocator.kt`, manifest receiver, `todo_widget_info.xml` initialLayout → `@layout/glance_default_loading_layout`. Delete v1 RemoteViews widget files. + `WidgetScreenshotTest` + `WidgetActionUnitTest` (`runGlanceAppWidgetUnitTest`).
9. **Worker** (§3.13): `work/TodoSyncWorker.kt` = token refresh + safety-net pull @30min; remove the 15-min poll.
10. **Polish** (§3.15–3.16): haptics, `Motion.kt`, `GlassLevel`/specular edge, shimmer, widget Material You (`values-v31/colors.xml`). Re-record goldens.
11. **Emulator tier** (§5.2–5.3): `WidgetPinAndTapTest` (UIAutomator) + the `instrumented` CI job (advisory, not the merge gate).
12. **Hardening** (optional): R8, backup rules, baseline profile.

When v2 is done: bump versionCode/Name, tag a new `v*` → the release APK builds; Mirko installs (or the in-app updater does it).

## Gotchas learned (don't re-hit)
- **Robolectric config** (`app/src/test/resources/robolectric.properties`): `sdk=34` (4.14 has no SDK-36 jar) + `application=android.app.Application` (the real `App.onCreate` inits Supabase, which throws on the JVM). Both are REQUIRED for any Robolectric/Roborazzi test.
- **`git commit -a` skips NEW files** → always `git add -A`.
- **Version stack that resolves:** AGP 8.9.1 (8.9.0 fails — deps need 8.9.1+), compileSdk 36 + `android.suppressUnsupportedCompileSdk=36` in gradle.properties, Compose BOM 2026.06.01, Kotlin 2.2.20, Haze 1.7.2 (do NOT bump to 2.0-alpha), supabase-kt 3.6.0.
- `animateColorAsState` lives in `androidx.compose.animation`, not `.core`.
- CI runner sometimes fails with "job was not acquired by Runner" = transient GitHub infra → just re-run (`gh run rerun <id>`).
- **Blueprint §7** enumerates every version-sensitive flag (KSP↔Kotlin lockstep, `selectAsFlow` signature to verify against supabase-kt 3.6.0, Glance 1.1.1 pin, Roborazzi golden env-sensitivity, emulator KVM). Read it before dep-touching steps.
