// app/src/main/java/com/nexus/vision/retail/export/DeliveryExporter.kt
package com.nexus.vision.retail.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.nexus.vision.retail.db.DeliveryRecord
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 納品レコード → CSV エクスポート
 *
 * 出力先: Context.cacheDir/exports/delivery_YYYYMMDD_HHmmss.csv
 * 文字コード: UTF-8 BOM付き（Excelで開けるよう）
 * FileProvider authorities: ${packageName}.retail.provider
 *
 * 使い方:
 *   val uri = DeliveryExporter.export(context, records)
 *   DeliveryExporter.share(context, uri)      // 共有
 *   DeliveryExporter.openChooser(context, uri) // 保存先選択
 */
object DeliveryExporter {

    private const val TAG = "DeliveryExporter"

    /** CSV カラム定義 */
    private val HEADERS = listOf(
        "ID", "企画名", "納品日", "JANコード",
        "商品名", "規格", "数量", "備考", "登録日時"
    )

    // ─────────────────────────────────────────────────────────
    // 公開API
    // ─────────────────────────────────────────────────────────

    /**
     * DeliveryRecord リストをCSVファイルに書き出してUriを返す
     *
     * @param context   Context
     * @param records   エクスポートするレコード
     * @param fileName  ファイル名（省略時は日時自動生成）
     * @return FileProvider Uri（他アプリに共有可能）
     */
    fun export(
        context: Context,
        records: List<DeliveryRecord>,
        fileName: String = generateFileName()
    ): Uri {
        val dir  = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(dir, fileName)

        FileWriter(file, Charsets.UTF_8).use { writer ->
            // UTF-8 BOM (Excel対応)
            writer.write("\uFEFF")

            // ヘッダ行
            writer.write(toCsvRow(HEADERS))
            writer.write("\n")

            // データ行
            val dtFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            records.forEach { r ->
                writer.write(
                    toCsvRow(
                        listOf(
                            r.id.toString(),
                            r.projectName,
                            r.date,
                            r.janCode,
                            r.productName,
                            r.spec,
                            r.quantity.toString(),
                            r.note,
                            dtFmt.format(Date(r.createdAt))
                        )
                    )
                )
                writer.write("\n")
            }
        }

        Log.i(TAG, "CSV出力: ${file.absolutePath} (${records.size}件)")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.retail.provider",
            file
        )
    }

    /**
     * CSVファイルを他アプリに共有（送信ボタン）
     */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type     = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "納品データ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "CSVを共有"))
    }

    /**
     * ダウンロードフォルダ等への保存（ACTION_CREATE_DOCUMENT 用Intent）
     * Activity の startActivityForResult で呼ぶ
     */
    fun createSaveIntent(suggestedName: String = generateFileName()): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type     = "text/csv"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    /**
     * ACTION_CREATE_DOCUMENT のコールバックで受け取った Uri にCSVを書き込む
     * （ダウンロードフォルダ保存フロー）
     */
    fun writeTo(context: Context, destUri: Uri, records: List<DeliveryRecord>) {
        context.contentResolver.openOutputStream(destUri)?.use { os ->
            val writer = os.bufferedWriter(Charsets.UTF_8)
            writer.write("\uFEFF")
            writer.write(toCsvRow(HEADERS) + "\n")
            val dtFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            records.forEach { r ->
                writer.write(
                    toCsvRow(
                        listOf(
                            r.id.toString(), r.projectName, r.date, r.janCode,
                            r.productName, r.spec, r.quantity.toString(),
                            r.note, dtFmt.format(Date(r.createdAt))
                        )
                    ) + "\n"
                )
            }
            writer.flush()
            Log.i(TAG, "writeTo: ${records.size}件 → $destUri")
        }
    }

    // ─────────────────────────────────────────────────────────
    // 内部ユーティリティ
    // ─────────────────────────────────────────────────────────

    /**
     * RFC 4180 準拠のCSV行生成
     * - ダブルクォート、カンマ、改行を含むセルはクォートで囲む
     */
    private fun toCsvRow(cells: List<String>): String =
        cells.joinToString(",") { cell ->
            val needsQuote = cell.contains(',') || cell.contains('"') ||
                             cell.contains('\n') || cell.contains('\r')
            if (needsQuote) "\"${cell.replace("\"", "\"\"")}\"" else cell
        }

    private fun generateFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
        return "delivery_$ts.csv"
    }
}
