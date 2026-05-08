// app/src/main/java/com/nexus/vision/retail/ocr/DocumentScannerHelper.kt
package com.nexus.vision.retail.ocr

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * ML Kit Document Scanner ラッパー
 *
 * 機能:
 *  - 紙の自動検出・トリミング
 *  - 台形補正（透視変換）
 *  - 傾き補正
 *  - 照明ムラ補正（SCANNER_MODE_FULL）
 *
 * 使い方:
 *  1. onCreate() で init() を呼ぶ
 *  2. startScan() でスキャン起動
 *  3. onScanResult() コールバックでUri受け取り
 */
class DocumentScannerHelper(
    private val activity: Activity,
    private val onScanned: (List<Uri>) -> Unit,
    private val onError:   (Exception) -> Unit = { Log.e("DocScanner", it.message, it) }
) {

    companion object {
        private const val TAG = "DocumentScannerHelper"
    }

    private var scanner: GmsDocumentScanner? = null

    // ActivityResultLauncher は Activity.registerForActivityResult で登録
    // → DeliveryOcrActivity 側で以下のように登録して渡す
    //
    //   val scanLauncher = registerForActivityResult(
    //       ActivityResultContracts.StartIntentSenderForResult()
    //   ) { result -> docScanHelper.handleResult(result.resultCode, result.data) }

    /**
     * スキャナーを初期化（onCreate で呼ぶ）
     *
     * @param maxPages  スキャン最大ページ数（納品伝票は1枚想定）
     * @param fullMode  true=照明補正+高品質（やや遅い）/ false=高速モード
     */
    fun init(maxPages: Int = 1, fullMode: Boolean = true) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)   // スキャナー内蔵のギャラリーは使わない
            .setPageLimit(maxPages)
            .setResultFormats(RESULT_FORMAT_JPEG)  // JPEG で受け取る
            .setScannerMode(
                if (fullMode) SCANNER_MODE_FULL else SCANNER_MODE_BASE
            )
            .build()

        scanner = GmsDocumentScanning.getClient(options)
        Log.d(TAG, "DocumentScanner 初期化完了 fullMode=$fullMode")
    }

    /**
     * スキャン開始
     *
     * @param launcher registerForActivityResult で作ったランチャー
     */
    fun startScan(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val sc = scanner ?: run {
            onError(IllegalStateException("init() を先に呼んでください"))
            return
        }

        sc.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "スキャン起動失敗", e)
                onError(e)
            }
    }

    /**
     * ActivityResult を処理してスキャン結果Uriを返す
     * startActivityForResult のコールバックから呼ぶ
     */
    fun handleResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            Log.d(TAG, "スキャンキャンセル")
            return
        }

        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        val pages  = result?.pages

        if (pages.isNullOrEmpty()) {
            onError(Exception("スキャン結果が空です"))
            return
        }

        val uris = pages.mapNotNull { it.imageUri }
        Log.i(TAG, "スキャン完了: ${uris.size}ページ")
        onScanned(uris)
    }
}
