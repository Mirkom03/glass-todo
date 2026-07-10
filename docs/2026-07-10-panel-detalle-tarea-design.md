# Panel de detalle de tarea — diseño (2026-07-10)

Estado: **aprobado por Mirko**, pendiente de plan de implementación.
Versión objetivo: `1.4.0` (versionCode 12), desde `1.3.3` (versionCode 11).

---

## 1. El problema

Hoy una tarea es un título, una etiqueta y una urgencia. No hay descripción, ni forma de editar
nada una vez creada: `TodoStore` solo expone `add`, `toggle` y `delete`.

Y en las dos superficies, tocar **cualquier** parte de la fila marca la tarea. Eso significa que la
fila no puede llevar ninguna otra acción: no hay hueco para "abrir la tarea".

## 2. Qué construimos

Tocar el **círculo** marca o desmarca. Tocar **el resto de la fila** abre una hoja que sube desde
abajo con la tarea entera: título, etiqueta, urgencia, una **descripción editable**, y borrar.

Mismo comportamiento en la app y en el widget, y el mismo composable pintando la hoja en ambos
sitios — así no pueden divergir visualmente, que es exactamente el fallo que costó v1.3.0→v1.3.1.

La hoja incluye un círculo de tick (`TaskCheck`). Un panel de tarea desde el que no se puede
completar la tarea sería absurdo, y sigue siendo un círculo: la regla se respeta.

## 3. Decisiones y por qué

### 3.1 La hoja es una Activity, no una cara del widget

Los App Widgets no admiten campos de texto editables. La lista blanca de vistas de `RemoteViews`
—Javadoc de `android.widget.RemoteViews`— es cerrada y no incluye `EditText`, ni en API 34, ni 35,
ni 36 (`targetSdk = 36`). «Descendants of these classes are not supported.»

No hay atajo por versión de Android. La descripción editable obliga a una Activity, igual que
`QuickAddActivity`.

### 3.2 Tema translúcido, sin XML nuevo

`@android:style/Theme.Translucent.NoTitleBar` existe desde API 1 y se referencia inline en el
manifest, igual que las dos Activities actuales. No hace falta crear el primer `themes.xml` del
proyecto.

`ModalBottomSheet` (Material3 1.4.0) se hospeda en su **propia sub-ventana Dialog** con su propio
scrim. Si la ventana de la Activity anfitriona es opaca, su fondo sólido se ve tras el scrim: es el
clásico «fondo negro». De ahí `windowIsTranslucent`.

> **Trampa de API 26 (nuestro `minSdk`).** Una Activity translúcida **no puede** declarar
> `android:screenOrientation`. En Android 8.0 lanza
> `IllegalStateException: Only fullscreen opaque activities can request orientation`.
> Corregido solo en 8.1. **No declarar orientación en `TaskDetailActivity`.**

Descartamos reusar `Theme.DeviceDefault.Dialog` (el de `QuickAddActivity`): fija
`windowIsFloating=true`, que centra y envuelve el contenido — correcto para una tarjeta-formulario,
incorrecto para una hoja anclada abajo, a ancho completo y arrastrable.

> **Hallazgo colateral.** `Theme.DeviceDefault.Dialog` **es Material You en API 31+**. El marco de
> la tarjeta de `QuickAddActivity` se pinta hoy con los colores dinámicos del sistema, no con Ink.
> Es la misma incoherencia app/widget que motivó v1.3.1, viva en el repo. Un tema translúcido la
> esquiva porque no pinta superficie de plataforma. **Fuera de alcance aquí**, pero anotado.

### 3.3 El cierre de la hoja

`onDismissRequest` puede llamar `finish()` directamente. En Material3 1.4.0, los cierres por gesto,
por scrim y por back ya ejecutan `sheetState.hide()` internamente e invocan `onDismissRequest`
dentro de `invokeOnCompletion { if (!isVisible) ... }`, así que la animación de salida ya ocurrió.

Solo los **cierres programáticos** (los botones Guardar y Borrar) necesitan orquestar
`scope.launch { sheetState.hide() }.invokeOnCompletion { finish() }`; ahí un `finish()` síncrono sí
se saltaría la animación.

El predictive-back lo registra el propio `ModalBottomSheetDialogWrapper` en el
`OnBackPressedDispatcher` de su Dialog. No hay que escribir `BackHandler`, ni tocar el manifest:
`enableOnBackInvokedCallback` es opt-out, no opt-in. La animación de *encogido* al arrastrar el
back solo aparece en Android 14+; en API 26–33 el back simplemente cierra. Aceptable.

