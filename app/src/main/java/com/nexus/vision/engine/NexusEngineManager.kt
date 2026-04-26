// ファイルパス: app/src/main/java/com/nexus/vision/engine/NexusEngineManager.kt

package com.nexus.vision.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * NEXUS Vision エンジン管理シングルトン
 *
 * 責務:
 *  - LiteRT-LM エンジンの初期化・解放
 *  - mutex によるシリアライズ（同時推論禁止）
 *  - 連続失敗カウント → 3回で自動リセット
 *  - RAM < 500MB で自動アンロード
 *  - ThermalMonitor 連携で SEVERE 以上はテキストオンリー
 *
 * Phase 2: 基本実装
 * Phase 3: DEOR / キャッシュ連携
 * Phase 7: NCNN (Real-ESRGAN) 連携
 */
class NexusEngineManager private constructor() {

    companion object {
        private const val TAG = "NexusEngineManager"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MIN_AVAILABLE_RAM_MB = 500L
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        @Volatile
        private var instance: NexusEngineManager? = null

        fun getInstance(): NexusEngineManager =
            instance ?: synchronized(this) {
                instance ?: NexusEngineManager().also { instance = it }
            }
    }

    // ── コルーチンスコープ ──
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 状態管理 ──
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // ── 推論 mutex ──
    private val inferenceMutex = Mutex()

    // ── コンポーネント ──
    // Dummy EngineSocket to fix compilation after litert-lm removal
    class EngineSocket {
        var isInitialized = false
        fun initialize(modelPath: String, cacheDir: String, useGpu: Boolean) {}
        fun startConversation() {}
        fun sendTextMessage(text: String): String = "Dummy Response"
        fun sendImageMessage(imagePath: String, text: String): String = "Dummy Response"
        fun sendTextMessageStream(text: String) = kotlinx.coroutines.flow.emptyFlow<String>()
        fun release() {}
    }
    private val socket = EngineSocket()
    private var thermalMonitor: ThermalMonitor? = null
    private var appContext: Context? = null

    // ── 失敗カウンタ ──
    private var consecutiveFailures = 0

    /**
     * エンジンマネージャを初期化する。Application.onCreate() で呼ぶ。
     */
    fun initialize(context: Context, thermalMonitor: ThermalMonitor) {
        this.appContext = context.applicationContext
        this.thermalMonitor = thermalMonitor

        // 発熱レベルを監視して自動ダウングレード
        scope.launch {
            thermalMonitor.thermalLevel.collect { level ->
                handleThermalChange(level)
            }
        }

        Log.i(TAG, "NexusEngineManager initialized")
    }

