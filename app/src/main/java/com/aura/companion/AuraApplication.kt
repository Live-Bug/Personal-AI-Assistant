package com.aura.companion

import android.app.Application
import com.aura.companion.data.db.AuraDatabase

class AuraApplication : Application() {

    val database: AuraDatabase by lazy {
        AuraDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AuraApplication
            private set
    }
}