No hace falta `enableEdgeToEdge()`: con `targetSdk = 36` Android impone edge-to-edge y no hay
opt-out.

## 4. La fila: dos zonas hermanas

### 4.1 Widget (`WidgetGlanceContent.TodoRow`)

Se quita el `.clickable` de la `Row` y se parte en dos rectángulos disjuntos:

```
Row  (SIN clickable, verticalAlignment = CenterVertically)
├── [barra de urgencia · como hoy]
├── Box(width 42.dp, padding vertical 6.dp)
│     .clickable(actionRunCallback<ToggleTodoAction>(id, !done))
│     └── Image(ic_check_on/off, 20.dp)   [contentAlignment = Center]
└── Box(defaultWeight(), padding vertical 6.dp)
      .clickable(actionStartActivity<TaskDetailActivity>(actionParametersOf(idKey to todo.id)))
      └── Row { Text(título, defaultWeight) · Text(etiqueta) }
```

El padding vertical de la `Row` (6dp arriba y abajo) **se mueve dentro de cada zona**, de modo que
el blanco de tap del tick pasa de 20×20dp a ≈42×32dp — 3,4× más área — **sin que la fila crezca de
alto**. El ancho sobra (180–250dp+); la altura es el recurso escaso.

El círculo debe quedar visualmente donde está hoy. La aritmética exacta de dp se ajusta contra
`WidgetScreenshotTest`, no a ojo.

**Se borra el comentario de `WidgetGlanceContent.kt:326-328`** («Glance registers one typed action
per row — no template, nothing to merge») y el equivalente de `AndroidManifest.xml:28-32`. Es falso
(ver §7.1).

### 4.2 App (`TaskRow`)

`TaskRow(task, onToggle, onOpen)`, partida en las **mismas dos zonas hermanas** que el widget, con el
padding vertical dentro de cada `clickable`. El tick queda con un blanco de 44×52dp y el círculo
sigue midiendo 22dp.

> **Corregido tras implementarlo (2026-07-10).** Este apartado decía que en Compose
> `minimumInteractiveComponentSize()` daba el blanco de 48dp «sin coste de layout». **Es falso:**
> crece el tamaño *medido*. Al aplicarlo, las filas pasaron de ~54dp a 78dp uniformes y los títulos se
> desplazaron a la derecha. Se vio en el screenshot de Roborazzi, no se dedujo. La solución es la
> misma que en el widget: pagar el blanco en ancho, no en alto.

`TaskCheck` pasa a ser puramente visual; el tap lo posee quien lo envuelve.

Se corrige el comentario de `TaskRow.kt:52` («the whole row is the tap target, exactly like the
widget»), que vuelve a ser verdad con la nueva semántica.

## 5. Los datos

### 5.1 Postgres primero

```sql
alter table public.todos add column notes text;
```

Nullable y sin default: cambio de catálogo instantáneo, sin reescritura de tabla ni lock largo.

- **RLS:** sin cambios. Las cuatro políticas de `public.todos` (`owner reads/inserts/updates/deletes`)
  filtran por `user_id` y son agnósticas a columnas.
- **Realtime:** sin cambios. `replica identity` ya es `full` y la tabla ya está en la publicación
  `supabase_realtime`, así que un `UPDATE` de `notes` emite un `postgres_changes` con la columna.

Se aplica **antes** de publicar la app: los clientes en 1.3.3 ignoran la columna nueva.

### 5.2 Room

`AppDatabase` está en `version = 1`, con `exportSchema = false`, y `ServiceLocator:33` construye la
DB **sin** `addMigrations` ni `fallbackToDestructiveMigration`.

