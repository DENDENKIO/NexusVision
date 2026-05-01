// ファイルパス: app/src/main/java/com/nexus/vision/engine/NexusEngineManager.kt

package com.nexus.vision.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NEXUS Vision エンジン管理シングルトン
 *
 * 修正内容:
 *  A: ダミー EngineSocket → 実 LiteRT-LM Engine/Conversation
 *  D: Engine/Conversation の close() ライフサイクル管理
 *  F: _state.value 直接代入 → _state.update{} でアトミック更新
 *  G: forceReset() を suspend 化 + Dispatchers.IO
 */
class NexusEngineManager private constructor() {

    companion object {
        private const val TAG = "NexusEngineManager"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MIN_AVAILABLE_RAM_MB = 500L
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MIN_MODEL_SIZE_BYTES = 2_500_000_000L // 2.5 GB

        @Volatile
        private var instance: NexusEngineManager? = null

        fun getInstance(): NexusEngineManager =
            instance ?: synchronized(this) {
                instance ?: NexusEngineManager().also { instance = it }
            }
    }

    // ── コルーチンスコープ ──
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 状態管理 (F: update{} でアトミック更新) ──
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // ── 推論 mutex ──
    private val inferenceMutex = Mutex()

    // ── LiteRT-LM コンポーネント (A: 実 Engine/Conversation) ──
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isGpuBackend = false

    private var thermalMonitor: ThermalMonitor? = null
    private var appContext: Context? = null

    // ── 失敗カウンタ ──
    private var consecutiveFailures = 0

    fun initialize(context: Context, thermalMonitor: ThermalMonitor) {
        this.appContext = context.applicationContext
        this.thermalMonitor = thermalMonitor

        scope.launch {
            thermalMonitor.thermalLevel.collect { level ->
                handleThermalChange(level)
            }
        }

        Log.i(TAG, "NexusEngineManager initialized")
    }

    /**
     * エンジンをロードする。
     * GPU → CPU フォールバック。モデルサイズ検証あり。
     */
    suspend fun loadEngine() = withContext(Dispatchers.IO) {
        if (_state.value is EngineState.Initializing ||
            _state.value is EngineState.Ready
        ) {
            Log.w(TAG, "Engine already loading or ready, skipping")
            return@withContext
        }

        val context = appContext ?: run {
            _state.update { EngineState.Error("Context not available") }
            return@withContext
        }

        if (!hasEnoughRam(context)) {
            _state.update {
                EngineState.Error(
                    message = "利用可能メモリが ${MIN_AVAILABLE_RAM_MB}MB 未満です",
                    consecutiveFailures = consecutiveFailures
                )
            }
            return@withContext
        }

        _state.update { EngineState.Initializing }

        try {
            val modelPath = resolveModelPath(context)

            if (modelPath == null) {
                _state.update {
                    EngineState.Error(
                        message = "モデルファイルが見つかりません。\n" +
                                "期待名: $MODEL_FILENAME\n" +
                                "配置先: files/models/, /data/local/tmp/llm/, /sdcard/Download/\n" +
                                "adb push でモデルを転送してください。"
                    )
                }
                return@withContext
            }

            // モデルサイズ検証
            val modelFile = File(modelPath)
            val modelSizeBytes = modelFile.length()
            val modelSizeMb = modelSizeBytes / (1024 * 1024)
            Log.i(TAG, "Model path: $modelPath, size: $modelSizeBytes bytes ($modelSizeMb MB)")

            if (modelSizeBytes < MIN_MODEL_SIZE_BYTES) {
                _state.update {
                    EngineState.Error(
                        message = "モデルファイルが破損しています (${modelSizeMb}MB)。\n" +
                                "正しいサイズ: 約2,583MB (2.58GB)。\n" +
                                "再ダウンロードしてください:\n" +
                                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
                    )
                }
                return@withContext
            }

            val cacheDir = context.cacheDir.absolutePath

            // GPU バックエンドを最初に試行
            var lastError: Exception? = null
            try {
                Log.i(TAG, "Trying GPU backend...")
                val gpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    cacheDir = cacheDir
                )
                val gpuEngine = Engine(gpuConfig)
                gpuEngine.initialize()
                engine = gpuEngine
                isGpuBackend = true
                Log.i(TAG, "★ GPU backend initialized successfully")
            } catch (e: Exception) {
                Log.w(TAG, "GPU backend failed: ${e.message}")
                lastError = e
                engine = null
                isGpuBackend = false
            }

            // CPU フォールバック
            if (engine == null) {
                try {
                    Log.i(TAG, "Trying CPU backend...")
                    val cpuConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDir
                    )
                    val cpuEngine = Engine(cpuConfig)
                    cpuEngine.initialize()
                    engine = cpuEngine
                    isGpuBackend = false
                    Log.i(TAG, "★ CPU backend initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "CPU backend also failed: ${e.message}")
                    _state.update {
                        EngineState.Error(
                            message = "エンジン初期化失敗: ${lastError?.message ?: e.message}",
                            cause = e,
                            consecutiveFailures = ++consecutiveFailures
                        )
                    }
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        forceReset()
                    }
                    return@withContext
                }
            }

