package com.lezi.gf.app

import android.app.Application

class LeziGfApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.localDataGate.migrateIfNeeded()
    }
}
