package com.nexus.vision.retail.import

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nexus.vision.retail.db.RetailDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CSVインポート
 *
 * 対応フォーマット:
 *  【納品CSV】ヘッダ行必須
 *    ID,企画名,納品日,部門,JANコード,メーカー,商品名,規格,数量,備考,登録日時
 *    ※ ID・登録日時列は無視（自動生成）
 *    ※ ヘッダ名は部分一致で列を自動検出
 *
 *  【商品CSV】ヘッダ行必須
 *    JANコード,商品名,規格,登録日時,最終納品日
 *    ※ 登録日時・最終納品日は省略可
 *
 * 重複判定:
 *  納品: 日付 + JANコード + 数量 が一致 → 上書き
 *  商品: JANコード が一致 → 上書き
 */
object CsvImporter {

    private const val TAG = "CsvImporter"

    // ─────────────────────────────────────────────────────────
    // 公開型
    // ─────────────────────────────────────────────────────────

    enum class CsvType { DELIVERY, PRODUCT }

    data class ImportResult(
        val type:      CsvType,
        val inserted:  Int,
        val updated:   Int,
        val skipped:   Int,
        val errors:    List<String>   // エラー行の説明
    ) {
        val total get() = inserted + updated + skipped
        val summary get() = buildString {
            append("✅ 新規:${inserted}件 / 🔄 更新:${updated}件")
            if (skipped > 0) append(" / ⚠️ スキップ:${skipped}件")
            if (errors.isNotEmpty()) append("\n❌ エラー:${errors.size}行")
        }
    }

    // ─────────────────────────────────────────────────────────
    // 公開API
    // ─────────────────────────────────────────────────────────

    /**
     * CSVファイルをインポートする
     * @param context   Context
     * @param uri       ファイルUri（ACTION_OPEN_DOCUMENT等で取得）
     * @param type      DELIVERY or PRODUCT
     * @return ImportResult
     */
    suspend fun import(
        context: Context,
        uri:     Uri,
        type:    CsvType
    ): ImportResult = withContext(Dispatchers.IO) {

        val lines = readLines(context, uri)
        if (lines.size < 2) {
            return@withContext ImportResult(type, 0, 0, 0,
                listOf("ファイルが空またはヘッダのみです"))
        }

        val header = parseCsvRow(lines[0])
        val dataLines = lines.drop(1).filter { it.isNotBlank() }

        Log.d(TAG, "インポート開始: type=$type, ヘッダ=$header, データ${dataLines.size}行")

        return@withContext when (type) {
            CsvType.DELIVERY -> importDelivery(context, header, dataLines)
            CsvType.PRODUCT  -> importProduct(context, header, dataLines)
        }
    }

    // ─────────────────────────────────────────────────────────
    // 納品CSV インポート
    // ─────────────────────────────────────────────────────────

    private suspend fun importDelivery(
        context:   Context,
        header:    List<String>,
        dataLines: List<String>
    ): ImportResult {
        val dao = RetailDatabase.getInstance(context).deliveryDao()

        // ヘッダから列インデックスを検出
        val iProject = findCol(header, "企画名", "プロジェクト")
        val iDate    = findCol(header, "納品日", "日付", "DATE")
        val iDept    = findCol(header, "部門", "DEPT")
        val iJan     = findCol(header, "JAN", "JANコード", "jan", "バーコード")
        val iMaker   = findCol(header, "メーカー", "MAKER", "製造")
        val iName    = findCol(header, "商品名", "品名")
        val iSpec    = findCol(header, "規格", "サイズ")
        val iQty     = findCol(header, "数量", "ケース", "個数")
        val iNote    = findCol(header, "備考", "メモ", "NOTE")

        if (iJan < 0 || iDate < 0 || iQty < 0) {
            return ImportResult(CsvType.DELIVERY, 0, 0, 0,
                listOf("必須列（納品日/JANコード/数量）がヘッダに見つかりません: $header"))
        }

        var inserted = 0; var updated = 0; var skipped = 0
        val errors   = mutableListOf<String>()

        dataLines.forEachIndexed { lineIdx, line ->
            val row = parseCsvRow(line)

            runCatching {
                val date    = row.getOrElse(iDate)    { "" }.trim()
                val jan     = row.getOrElse(iJan)     { "" }.trim()
                    .replace("-","").replace(" ","")
                val qtyStr  = row.getOrElse(iQty)     { "" }.trim()

                // 最低限のバリデーション
                if (date.isBlank() || jan.isBlank() || qtyStr.isBlank()) {
                    skipped++
                    return@forEachIndexed
                }
                val qty = qtyStr.filter { it.isDigit() }.toIntOrNull()
                    ?: run { errors.add("行${lineIdx+2}: 数量不正 \"$qtyStr\""); return@forEachIndexed }

                val existing = dao.findByKey(date, jan, qty)
                val now      = System.currentTimeMillis()

                dao.upsert(
                    projectName = row.getOrElse(iProject) { "通常" }.trim().ifBlank { "通常" },
                    date        = date,
                    department  = if (iDept  >= 0) row.getOrElse(iDept)  { "" }.trim() else "",
                    janCode     = jan,
                    maker       = if (iMaker >= 0) row.getOrElse(iMaker) { "" }.trim() else "",
                    productName = if (iName  >= 0) row.getOrElse(iName)  { "" }.trim() else "",
                    spec        = if (iSpec  >= 0) row.getOrElse(iSpec)  { "" }.trim() else "",
                    quantity    = qty,
                    note        = if (iNote  >= 0) row.getOrElse(iNote)  { "" }.trim() else "",
                    createdAt   = existing?.createdAt ?: now
                )

                if (existing != null) updated++ else inserted++

            }.onFailure { e ->
                errors.add("行${lineIdx+2}: ${e.message}")
                Log.w(TAG, "行${lineIdx+2}エラー: $line", e)
            }
        }

        Log.i(TAG, "納品CSV完了: inserted=$inserted updated=$updated skipped=$skipped errors=${errors.size}")
        return ImportResult(CsvType.DELIVERY, inserted, updated, skipped, errors)
    }

