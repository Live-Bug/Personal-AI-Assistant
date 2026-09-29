package com.aura.companion

import android.app.Application
import com.aura.companion.ai.AuraAssistant
import com.aura.companion.ai.GemmaManager
import com.aura.companion.ai.OnlineLookupManager
import com.aura.companion.capture.CaptureController
import com.aura.companion.data.db.AuraDatabase
import com.aura.companion.pipeline.ConversationProcessor
import com.aura.companion.pipeline.ConversationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the app-wide pipeline: mic capture -> conversation tracker -> batch processor, plus the
 * shared Gemma instance. These outlive any screen so capture keeps running in the background
 * (kept alive by AuraForegroundService).
 */
class AuraApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AuraDatabase by lazy { AuraDatabase.getDatabase(this) }
    val gemma: GemmaManager by lazy { GemmaManager(this, appScope) }
    val online: OnlineLookupManager by lazy { OnlineLookupManager() }
    val capture: CaptureController by lazy { CaptureController(this, appScope) }
    val processor: ConversationProcessor by lazy { ConversationProcessor(database, gemma, appScope) }
    val tracker: ConversationTracker by lazy {
        ConversationTracker(database, capture, appScope, onClosed = processor::trigger)
    }
    val assistant: AuraAssistant by lazy { AuraAssistant(database, gemma, online) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AuraApplication
            private set
    }
}
