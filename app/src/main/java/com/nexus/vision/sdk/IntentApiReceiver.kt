// ファイルパス: app/src/main/java/com/nexus/vision/sdk/IntentApiReceiver.kt

package com.nexus.vision.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 13 – Step 13-2: 署名検証付き Intent API
 *
 * 外部アプリから以下の Intent で呼び出し可能:
 *   Action: com.nexus.vision.api.ANALYZE
 *   Extras:
 *     - "text" (String): 質問テキスト
 *     - "command" (String): "classify" | "enhance" | "ask" | "analyze"
 *     - "callback_action" (String): 結果を返すための BroadcastReceiver Action
 *
 * 結果は callback_action の Intent に "result" extra として返される。
 *
 * セキュリティ: 呼び出し元アプリの署名を検証し、
 * 自己署名 (同一署名) のアプリのみ許可する。
 */
class IntentApiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IntentApiReceiver"
        const val ACTION_ANALYZE = "com.nexus.vision.api.ANALYZE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 許可する外部パッケージのリスト（空 = 同一署名のみ許可）
        private val ALLOWED_PACKAGES = setOf<String>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANALYZE) return

        val callerPackage = intent.`package`
            ?: intent.getStringExtra("caller_package")
            ?: "unknown"
        val text = intent.getStringExtra("text").orEmpty()
        val command = intent.getStringExtra("command") ?: "ask"
        val callbackAction = intent.getStringExtra("callback_action")

        Log.i(TAG, "API request: caller=$callerPackage, command=$command, textLen=${text.length}")

        // ── 署名検証 ──
        if (!verifyCallerSignature(context, callerPackage)) {
            Log.w(TAG, "Signature verification failed for $callerPackage")
            sendResult(context, callbackAction, "エラー: 署名検証に失敗しました。同一署名のアプリのみ利用可能です。")
            return
        }

        if (text.isBlank()) {
            sendResult(context, callbackAction, "エラー: text が空です。")
            return
        }

        // ── 非同期処理 ──
        val pendingResult = goAsync()

        scope.launch {
            try {
                val result = processCommand(command, text)
                sendResult(context, callbackAction, result)
            } catch (e: Exception) {
                Log.e(TAG, "API processing failed", e)
                sendResult(context, callbackAction, "エラー: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processCommand(command: String, text: String): String {
        val engineManager = NexusEngineManager.getInstance()

        // エンジン状態チェック
        val state = engineManager.state.value
        if (state !is EngineState.Ready && state !is EngineState.Degraded) {
            return "エラー: エンジンが未ロードです (state=$state)"
        }

        return when (command) {
            "ask", "analyze", "classify" -> {
                val result = engineManager.inferText(text)
                result.getOrElse { "エラー: ${it.message}" }
            }
            "enhance" -> {
                "エラー: enhance コマンドは Intent API 未対応です。アプリ内から画像を選択してください。"
            }
            else -> {
                "エラー: 不明なコマンド '$command'。使用可能: ask, analyze, classify"
            }
        }
    }

    private fun sendResult(context: Context, callbackAction: String?, result: String) {
        if (callbackAction.isNullOrBlank()) {
            Log.i(TAG, "No callback_action — result discarded (${result.take(50)})")
            return
        }

        val resultIntent = Intent(callbackAction).apply {
            putExtra("result", result)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        context.sendBroadcast(resultIntent)
        Log.i(TAG, "Result sent via $callbackAction (${result.length} chars)")
    }

    /**
     * 呼び出し元アプリの署名が自アプリと同一かを検証する。
     */
    private fun verifyCallerSignature(context: Context, callerPackage: String): Boolean {
        if (callerPackage == "unknown") return false
        if (callerPackage == context.packageName) return true
        if (ALLOWED_PACKAGES.contains(callerPackage)) return true

        return try {
            val pm = context.packageManager

            val mySignatures = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners ?: return false

            val callerSignatures = pm.getPackageInfo(
                callerPackage,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners ?: return false

            // 少なくとも1つの署名が一致すれば OK
            mySignatures.any { mySig ->
                callerSignatures.any { callerSig ->
                    mySig == callerSig
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $callerPackage")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error", e)
            false
        }
    }
}
