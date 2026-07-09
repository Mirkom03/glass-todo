package com.mirko.glasstodo

import android.app.Application
import com.mirko.glasstodo.di.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Builds the one Supabase client + Room DB that the app and the widget share, and starts the
        // realtime collector (which subscribes only once a session is authenticated).
        ServiceLocator.store(this)
    }
}
