// ファイルパス: app/src/main/java/com/nexus/vision/MainActivity.kt
package com.nexus.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.nexus.vision.os.ShareReceiver
import com.nexus.vision.ui.MainScreen
import com.nexus.vision.ui.MainViewModel
import com.nexus.vision.ui.theme.NexusVisionTheme
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.notification.InlineReplyHandler
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexus.vision.audio.TunerScreen
import com.nexus.vision.translate.TranslateScreen

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var onImageSelected: ((Uri) -> Unit)? = null
    private var onMultipleImagesSelected: ((List<Uri>) -> Unit)? = null
    private lateinit var mainViewModel: MainViewModel

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                Log.d(TAG, "Image selected: $uri")
                onImageSelected?.invoke(uri)
            }
        }

    private val pickMultipleMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                Log.d(TAG, "Multiple images selected: ${uris.size}")
                onMultipleImagesSelected?.invoke(uris)
            }
        }

    /**
     * ファイルピッカー: CSV / XLSX / PDF を選択
     */
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                Log.d(TAG, "File selected: $uri")
                // 永続的な読み取り権限を取得
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Could not take persistable permission: ${e.message}")
                }
                mainViewModel.onFileSelected(uri)
            }
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            for ((permission, granted) in grants) {
                Log.d(TAG, "Permission $permission: $granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // ShareReceiver からの結果を処理
        handleShareResult(intent)

        setContent {
            NexusVisionTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            viewModel = mainViewModel,
                            onPickImage = { launchImagePicker() },
                            onPickFile = { launchFilePicker() },
                            onPickMultipleImages = { launchMultipleImagePicker() },
                            onNavigateToTuner = { navController.navigate("tuner") },
                            onNavigateToTranslate = { navController.navigate("translate") },
                            onImageSelected = { callback ->
                                onImageSelected = callback
                            },
                            onMultipleImagesSelected = { callback ->
                                onMultipleImagesSelected = callback
                            }
                        )
                    }
                    composable("tuner") {
                        TunerScreen()
                    }
                    composable("translate") {
                        TranslateScreen()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // エンジンがReady/Degradedの場合のみ、通知からの質問を有効にする
        val engineState = NexusEngineManager.getInstance().state.value
        if (engineState is EngineState.Ready || engineState is EngineState.Degraded) {
            InlineReplyHandler.showReplyNotification(
                this,
                "NEXUS Vision — 通知から質問できます"
            )
            Log.d(TAG, "Inline reply notification shown (engine=$engineState)")
        }
    }

    override fun onStart() {
        super.onStart()
        // アプリに戻ったら通知を消す（チャットUIで直接操作するため）
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(InlineReplyHandler.NOTIFICATION_ID)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareResult(intent)
    }

    /**
     * ShareReceiver から渡された結果テキストをチャットに追加する
     */
    private fun handleShareResult(intent: Intent?) {
        val shareResult = intent?.getStringExtra(ShareReceiver.EXTRA_SHARE_RESULT)
        if (!shareResult.isNullOrBlank()) {
            mainViewModel.addShareResult(shareResult)
            // Extra を消費して再処理を防止
            intent.removeExtra(ShareReceiver.EXTRA_SHARE_RESULT)
        }
    }

    private fun launchImagePicker() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchMultipleImagePicker() {
        pickMultipleMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchFilePicker() {
        pickFile.launch(
            arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/pdf",
                "application/octet-stream"
            )
        )
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }
}
