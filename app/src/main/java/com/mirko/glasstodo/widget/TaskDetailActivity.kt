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
 * visible behind it, and it renders the SAME TaskDetailContent the app does. One composable, two
 * hosts: the app and the widget cannot drift apart.
 *
 * The theme is declared in the manifest as @android:style/Theme.Translucent.NoTitleBar. It must NOT
 * declare android:screenOrientation: on API 26 (our minSdk) a translucent activity that requests an
 * orientation throws `Only fullscreen opaque activities can request orientation`.
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
            fun hideThenFinish() {
                scope.launch {
                    runCatching { sheetState.hide() }
                    finish()
                }
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