    // ─────────────────────────────────────────────────────────
    // 商品CSV インポート
    // ─────────────────────────────────────────────────────────

    private suspend fun importProduct(
        context:   Context,
        header:    List<String>,
        dataLines: List<String>
    ): ImportResult {
        val dao = RetailDatabase.getInstance(context).productDao()

        val iJan      = findCol(header, "JAN", "JANコード", "jan", "バーコード")
        val iName     = findCol(header, "商品名", "品名")
        val iMaker    = findCol(header, "メーカー", "MAKER", "製造")
        val iSpec     = findCol(header, "規格", "サイズ")

        if (iJan < 0) {
            return ImportResult(CsvType.PRODUCT, 0, 0, 0,
                listOf("必須列（JANコード）がヘッダに見つかりません: $header"))
        }

        var inserted = 0; var updated = 0; var skipped = 0
        val errors   = mutableListOf<String>()

        dataLines.forEachIndexed { lineIdx, line ->
            val row = parseCsvRow(line)

            runCatching {
                val jan = row.getOrElse(iJan) { "" }.trim()
                    .replace("-","").replace(" ","")

                if (jan.isBlank()) { skipped++; return@forEachIndexed }

                // 既存レコードを確認（新規/更新の判断と registeredAt 維持）
                val existing = dao.findByJan(jan)
                val now      = System.currentTimeMillis()

                dao.upsert(
                    janCode          = jan,
                    productName      = if (iName     >= 0) row.getOrElse(iName)     { "" }.trim() else "",
                    maker            = if (iMaker    >= 0) row.getOrElse(iMaker)    { "" }.trim() else "",
                    spec             = if (iSpec     >= 0) row.getOrElse(iSpec)     { "" }.trim() else "",
                    registeredAt     = existing?.registeredAt ?: now
                )

                if (existing != null) updated++ else inserted++

            }.onFailure { e ->
                errors.add("行${lineIdx+2}: ${e.message}")
            }
        }

        Log.i(TAG, "商品CSV完了: inserted=$inserted updated=$updated skipped=$skipped")
        return ImportResult(CsvType.PRODUCT, inserted, updated, skipped, errors)
    }

    // ─────────────────────────────────────────────────────────
    // CSV解析ユーティリティ
    // ─────────────────────────────────────────────────────────

    /**
     * RFC 4180 準拠のCSV1行をパース
     * - ダブルクォートで囲まれたフィールドに対応
     * - "" → " エスケープ対応
     * - UTF-8 BOM（\uFEFF）を除去
     */
    fun parseCsvRow(line: String): List<String> {
        val cleaned = line.trimStart('\uFEFF')
        val result  = mutableListOf<String>()
        val sb      = StringBuilder()
        var inQuote = false
        var i = 0

        while (i < cleaned.length) {
            val c = cleaned[i]
            when {
                inQuote && c == '"' && i + 1 < cleaned.length && cleaned[i + 1] == '"' -> {
                    sb.append('"'); i += 2   // "" → "
                }
                c == '"' -> { inQuote = !inQuote; i++ }
                c == ',' && !inQuote -> { result.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        result.add(sb.toString())
        return result
    }

    /**
     * ヘッダ行から列インデックスを検出
     * 複数の候補ワードを部分一致で検索
     */
    private fun findCol(header: List<String>, vararg candidates: String): Int =
        header.indexOfFirst { cell ->
            candidates.any { c -> cell.contains(c, ignoreCase = true) }
        }

    /**
     * UriからUTF-8/Shift_JIS両対応でテキスト行を読み込む
     */
    private fun readLines(context: Context, uri: Uri): List<String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return emptyList()

        // UTF-8 BOMがあればUTF-8、なければShift_JISも試みる
        return try {
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()) {
                String(bytes, Charsets.UTF_8)
            } else {
                // UTF-8で試して文字化けなければUTF-8、あればSJIS
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.contains('\uFFFD')) String(bytes, charset("Shift_JIS"))
                else utf8
            }
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }.lines()
    }
}
