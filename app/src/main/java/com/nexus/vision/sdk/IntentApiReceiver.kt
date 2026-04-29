package com.nexus.vision.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class IntentApiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IntentApiReceiver"
        const val ACTION_ANALYZE = "com.nexus.vision.api.ANALYZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANALYZE) return
        val caller = intent.`package` ?: "unknown"
        val text = intent.getStringExtra("text").orEmpty()
        Log.i(TAG, "Received API request from=$caller textLength=${text.length}")
        // Phase 13: 署名検証 + 非同期処理キューは次ステップで追加
    }
}
