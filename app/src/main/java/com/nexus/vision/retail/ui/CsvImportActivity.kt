package com.nexus.vision.retail.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexus.vision.retail.import.CsvImporter
import kotlinx.coroutines.launch

/**
 * CSVインポート画面
 *
 * 機能:
 *  - 「納品CSV」「商品CSV」タブ切り替え
 *  - ファイル選択 → プレビュー（先頭5行）
 *  - インポート実行 → 結果ダイアログ
 */
class CsvImportActivity : AppCompatActivity() {

    private lateinit var typeSwitchRow: LinearLayout
    private lateinit var deliveryBtn:   Button
    private lateinit var productBtn:    Button
    private lateinit var statusTv:      TextView
    private lateinit var previewTv:     TextView
    private lateinit var selectBtn:     Button
    private lateinit var importBtn:     Button

    private var selectedUri:  Uri? = null
    private var selectedType: CsvImporter.CsvType = CsvImporter.CsvType.DELIVERY

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // 永続的なUri権限を取得（アプリ再起動後も有効）
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedUri = it
            showPreview(it)
            importBtn.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        title = "📥 CSVインポート"
    }

    // ── UI構築 ────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        // タイプ選択ボタン
        typeSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        deliveryBtn = makeButton("📦 納品CSV", "#2255AA") { switchType(CsvImporter.CsvType.DELIVERY) }
        productBtn  = makeButton("🏷️ 商品CSV", "#444455") { switchType(CsvImporter.CsvType.PRODUCT) }
        typeSwitchRow.addView(deliveryBtn, lp(0, 44.dp(), 1f))
        typeSwitchRow.addView(productBtn,  lp(0, 44.dp(), 1f).apply { marginStart = 8.dp() })
        root.addView(typeSwitchRow, lp(-1, -2))

        // 説明テキスト
        statusTv = TextView(this).apply {
            text = formatHint(selectedType)
            setTextColor(Color.parseColor("#7777AA"))
            textSize = 11f
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        root.addView(statusTv, lp(-1, -2))

        // ファイル選択ボタン
        selectBtn = makeButton("📂 CSVファイルを選択", "#1A5A3A") {
            fileLauncher.launch(arrayOf("text/csv", "text/plain", "*/*"))
        }
        root.addView(selectBtn, lp(-1, 48.dp()).apply { bottomMargin = 8.dp() })

        // プレビュー（先頭5行）
        root.addView(TextView(this).apply {
            text = "プレビュー（先頭5行）"
            setTextColor(Color.parseColor("#AAAACC"))
            textSize = 11f
        }, lp(-1, -2))

        val previewScroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111120"))
        }
        previewTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCDD"))
            textSize = 10f
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            text = "ファイルを選択するとここに表示されます"
            typeface = android.graphics.Typeface.MONOSPACE
        }
        previewScroll.addView(previewTv)
        root.addView(previewScroll, lp(-1, 0, 1f))

        // インポートボタン
        importBtn = makeButton("📥 インポート実行", "#1A7A3A") { executeImport() }
        importBtn.visibility = View.GONE
        root.addView(importBtn, lp(-1, 52.dp()).apply { topMargin = 8.dp() })

        return root
    }

    // ── タイプ切り替え ────────────────────────────────────────

    private fun switchType(type: CsvImporter.CsvType) {
        selectedType = type
        selectedUri  = null
        importBtn.visibility = View.GONE
        previewTv.text = "ファイルを選択するとここに表示されます"
        statusTv.text  = formatHint(type)

        deliveryBtn.setBackgroundColor(Color.parseColor(
            if (type == CsvImporter.CsvType.DELIVERY) "#2255AA" else "#444455"))
        productBtn.setBackgroundColor(Color.parseColor(
            if (type == CsvImporter.CsvType.PRODUCT)  "#2255AA" else "#444455"))
    }

    // ── プレビュー表示 ────────────────────────────────────────

    private fun showPreview(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val encoding = detectEncoding(bytes)
            val lines = String(bytes, encoding).lines()

            val preview = lines.take(6).joinToString("\n")
            previewTv.text = preview
            statusTv.text  = "📄 ${lines.size}行（ヘッダ含む）を読み込みました"
        } catch (e: Exception) {
            previewTv.text = "❌ 読み込みエラー: ${e.message}"
        }
    }

    private fun detectEncoding(bytes: ByteArray): java.nio.charset.Charset {
        return if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()) Charsets.UTF_8
        else {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains('\uFFFD')) java.nio.charset.Charset.forName("Shift_JIS") else Charsets.UTF_8
        }
    }

    // ── インポート実行 ────────────────────────────────────────

    private fun executeImport() {
        val uri = selectedUri ?: return
        importBtn.isEnabled = false
        statusTv.text = "⏳ インポート中..."

        lifecycleScope.launch {
            val result = CsvImporter.import(this@CsvImportActivity, uri, selectedType)

            importBtn.isEnabled = true
            statusTv.text = result.summary

            val detail = buildString {
                appendLine(result.summary)
                if (result.errors.isNotEmpty()) {
                    appendLine()
                    appendLine("── エラー詳細 ──")
                    result.errors.take(20).forEach { appendLine(it) }
                    if (result.errors.size > 20)
                        appendLine("... 他${result.errors.size - 20}件")
                }
            }

            AlertDialog.Builder(this@CsvImportActivity)
                .setTitle("📥 インポート完了")
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .setNegativeButton("続けてインポート") { _, _ ->
                    selectedUri = null
                    importBtn.visibility = View.GONE
                    previewTv.text = "ファイルを選択するとここに表示されます"
                }
                .show()
        }
    }

    // ── ユーティリティ ────────────────────────────────────────

    private fun formatHint(type: CsvImporter.CsvType) = when (type) {
        CsvImporter.CsvType.DELIVERY ->
            "納品CSV形式:\n企画名, 納品日, 部門, JANコード, メーカー, 商品名, 規格, 数量, 備考\n重複判定: 納品日 + JANコード + 数量"
        CsvImporter.CsvType.PRODUCT  ->
            "商品CSV形式:\nJANコード, 商品名, 規格, 最終納品日\n重複判定: JANコードのみ"
    }

    private fun makeButton(label: String, color: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            setOnClickListener { onClick() }
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)
}
