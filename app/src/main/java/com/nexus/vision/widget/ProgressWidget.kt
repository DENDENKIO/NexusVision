// ファイルパス: app/src/main/java/com/nexus/vision/widget/ProgressWidget.kt

package com.nexus.vision.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.LinearProgressIndicator
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Phase 12 – Step 12-1: Jetpack Glance ウィジェット
 *
 * バッチ高画質化の進捗をホーム画面から確認可能。
 * BatchEnhanceQueue.progress を監視して表示を更新。
 */
class ProgressWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "ProgressWidget"

        // 静的にキャッシュ（Glance は provideContent ごとに新規インスタンス）
        @Volatile
        var cachedProgress: Float = 0f

        @Volatile
        var cachedStatusText: String = "待機中"

        @Volatile
        var cachedIsRunning: Boolean = false

        /**
         * 外部から進捗を更新し、ウィジェットをリフレッシュする。
         */
        fun updateProgress(context: Context, progress: Float, statusText: String, isRunning: Boolean) {
            cachedProgress = progress.coerceIn(0f, 1f)
            cachedStatusText = statusText
            cachedIsRunning = isRunning
            MainScope().launch {
                try {
                    ProgressWidget().updateAll(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Widget update failed: ${e.message}")
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent(
                    progress = cachedProgress,
                    statusText = cachedStatusText,
                    isRunning = cachedIsRunning
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(
        progress: Float,
        statusText: String,
        isRunning: Boolean
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(GlanceTheme.colors.background),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEXUS Vision",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = GlanceTheme.colors.onBackground
                    )
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = if (isRunning) "処理中" else "完了",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = GlanceTheme.colors.secondary
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            if (isRunning) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
            }

            Text(
                text = statusText,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = GlanceTheme.colors.onBackground
                ),
                maxLines = 2
            )
        }
    }
}

/**
 * GlanceAppWidgetReceiver — システムがウィジェット更新時に呼び出す。
 */
class ProgressWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProgressWidget()
}