            // Conversation 作成
            createNewConversation()

            consecutiveFailures = 0
            _state.update { EngineState.Ready }
            Log.i(TAG, "Engine loaded — backend=${if (isGpuBackend) "GPU" else "CPU"}")

        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "Engine load failed (attempt $consecutiveFailures)", e)
            _state.update {
                EngineState.Error(
                    message = "エンジン初期化失敗: ${e.message}",
                    cause = e,
                    consecutiveFailures = consecutiveFailures
                )
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                forceReset()
            }
        }
    }

    /**
     * 新しい Conversation を作成する。
     * (D: 既存 Conversation を安全に close)
     */
    private fun createNewConversation() {
        try { conversation?.close() } catch (e: Exception) {
            Log.w(TAG, "Error closing previous conversation", e)
        }
        conversation = null

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(
                "あなたはNexusVisionアシスタントです。日本語で簡潔に回答してください。"
            ),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        )
        conversation = engine!!.createConversation(conversationConfig)
        Log.i(TAG, "New conversation created")
    }

    /**
     * テキスト推論を実行する。
     * Dimensity 対策: SIGSEGV 時に Conversation を再作成。
     */
    suspend fun inferText(text: String): Result<String> = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = appContext ?: return@withContext Result.failure(
                IllegalStateException("Context not available")
            )

            if (!hasEnoughRam(context)) {
                unloadEngine()
                return@withContext Result.failure(
                    OutOfMemoryError("RAM < ${MIN_AVAILABLE_RAM_MB}MB — engine unloaded")
                )
            }

            when (val currentState = _state.value) {
                is EngineState.Ready, is EngineState.Degraded -> { /* OK */ }
                is EngineState.Error -> return@withContext Result.failure(
                    IllegalStateException("Engine in error state: ${currentState.message}")
                )
                else -> return@withContext Result.failure(
                    IllegalStateException("Engine not ready: $currentState")
                )
            }

            _state.update { EngineState.Processing("テキスト推論中...") }

            try {
                // プロンプト圧縮 (3-1)
                val prompt = if (text.length > 800) text.take(800) + "…(以下省略)" else text

                val conv = conversation ?: run {
                    createNewConversation()
                    conversation
                } ?: return@withContext Result.failure(
                    IllegalStateException("Conversation 作成失敗")
                )

                val response = conv.sendMessage(prompt)
                consecutiveFailures = 0
                _state.update { EngineState.Ready }
                Result.success(response.toString())
            } catch (e: Exception) {
                consecutiveFailures++
                Log.e(TAG, "Text inference failed (failures=$consecutiveFailures)", e)

                // Dimensity 対策: Conversation 再作成
                try {
                    createNewConversation()
                } catch (recreateErr: Exception) {
                    Log.e(TAG, "Conversation recreation failed", recreateErr)
                }

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    forceReset()
                } else {
                    _state.update {
                        EngineState.Error(
                            message = "推論エラー: ${e.message}",
                            cause = e,
                            consecutiveFailures = consecutiveFailures
                        )
                    }
                }
                Result.failure(e)
            }
        }
    }

    /**
     * 画像+テキスト推論を実行する。
     * Vision SIGSEGV 回避のため OCR→テキスト推論パイプラインを推奨。
     * GPU + visionBackend が安定するまでは inferImageWithOcrText() を使う。
     */
    suspend fun inferImage(imagePath: String, text: String): Result<String> =
        inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                val monitor = thermalMonitor
                if (monitor != null && !monitor.isVisionAllowed()) {
                    _state.update { EngineState.Degraded }
                    return@withContext Result.failure(
                        IllegalStateException("発熱レベルが高いため画像推論は一時停止中です")
                    )
                }

                val context = appContext ?: return@withContext Result.failure(
                    IllegalStateException("Context not available")
                )

                if (!hasEnoughRam(context)) {
                    unloadEngine()
                    return@withContext Result.failure(
                        OutOfMemoryError("RAM < ${MIN_AVAILABLE_RAM_MB}MB — engine unloaded")
                    )
                }

                when (val currentState = _state.value) {
                    is EngineState.Ready -> { /* OK */ }
                    is EngineState.Error -> return@withContext Result.failure(
                        IllegalStateException("Engine in error state: ${currentState.message}")
                    )
                    else -> return@withContext Result.failure(
                        IllegalStateException("Engine not ready: $currentState")
                    )
                }

                _state.update { EngineState.Processing("画像推論中...") }

                // OCR→テキスト推論パイプライン（Vision SIGSEGV 回避）
                return@withContext inferImageWithOcrFallback(imagePath, text)
            }
        }

    /**
     * OCR テキストを受け取って Gemma テキスト推論で分析する。
     * Vision ネイティブ呼び出しを回避する安全なパイプライン。
     */
    suspend fun inferImageWithOcrText(ocrText: String, userPrompt: String): Result<String> =
        inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                when (val currentState = _state.value) {
                    is EngineState.Ready, is EngineState.Degraded -> { /* OK */ }
                    else -> return@withContext Result.failure(
                        IllegalStateException("Engine not ready: $currentState")
                    )
                }

                _state.update { EngineState.Processing("画像テキスト分析中...") }

                try {
                    val trimmedOcr = if (ocrText.length > 500) ocrText.take(500) + "…" else ocrText
                    val combinedPrompt = buildString {
                        appendLine("以下は画像から抽出されたテキストです:")
                        appendLine("---")
                        appendLine(trimmedOcr)
                        appendLine("---")
                        if (userPrompt.isNotBlank()) {
                            appendLine("ユーザーの質問: $userPrompt")
                        } else {
                            appendLine("この内容を要約してください。")
                        }
                    }

                    val conv = conversation ?: run {
                        createNewConversation()
                        conversation
                    } ?: return@withContext Result.failure(
                        IllegalStateException("Conversation 作成失敗")
                    )

                    val response = conv.sendMessage(combinedPrompt)
                    consecutiveFailures = 0
                    _state.update { EngineState.Ready }
                    Result.success(response.toString())
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.e(TAG, "OCR-text inference failed", e)
                    try { createNewConversation() } catch (_: Exception) {}
                    _state.update {
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) EngineState.Idle
                        else EngineState.Error("推論エラー: ${e.message}", e, consecutiveFailures)
                    }
                    Result.failure(e)
                }
            }
        }

    /**
     * 画像パスから OCR テキストを推論に渡す（Vision ネイティブ不使用）
     */
    private fun inferImageWithOcrFallback(imagePath: String, text: String): Result<String> {
        // この関数は inferImage() から呼ばれ、
        // MainViewModel 側で OCR→テキストパイプラインを使うよう指示する
        _state.update { EngineState.Ready }
        return Result.failure(
            UnsupportedOperationException(
                "USE_OCR_PIPELINE: Vision ネイティブは SIGSEGV のため無効化。" +
                "MainViewModel.processImageMessage() で OCR→inferImageWithOcrText() を使用してください。"
            )
        )
    }

    fun currentState(): EngineState = _state.value

    /**
     * エンジンをアンロードする (D: 安全な close)
     */
    fun unloadEngine() {
        try { conversation?.close() } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation", e)
        }
        conversation = null

        try { engine?.close() } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        }
        engine = null
        isGpuBackend = false

        _state.update { EngineState.Idle }
        Log.i(TAG, "Engine unloaded")
    }

    /**
     * エンジンを強制リセットする (G: suspend + IO)
     */
    private suspend fun forceReset() = withContext(Dispatchers.IO) {
        Log.w(TAG, "Force resetting engine...")
        unloadEngine()
        consecutiveFailures = 0
        _state.update { EngineState.Idle }
    }

    /**
     * エンジンを完全に解放する (I: ProcessLifecycleOwner 経由で呼ぶ)
     */
    fun release() {
        unloadEngine()
        thermalMonitor?.stopMonitoring()
        _state.update { EngineState.Released }
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
                    _state.update { EngineState.Degraded }
                }
            }
            else -> {
                if (_state.value is EngineState.Degraded && engine != null) {
                    Log.i(TAG, "Thermal normal — restoring to Ready")
                    _state.update { EngineState.Ready }
                }
            }
        }
    }

    private fun resolveModelPath(context: Context): String? {
        val candidates = listOf(
            File(context.filesDir, "models/$MODEL_FILENAME"),
            File(context.filesDir, MODEL_FILENAME),
            File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME"),
            File(context.getExternalFilesDir(null), MODEL_FILENAME),
            File("/data/local/tmp/llm/$MODEL_FILENAME"),
            File("/sdcard/Download/$MODEL_FILENAME"),
            File("/sdcard/Android/data/${context.packageName}/files/$MODEL_FILENAME"),
            File("/sdcard/Android/data/${context.packageName}/files/models/$MODEL_FILENAME")
        )

        for (c in candidates) {
            if (c.exists() && c.length() > 0) {
                Log.d(TAG, "Model found at: ${c.absolutePath}")
                return c.absolutePath
            }
        }

        // フォールバック: ディレクトリ内の .litertlm を検索
        val searchDirs = listOf(
            File(context.filesDir, "models"),
            context.filesDir,
            context.getExternalFilesDir(null),
            File(context.getExternalFilesDir(null), "models")
        )
        for (dir in searchDirs) {
            if (dir?.isDirectory == true) {
                dir.listFiles()?.firstOrNull {
                    it.isFile && it.extension.equals("litertlm", ignoreCase = true) && it.length() > 0
                }?.let {
                    Log.w(TAG, "Fallback model found: ${it.absolutePath}")
                    return it.absolutePath
                }
            }
        }

        // assets からコピー（ファイルサイズが大きいため通常は不使用）
        val copied = copyModelFromAssets(context)
        if (copied != null) return copied

        Log.w(TAG, "Model file not found: $MODEL_FILENAME")
        return null
    }

    private fun findFirstLitertModel(dir: File): String? {
        if (!dir.exists() || !dir.isDirectory) return null
        return dir.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
            ?.absolutePath
    }

    private fun copyModelFromAssets(context: Context): String? {
        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists() && !targetDir.mkdirs()) return null

        val candidates = mutableListOf(MODEL_FILENAME)
        runCatching {
            context.assets.list("models")
                ?.filter { it.endsWith(".litertlm", ignoreCase = true) }
                ?.forEach { if (it != MODEL_FILENAME) candidates.add(it) }
        }

        for (name in candidates) {
            val outFile = File(targetDir, name)
            val copied = runCatching {
                context.assets.open("models/$name").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.absolutePath
            }.getOrNull()
            if (copied != null) {
                Log.i(TAG, "Model copied from assets: $copied")
                return copied
            }
        }
        return null
    }

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

// MutableStateFlow の update 拡張（アトミック更新、F対策）
private inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        val prevValue = value
        val nextValue = function(prevValue)
        if (compareAndSet(prevValue, nextValue)) return
    }
}