    /**
     * エンジンをロードする（バックグラウンド）。
     * モデルファイルが存在しない場合は Error 状態になる。
     */
    suspend fun loadEngine() {
        if (_state.value is EngineState.Initializing ||
            _state.value is EngineState.Ready
        ) {
            Log.w(TAG, "Engine already loading or ready, skipping")
            return
        }

        val context = appContext ?: run {
            _state.value = EngineState.Error("Context not available")
            return
        }

        // RAM チェック
        if (!hasEnoughRam(context)) {
            _state.value = EngineState.Error(
                message = "利用可能メモリが ${MIN_AVAILABLE_RAM_MB}MB 未満です",
                consecutiveFailures = consecutiveFailures
            )
            return
        }

        _state.value = EngineState.Initializing

        try {
            val modelPath = resolveModelPath(context)

            if (modelPath == null) {
                _state.value = EngineState.Error(
                    message = "モデルファイルが見つかりません: $MODEL_FILENAME\n" +
                            "app/docs/SPEC_v4.0.md のモデル配置手順を参照してください。"
                )
                return
            }

            socket.initialize(
                modelPath = modelPath,
                cacheDir = context.cacheDir.absolutePath,
                useGpu = true
            )

            socket.startConversation()

            consecutiveFailures = 0
            _state.value = EngineState.Ready
            Log.i(TAG, "Engine loaded successfully — state=Ready")

        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "Engine load failed (attempt $consecutiveFailures)", e)

            _state.value = EngineState.Error(
                message = "エンジン初期化失敗: ${e.message}",
                cause = e,
                consecutiveFailures = consecutiveFailures
            )

            // 3回連続失敗で自動リセット
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "Max failures reached ($MAX_CONSECUTIVE_FAILURES), resetting...")
                forceReset()
            }
        }
    }

    /**
     * テキスト推論を実行する。
     * mutex でシリアライズし、同時リクエストを防ぐ。
     */
    suspend fun inferText(text: String): Result<String> = inferenceMutex.withLock {
        val context = appContext ?: return Result.failure(
            IllegalStateException("Context not available")
        )

        // RAM チェック
        if (!hasEnoughRam(context)) {
            unloadEngine()
            return Result.failure(
                OutOfMemoryError("RAM < ${MIN_AVAILABLE_RAM_MB}MB — engine unloaded")
            )
        }

        // エンジン状態チェック
        when (val currentState = _state.value) {
            is EngineState.Ready, is EngineState.Degraded -> { /* OK */ }
            is EngineState.Error -> return Result.failure(
                IllegalStateException("Engine in error state: ${currentState.message}")
            )
            else -> return Result.failure(
                IllegalStateException("Engine not ready: $currentState")
            )
        }

        _state.value = EngineState.Processing("テキスト推論中...")

        return try {
            val response = socket.sendTextMessage(text)
            consecutiveFailures = 0
            _state.value = EngineState.Ready
            Result.success(response)
        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "Text inference failed (failures=$consecutiveFailures)", e)

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                forceReset()
            } else {
                _state.value = EngineState.Error(
                    message = "推論エラー: ${e.message}",
                    cause = e,
                    consecutiveFailures = consecutiveFailures
                )
            }
            Result.failure(e)
        }
    }

    /**
     * 画像+テキスト推論を実行する。
     * 発熱レベル SEVERE 以上の場合は拒否。
     */
    suspend fun inferImage(imagePath: String, text: String): Result<String> =
        inferenceMutex.withLock {
            // 発熱チェック
            val monitor = thermalMonitor
            if (monitor != null && !monitor.isVisionAllowed()) {
                _state.value = EngineState.Degraded
                return Result.failure(
                    IllegalStateException("発熱レベルが高いため画像推論は一時停止中です")
                )
            }

            val context = appContext ?: return Result.failure(
                IllegalStateException("Context not available")
            )

            if (!hasEnoughRam(context)) {
                unloadEngine()
                return Result.failure(
                    OutOfMemoryError("RAM < ${MIN_AVAILABLE_RAM_MB}MB — engine unloaded")
                )
            }

            when (val currentState = _state.value) {
                is EngineState.Ready -> { /* OK */ }
                is EngineState.Error -> return Result.failure(
                    IllegalStateException("Engine in error state: ${currentState.message}")
                )
                else -> return Result.failure(
                    IllegalStateException("Engine not ready: $currentState")
                )
            }

            _state.value = EngineState.Processing("画像推論中...")

            return try {
                val response = socket.sendImageMessage(imagePath, text)
                consecutiveFailures = 0
                _state.value = EngineState.Ready
                Result.success(response)
            } catch (e: Exception) {
                consecutiveFailures++
                Log.e(TAG, "Image inference failed (failures=$consecutiveFailures)", e)

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    forceReset()
                } else {
                    _state.value = EngineState.Error(
                        message = "画像推論エラー: ${e.message}",
                        cause = e,
                        consecutiveFailures = consecutiveFailures
                    )
                }
                Result.failure(e)
            }
        }

    /**
     * テキスト推論をストリーミングで実行する。
     */
    fun inferTextStream(text: String) = socket.sendTextMessageStream(text)

    /**
     * エンジンをアンロードする。
     */
    fun unloadEngine() {
        socket.release()
        _state.value = EngineState.Idle
        Log.i(TAG, "Engine unloaded")
    }

    /**
     * エンジンを強制リセットする。
     * 連続失敗時に呼ばれる。
     */
    private fun forceReset() {
        Log.w(TAG, "Force resetting engine...")
        socket.release()
        consecutiveFailures = 0
        _state.value = EngineState.Idle
    }

    /**
     * エンジンを完全に解放する。Application 終了時に呼ぶ。
     */
    fun release() {
        socket.release()
        thermalMonitor?.stopMonitoring()
        _state.value = EngineState.Released
        Log.i(TAG, "NexusEngineManager released")
    }

    // ── Private Helpers ──

    private fun handleThermalChange(level: ThermalLevel) {
        Log.d(TAG, "Thermal level: $level")

        when {
            level.shouldStopEngine() -> {
                Log.w(TAG, "CRITICAL thermal — stopping engine")
                unloadEngine()
            }
            level.shouldDegradeVision() -> {
                if (_state.value is EngineState.Ready) {
                    Log.w(TAG, "SEVERE thermal — degrading to text-only")
                    _state.value = EngineState.Degraded
                }
            }
            else -> {
                // 発熱が収まったら Ready に復帰
                if (_state.value is EngineState.Degraded && socket.isInitialized) {
                    Log.i(TAG, "Thermal normal — restoring to Ready")
                    _state.value = EngineState.Ready
                }
            }
        }
    }

    /**
     * モデルファイルのパスを解決する。
     * 優先順位: 1) 外部ファイル 2) assets からコピー
     */
    private fun resolveModelPath(context: Context): String? {
        // 1) filesDir に既にあるか確認
        val filesModel = File(context.filesDir, "models/$MODEL_FILENAME")
        if (filesModel.exists()) {
            Log.d(TAG, "Model found at: ${filesModel.absolutePath}")
            return filesModel.absolutePath
        }

        // 2) 外部ストレージにあるか確認
        val externalModel = File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME")
        if (externalModel.exists()) {
            Log.d(TAG, "Model found at: ${externalModel.absolutePath}")
            return externalModel.absolutePath
        }

        // TODO Phase 2+: assets からコピー or ダウンロード機能
        Log.w(TAG, "Model file not found: $MODEL_FILENAME")
        return null
    }

    /**
     * 利用可能 RAM が閾値以上かチェックする。
     */
    private fun hasEnoughRam(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val availableMb = memInfo.availMem / (1024 * 1024)
        val enough = availableMb >= MIN_AVAILABLE_RAM_MB

        if (!enough) {
            Log.w(TAG, "Low RAM: ${availableMb}MB available (min: ${MIN_AVAILABLE_RAM_MB}MB)")
        }

        return enough
    }
}
