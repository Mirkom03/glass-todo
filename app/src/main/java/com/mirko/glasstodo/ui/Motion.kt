package com.mirko.glasstodo.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun rememberReducedMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** True only while the screen is RESUMED — gate the infinite aurora on this to save battery/GPU. */
@Composable
fun rememberResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ ->
            resumed.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return resumed.value
}