Subir a `version = 2` sin escribir la migración lanza
`IllegalStateException: A migration from 1 to 2 was required but not found` **al abrir la base de
datos** — es decir, crash de arranque para todo el que ya tenga la app instalada.

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `notes` TEXT")
    }
}
```

`String?` en la entidad genera una columna `TEXT` nullable; el `ADD COLUMN` debe coincidir
exactamente o Room falla la validación del identity-hash. Las filas viejas quedan con `notes = NULL`.

Se activa `exportSchema = true` y se añade a `app/build.gradle.kts`:

```kotlin
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
```

con `app/schemas/` versionado en git, para poder escribir un `MigrationTestHelper` (ver §8).

### 5.3 El bug de sincronización que hay que arreglar

`supabase-kt` construye su `Json` activando **solo** `ignoreUnknownKeys = true`
(`SupabaseClientBuilder.defaultSerializer`). No toca `encodeDefaults`, que en kotlinx vale `false`
por defecto.

Consecuencia: **`toDto()` omite del JSON todo campo igual a su valor por defecto** — `done = false`,
`project = null`, `priority = 0`.

Y `TodoStore.drainPending()` (línea 64) reenvía con `remote.upsert(row.toDto())`. Un upsert es
`INSERT ... ON CONFLICT DO UPDATE`, y el `SET` del conflicto solo escribe las columnas presentes en
el payload.

> Si des-ticas una tarea sin red, la fila queda `PENDING`. Cuando el drain la reenvía, `done = false`
> **no viaja**, y el servidor conserva `done = true`. **El des-tick se pierde en el sync.** Es el
> primo hermano del bug del 2026-07-09 («no puedo destickearlo»), una capa más abajo. Con `notes`
> pasaría lo mismo: borrar una nota nunca se sincronizaría.

**Arreglo:** `encodeDefaults = true` en el serializer de `SupabaseClient`. Una línea. Es un bug de
producción preexistente, no lo introduce esta feature, y bloquea el borrado de notas.

### 5.4 El camino de escritura del `update`

`TodoStore.update(id, title, project, priority, notes)` sigue el patrón optimista ya probado de
`toggle()`: escribe en Room primero (marcando `PENDING`), empuja después, y hace settle/rollback
guardado para que un push lento no pise una edición más nueva.

El push usa un **UPDATE dirigido por id con `set()` explícito**, calcado de `TodoRemoteImpl.setDone`,
**nunca** `upsert(toDto())`. Dos razones:

1. No toca `done`, `created_at` ni `completed_at`, así que no puede pisar un toggle concurrente
   hecho desde otro dispositivo.
2. `set("notes", null)` y `set("project", null)` **sí envían el null**, así que vaciar un campo se
   sincroniza. Con el DTO completo, no.

`reconcile()` no es un riesgo: filtra explícitamente las filas `PENDING` antes del `upsertAll`, así
que un snapshot del servidor con notas viejas no pisa una edición local sin sincronizar.

`completed_at` **no es un riesgo**: la tabla no tiene triggers, la columna está siempre a `NULL`, y
no está en el DTO. Es vestigial. No hay que protegerla ni añadirla.

### 5.5 Lo que `encodeDefaults = true` también cambia

`drainPending()` reenvía las filas `PENDING` con `upsert(row.toDto())` — el DTO **completo**, no un
`set()` dirigido. Hoy ese payload omite los campos que valen su default; mañana los incluirá todos.

Eso arregla el des-tick perdido (§5.3), pero **ensancha el last-write-wins**: si una fila queda
`PENDING` por una edición de notas, y mientras tanto otro dispositivo cambia `done` en el servidor,
el próximo drain sobrescribirá `done` con el valor local, que puede ser viejo.

Lo aceptamos. La app es de un solo usuario con pocos dispositivos, la ventana es la que va desde un
push fallido hasta el siguiente drain, y la alternativa (un `set()` por campo también en el drain,
o vector de versiones por columna) es desproporcionada. Queda escrito para que la próxima vez que
algo se «des-sincronice raro» se mire aquí primero.

## 6. Ficheros

**Nuevos**
- `ui/TaskDetailSheet.kt` — `TaskDetailContent` stateless (recibe la tarea + lambdas) y el wrapper
  `TaskDetailSheet` que lo mete en `ModalBottomSheet`. Sigue el patrón de `TodoScreenContent`.
- `widget/TaskDetailActivity.kt` — lee `intent.getStringExtra("todo_id")`, observa la tarea, hospeda
  `TaskDetailSheet` bajo `ListoTheme`, y `finish()` al cerrar.
- `app/src/test/.../ui/TaskDetailScreenshotTest.kt`
- `supabase/002_todos_notes.sql`

**Modificados**
- `domain/TodoUi.kt` — `+ notes: String? = null`
- `data/local/TodoEntity.kt` — `+ notes`, y `toUi()` lo mapea
- `data/local/AppDatabase.kt` — `version = 2`, `exportSchema = true`, `MIGRATION_1_2`
- `data/local/TodoDao.kt` — `+ observeById(id)`, settle/rollback del update
- `data/remote/TodoDto.kt` — `+ notes`, en `toEntity` y `toDto`
- `data/remote/TodoRemote.kt` + `TodoRemoteImpl.kt` — `+ update(...)` con `set()` explícito
- `data/SupabaseClient.kt` — `encodeDefaults = true`
- `data/TodoStore.kt` — `+ update(...)` optimista
- `di/ServiceLocator.kt` — `.addMigrations(MIGRATION_1_2)`
- `ui/TodoViewModel.kt` — `+ update(...)`
- `ui/TodoScreen.kt` — estado de hoja abierta, wiring
- `ui/TaskRow.kt` — separar `onToggle` (círculo) de `onOpen` (fila)
- `widget/WidgetGlanceContent.kt` — fila en dos zonas; borrar el comentario falso
- `AndroidManifest.xml` — registrar `TaskDetailActivity` (`exported=false`,
  `@android:style/Theme.Translucent.NoTitleBar`, **sin `screenOrientation`**); corregir el comentario
- `app/build.gradle.kts` — `versionCode = 12`, `versionName = "1.4.0"`, `ksp` schemaLocation
- Tests: `TodoDtoMappingTest`, `TodoStoreTest`, `WidgetScreenshotTest`

## 7. Hechos verificados que sostienen el diseño

Todo lo de esta sección se comprobó contra el bytecode de los artefactos cacheados que usa el repo
(`glance-appwidget-1.1.1`, `material3-android/1.4.0`, `supabase-kt-runtime`), contra la fuente de
AndroidX, contra la doc oficial, y contra la base de datos real (`wkjfnpjklmikswgofekq`).

### 7.1 El comentario del repo sobre Glance es falso

Dentro de una `LazyColumn`, Glance **sí** usa `setPendingIntentTemplate` + `setOnClickFillInIntent`.
`ApplyActionKt.applyAction` ramifica en `TranslationContext.isLazyCollectionDescendant()`: si es
`true` llama `RemoteViews.setOnClickFillInIntent(viewId, intent)`; si no, `setOnClickPendingIntent`.
El template lo pone `LazyListTranslatorKt.translateEmittableLazyList`.

Es la misma **familia** de mecanismo que los comentarios del repo culpan del bug de taps de v1. La
diferencia real es que Glance monta el template correctamente: el flag es `184549384` = `0x0B000008`
= `FLAG_UPDATE_CURRENT | FLAG_MUTABLE | FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT | FILL_IN_COMPONENT`. Un
template inmutable haría que el fill-in se ignore en silencio en API 31+ — justo la clase de fallo
«el tap no hacía nada» de v1.

**Lo que esto significa para nosotros:** el toggle que hoy funciona en el móvil de Mirko **ya viaja
por ese camino**. Añadir una segunda zona clicable no estrena mecanismo. No des-riesga a cero, pero
tampoco reintroduce nada nuevo.

### 7.2 Dos zonas clicables por fila no colisionan

`applyAction` opera **por elemento**, atando la acción al `viewDef.mainViewId` de esa vista. Cada
fill-in lleva una data-Uri única (`createUniqueUri`) que incluye el `viewId`. Dos hermanas del mismo
item tienen viewIds distintos, luego Uris distintas, luego intents distintos, ambos resueltos contra
el mismo template.

Además cada item lazy se traduce a un `RemoteViews` **independiente**
(`RemoteCollectionItems.Builder.addItem`, `setHasStableIds(true)`, `setViewTypeCount`), con su propio
espacio de viewIds. Por eso el `Spacer` condicional de urgencia (árboles de hijos heterogéneos entre
filas) **no es un problema**: es un caso soportado de primera clase.

No hay explosión de PendingIntents: el fill-in es un `Intent`, no un `PendingIntent`. La segunda zona
cuesta cero PendingIntents.

### 7.3 Por qué hermanas y no anidadas

`setOnClickPendingIntent` está **prohibido** en vistas hijo de un item de colección; `setOnClickFillInIntent`
por vista es lo soportado. A nivel de `RemoteViews`, anidar (`Row.clickable` + `Image.clickable`) y
partir en dos hermanas producen el mismo patrón: dos fill-in en un item.

Elegimos hermanas porque los rectángulos son disjuntos y la asignación tap→acción no depende del
despacho padre/hijo. No porque anidar sea demostrablemente peligroso: el issue 300765232, que se citó
como prueba de eso, resultó tratar de **dos `LazyColumn` hermanas dentro de un `Row`**, topología
inversa. La razón es claridad, no miedo.

### 7.4 El bug histórico de v1.1.0 no aplica

El `CheckBox` decorativo se comía el touch porque `CompoundButton` es clickable y focusable por
defecto **a nivel de `View`**, no por `setOnCheckedChangeResponse`. Una `Image` no es un
`CompoundButton`. Seguimos usando `Image` para el círculo; nunca `CheckBox` ni `Switch`.

Y el bug `b/282445798` (colisión de claves) afecta **solo** a acciones lambda `clickable { }`, que
usan `currentCompositeKeyHash`. `actionRunCallback<T>(params)` y `actionStartActivity<T>(params)` son
tipadas y no dependen de ella. **Regla:** no usar el overload `clickable { }` dentro del bucle de
`items` sin un `key` único por fila.

### 7.5 Los ActionParameters llegan a la Activity

`ApplyActionKt.getStartActivityIntent` itera `parameters.asMap()`, usa `ActionParameters.Key.getName()`
como clave, construye un `Bundle` y llama `Intent.putExtras`. La Activity los lee con
`intent.getStringExtra("todo_id")`. El nombre de la `Key` debe coincidir exactamente.

### 7.6 Glance no tiene touch targets mínimos

No existe equivalente de `minimumInteractiveComponentSize` en los 722 `.class` de
`glance` + `glance-appwidget` 1.1.1. El área táctil **es** la caja del elemento clicable — que sí
crece con `padding` (el click y el padding se aplican a la misma view), pero no puede solaparse con
las filas vecinas: `Box`/`Row`/`Column` son wrap-content, así que un hijo de 48dp expande su
contenedor a 48dp.

Por eso el blanco se paga en **ancho**. Un check de 44dp de alto llevaría la fila a ≥56dp y bajaría
el widget grande de ~5 filas a ~3.

## 8. Verificación

| Qué | Cómo |
|---|---|
| Migración de Room 1→2 | `MigrationTestHelper` (requiere el `exportSchema = true` de §5.2) |
| `encodeDefaults` arregla el des-tick | Test de que `done = false` aparece en el JSON de `toDto()` |
| `notes` sobrevive el round-trip | `TodoDtoMappingTest.roundTrip_preservesEveryRemoteField` |
| `update()` es optimista, revierte, y no se deja pisar | `TodoStoreTest`, análogo a `toggle_aSlowFirstPush…` |
| La fila y la hoja se ven bien | `WidgetScreenshotTest`, `TaskDetailScreenshotTest` |
| **Los dos taps funcionan en un launcher real** | **Manual, en el móvil.** Ver abajo. |

> **`[no-automatizable: Robolectric no monta un AppWidgetHost]`** — Lo que **sí** cubre un test:
> `WidgetActionUnitTest.theCircleTicksAndTheRestOfTheRowOpensTheTask` prueba que cada fila registra
> **dos acciones distintas en dos nodos distintos** (`onNode` falla si un matcher casa con más de uno).
> Lo que **ningún test puede firmar** es que un launcher real despache esos dos fill-in intents a la
> vista correcta. Se verifica instalando el APK y tocando ambas zonas, en la fila con urgencia y en la
> fila sin urgencia. Sin esa comprobación la feature **no se da por cerrada**.

Construir y testear en local (no a ciegas contra el CI):

```bash
export JAVA_HOME="C:/Users/mirko/tools/jdk-17.0.19+10"
export ANDROID_HOME="C:/Users/mirko/tools/android-sdk"
./gradlew testDebugUnitTest --console=plain
```

## 9. Orden de entrega

1. `alter table public.todos add column notes text;` por Supabase MCP (los clientes en 1.3.3 la ignoran).
2. Capa de datos: entidad, migración, DTO, `encodeDefaults`, `remote.update`, `store.update`, + tests.
3. `TaskDetailContent` + `TaskDetailSheet` + screenshot test.
4. App: `TaskRow` en dos zonas, hoja en `TodoScreen`.
5. Widget: fila en dos zonas, `TaskDetailActivity`, manifest.
6. Build local, tests en verde, **verificación manual de los dos taps en el móvil**.
7. `versionCode = 12`, `versionName = "1.4.0"`, release firmado con el keystore estable.

## 10. Fuera de alcance

- El Material You que se cuela por `Theme.DeviceDefault.Dialog` en `QuickAddActivity` (§3.2).
- Añadir descripción desde `QuickAddActivity` al crear la tarea.
- Swipe para marcar (se descartó: recuperaría el gesto de una mano, pero es superficie nueva sin
  test y no es lo que se pidió).
- Reordenar, fechas, recordatorios.
