// ファイルパス: app/src/main/java/com/nexus/vision/NexusApplication.kt
package com.nexus.vision

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.MyObjectBox
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalMonitor
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
import com.nexus.vision.memory.LongTermMemory
import io.objectbox.BoxStore

class NexusApplication : Application() {

    companion object {
        private const val TAG = "NexusApp"

        @Volatile
        private lateinit var instance: NexusApplication

        fun getInstance(): NexusApplication = instance
    }

    lateinit var thermalMonitor: ThermalMonitor
        private set

    lateinit var boxStore: BoxStore
        private set
    lateinit var l1Cache: L1PHashCache
        private set
    lateinit var l2Cache: L2InferenceCache
        private set

    lateinit var sheetsIndex: NexusSheetsIndex
        private set
    lateinit var sheetsQueryEngine: SheetsQueryEngine
        private set
    lateinit var longTermMemory: LongTermMemory
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "NEXUS Vision initialized — versionName=${BuildConfig.VERSION_NAME}")

        // Phase 2
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startMonitoring()
        NexusEngineManager.getInstance().initialize(this, thermalMonitor)

        // Phase 3: ObjectBox
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .name("nexus-vision-db")
            .build()
        Log.i(TAG, "ObjectBox initialized")

        // Phase 3: キャッシュ
        l1Cache = L1PHashCache(boxStore)
        l2Cache = L2InferenceCache(boxStore)
        Log.i(TAG, "Caches initialized — L1: ${l1Cache.count()} entries, L2: ${l2Cache.count()} entries")

        // Phase 8.5: NexusSheets
        sheetsIndex = NexusSheetsIndex(boxStore)
        sheetsQueryEngine = SheetsQueryEngine(sheetsIndex)
        Log.i(TAG, "NexusSheets initialized — ${sheetsIndex.fileCount()} files, ${sheetsIndex.totalRowCount()} rows")

        // Phase 11: Long-Term Memory
        longTermMemory = LongTermMemory(boxStore)
        Log.i(TAG, "Phase 11: LongTermMemory initialized — ${longTermMemory.entryCount()} entries")

        // I: ProcessLifecycleOwner で確実にリソース解放（onTerminate は実機で呼ばれない）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "ProcessLifecycleOwner onDestroy — releasing resources")
                NexusEngineManager.getInstance().release()
                boxStore.close()
            }
        })
    }

    // I: onTerminate は非推奨（実機では呼ばれない）— バックアップとして残す
    override fun onTerminate() {
        super.onTerminate()
        NexusEngineManager.getInstance().release()
        boxStore.close()
    }
}
