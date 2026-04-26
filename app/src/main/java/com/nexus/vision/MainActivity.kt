// ファイルパス: app/src/main/java/com/nexus/vision/MainActivity.kt

package com.nexus.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nexus.vision.ui.MainScreen
import com.nexus.vision.ui.theme.NexusVisionTheme

/**
 * NEXUS Vision メインエントリーポイント
 *
 * Phase 4 修正: onPickImage をラムダで直接渡す。
 *   ViewModel は MainScreen 内で viewModel() で生成させ、
 *   画像選択の結果だけ外から注入する。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Phase 4: 画像選択ランチャー（コールバックは後で設定）
    private var onImageSelected: ((android.net.Uri) -> Unit)? = null

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                Log.d(TAG, "Image selected: $uri")
                onImageSelected?.invoke(uri)
            }
        }

    // Phase 4: 権限リクエストランチャー
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

        setContent {
            NexusVisionTheme {
                MainScreen(
                    onPickImage = { launchImagePicker() },
                    onImageSelected = { callback ->
                        onImageSelected = callback
                    }
                )
            }
        }
    }

    private fun launchImagePicker() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
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