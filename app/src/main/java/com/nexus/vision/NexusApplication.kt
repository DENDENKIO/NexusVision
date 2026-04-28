// ファイルパス: app/src/main/java/com/nexus/vision/NexusApplication.kt
package com.nexus.vision

import android.app.Application
import android.util.Log
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.MyObjectBox
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalMonitor
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
import io.objectbox.BoxStore

/**
 * NEXUS Vision アプリケーションクラス
 *
 * 責務:
 *   - ThermalMonitor 初期化        (Phase 2)
 *   - NexusEngineManager 初期化    (Phase 2)
 *   - ObjectBox 初期化             (Phase 3)
 *   - L1/L2 キャッシュ初期化        (Phase 3)
 *   - NexusSheetsIndex 初期化      (Phase 8.5)
 *   - SheetsQueryEngine 初期化     (Phase 8.5)
 *   - グローバル例外ハンドラ
 */
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

    // Phase 8.5 追加
    lateinit var sheetsIndex: NexusSheetsIndex
        private set
    lateinit var sheetsQueryEngine: SheetsQueryEngine
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
    }

    override fun onTerminate() {
        super.onTerminate()
        NexusEngineManager.getInstance().release()
        boxStore.close()
    }
}
