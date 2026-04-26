// ファイルパス: app/src/main/java/com/nexus/vision/NexusApplication.kt

package com.nexus.vision

import android.app.Application
import android.util.Log
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.cache.MyObjectBox
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalMonitor
import io.objectbox.BoxStore

/**
 * NEXUS Vision アプリケーションクラス
 *
 * 責務:
 *   - ThermalMonitor 初期化        (Phase 2)
 *   - NexusEngineManager 初期化    (Phase 2)
 *   - ObjectBox 初期化             (Phase 3 追加)
 *   - L1/L2 キャッシュ初期化        (Phase 3 追加)
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

    // Phase 3 追加: ObjectBox と キャッシュ
    lateinit var boxStore: BoxStore
        private set
    lateinit var l1Cache: L1PHashCache
        private set
    lateinit var l2Cache: L2InferenceCache
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // グローバル例外ハンドラ
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            // TODO Phase 11: 長期記憶にクラッシュログを保存
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "NEXUS Vision initialized — versionName=${BuildConfig.VERSION_NAME}")

        // Phase 2: ThermalMonitor
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startMonitoring()

        // Phase 2: NexusEngineManager
        NexusEngineManager.getInstance().initialize(this, thermalMonitor)

        // Phase 3 追加: ObjectBox 初期化
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .name("nexus-vision-db")
            .build()
        Log.i(TAG, "ObjectBox initialized")

        // Phase 3 追加: キャッシュ初期化
        l1Cache = L1PHashCache(boxStore)
        l2Cache = L2InferenceCache(boxStore)
        Log.i(TAG, "Caches initialized — L1: ${l1Cache.count()} entries, L2: ${l2Cache.count()} entries")
    }

    override fun onTerminate() {
        super.onTerminate()
        NexusEngineManager.getInstance().release()
        // Phase 3 追加: ObjectBox クローズ
        boxStore.close()
    }
}
