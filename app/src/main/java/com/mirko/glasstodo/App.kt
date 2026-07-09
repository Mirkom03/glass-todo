package com.mirko.glasstodo

import android.app.Application
import com.mirko.glasstodo.data.SupabaseClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SupabaseClient.init()   // one client for the whole process (app + widget share it)
    }
}
