// app/src/main/java/com/nexus/vision/retail/ocr/DeliveryOcrParser.kt
package com.nexus.vision.retail.ocr

import android.util.Log
import com.nexus.vision.ocr.OcrResult
import com.nexus.vision.ocr.TableReconstructor
import com.nexus.vision.retail.db.DeliveryRecord

/**
 * OCRテキスト → DeliveryRecord リスト変換
 *
 * 想定フォーマット (1行=1レコード、タブまたはスペース区切り):
 *   日付  JANコード  商品名  規格  数量  備考(省略可)
 *
 * 【パース戦略】
 *  Strategy A (TableReconstructor):
 *    OcrResult (座標付き) → TableReconstructor でセル構造を復元
 *    → ヘッダ行から列インデックスを自動検出して高精度マッピング
 *    → Strategy A の成功件数 >= 2 なら採用
 *
 *  Strategy B (rawText fallback):
 *    全テキストを行単位で正規表現パース (従来実装)
 */
object DeliveryOcrParser {

    private const val TAG = "DeliveryOcrParser"

    // JANコード判定: 8桁または13桁の数字
    private val JAN_REGEX = Regex("""^[0-9]{8}$|^[0-9]{13}$""")

    // ─────────────────────────────────────────────────────────
    // 公開API
    // ─────────────────────────────────────────────────────────

    sealed class ParseResult {
        data class Success(val record: DeliveryRecord) : ParseResult()
        data class Failed(val rawLine: String, val reason: String) : ParseResult()
        val isSuccess get() = this is Success
    }

    /**
     * rawText のみを使う従来API（Strategy B のみ）
     * 互換性維持のため残す
     */
    fun parse(
        rawText: String,
        project: String = "通常",
        fallbackYear: String = currentYear()
    ): List<ParseResult> = parseByRegex(rawText, project, fallbackYear)

    /**
     * OcrResult（座標付き）を使う高精度API
     * Strategy A → B の順でフォールバック
     *
     * DeliveryOcrActivity.runOcr() から呼ぶ推奨エントリポイント
     */
    fun parseWithTable(
        ocrResult: OcrResult,
        project: String = "通常"
    ): List<ParseResult> {

        // ── Strategy A: TableReconstructor ──────────────────
        val tableResult = runCatching {
            TableReconstructor.reconstruct(ocrResult)
        }.getOrElse { e ->
            Log.w(TAG, "TableReconstructor失敗: ${e.message}")
            null
        }

        if (tableResult != null && tableResult.isSuccess && tableResult.rows.isNotEmpty()) {
            Log.d(TAG, "TableResult: ${tableResult.rows.size}行 × " +
                    "${tableResult.rows.firstOrNull()?.size ?: 0}列")

            val results = parseFromTableResult(tableResult.rows, project)
            val successCount = results.filterIsInstance<ParseResult.Success>().size

            Log.d(TAG, "Strategy A: $successCount / ${results.size} 件成功")

            if (successCount >= 2) {
                return results
            }
            Log.d(TAG, "Strategy A 件数不足 → Strategy B にフォールバック")
        } else {
            Log.d(TAG, "TableReconstructor: テーブル構造なし → Strategy B")
        }

        // ── Strategy B: rawText fallback ───────────────────
        return parseByRegex(ocrResult.fullText, project, currentYear())
    }

    // ─────────────────────────────────────────────────────────
    // Strategy A: テーブル構造パース
    // ─────────────────────────────────────────────────────────

    /**
     * TableReconstructor の rows（List<List<String>>）から DeliveryRecord を生成
     *
     * ① 1行目がヘッダかどうか判定（数字が少ない＝ヘッダ行）
     * ② ヘッダありなら列インデックスを自動検出
     * ③ ヘッダなしなら JANコードの位置からインデックスを推測
     */
    private fun parseFromTableResult(
        rows: List<List<String>>,
        project: String
    ): List<ParseResult> {
        if (rows.isEmpty()) return emptyList()

        val firstRow = rows[0]
        val isHeader = isHeaderRow(firstRow)

        val colMap: ColMap
        val dataRows: List<List<String>>

        if (isHeader) {
            colMap   = detectColMapFromHeader(firstRow)
            dataRows = rows.drop(1)
            Log.d(TAG, "ヘッダ検出: $colMap")
        } else {
            colMap   = inferColMapFromData(rows)
            dataRows = rows
            Log.d(TAG, "ヘッダなし推測: $colMap")
        }

        return dataRows.mapIndexed { idx, row ->
            parseTableRow(row, colMap, project, idx + if (isHeader) 2 else 1)
        }
    }

    /**
     * 1行目がヘッダ行かどうかを判定
     * 判定基準：セルのうち数字のみのセルが全体の30%未満
     */
    private fun isHeaderRow(row: List<String>): Boolean {
        if (row.isEmpty()) return false
        val numericCount = row.count { it.trim().all { c -> c.isDigit() || c == '/' || c == '-' } }
        return numericCount.toDouble() / row.size < 0.3
    }

    /**
     * ヘッダ行から各列の役割を検出
     */
    private fun detectColMapFromHeader(header: List<String>): ColMap {
        var date = -1; var jan = -1; var name = -1
        var spec = -1; var qty  = -1; var note = -1

        header.forEachIndexed { i, cell ->
            val c = cell.trim()
            when {
                c.contains(Regex("日付|日 付|DATE|納品日"))          -> date = i
                c.contains(Regex("JAN|ＪＡＮ|jan|バーコード|コード")) -> jan  = i
                c.contains(Regex("商品名|品名|商 品"))               -> name = i
                c.contains(Regex("規格|サイズ|内容量"))              -> spec = i
                c.contains(Regex("数量|ケース|個数|本数|枚数|数"))   -> qty  = i
                c.contains(Regex("備考|メモ|NOTE"))                  -> note = i
            }
        }

        // JAN列が未検出 → ヘッダ自体がJANコード(8桁/13桁)の場合も考慮
        if (jan == -1) {
            header.forEachIndexed { i, cell ->
                if (JAN_REGEX.matches(cell.trim())) { jan = i; return@forEachIndexed }
            }
        }

        return ColMap(date, jan, name, spec, qty, note)
    }

    /**
     * ヘッダなし時：データ行からJAN列位置を特定し、他を推測
     *
     * 戦略：
     *  - 最初の数行で13桁数字が出現する列 → JAN
     *  - JAN列の隣で最も長い文字列が多い列 → 商品名
     *  - 短い数字（1〜4桁）が多い列 → 数量
     *  - 日付パターンが出る列 → 日付
     */
    private fun inferColMapFromData(rows: List<List<String>>): ColMap {
        val sampleRows = rows.take(5)
        val maxCols    = if (sampleRows.isNotEmpty()) sampleRows.maxOf { it.size } else 0

        var janCol  = -1
        var dateCol = -1
        var qtyCol  = -1
        var nameCol = -1

        if (maxCols == 0) return ColMap(-1, -1, -1, -1, -1, -1)

        // JAN列: 13桁数字が最も多く出る列
        val janScores = IntArray(maxCols)
        sampleRows.forEach { row ->
            row.forEachIndexed { i, cell ->
                if (i < maxCols && JAN_REGEX.matches(cell.trim())) janScores[i]++
            }
        }
        janCol = janScores.indices.maxByOrNull { janScores[it] }
            ?.takeIf { janScores[it] > 0 } ?: -1

        // 日付列: 日付パターンが出る列
        val dateRegex = Regex("""\d{4}[/\-]\d{1,2}[/\-]\d{1,2}|\d{1,2}[/\-]\d{1,2}""")
        val dateScores = IntArray(maxCols)
        sampleRows.forEach { row ->
            row.forEachIndexed { i, cell ->
                if (i < maxCols && dateRegex.containsMatchIn(cell.trim())) dateScores[i]++
            }
        }
        dateCol = dateScores.indices.maxByOrNull { dateScores[it] }
            ?.takeIf { dateScores[it] > 0 } ?: -1

        // 数量列: 1〜4桁の純粋な数字が多い列（JAN列・日付列を除く）
        val qtyScores = IntArray(maxCols)
        sampleRows.forEach { row ->
            row.forEachIndexed { i, cell ->
                if (i >= maxCols || i == janCol || i == dateCol) return@forEachIndexed
                val n = cell.trim()
                if (n.all { it.isDigit() } && n.length in 1..4) qtyScores[i]++
            }
        }
        qtyCol = qtyScores.indices.maxByOrNull { qtyScores[it] }
            ?.takeIf { qtyScores[it] > 0 } ?: -1

        // 商品名列: JAN・日付・数量でない列のうち最も文字が長い列
        val lenScores = IntArray(maxCols)
        sampleRows.forEach { row ->
            row.forEachIndexed { i, cell ->
                if (i >= maxCols || i == janCol || i == dateCol || i == qtyCol) return@forEachIndexed
                lenScores[i] += cell.length
            }
        }
        nameCol = lenScores.indices
            .filter { it != janCol && it != dateCol && it != qtyCol }
            .maxByOrNull { lenScores[it] }
            ?.takeIf { lenScores[it] > 0 } ?: -1

        // 規格列: nameCol+1（経験則）
        val specCol = if (nameCol >= 0 && nameCol + 1 < maxCols && nameCol + 1 != qtyCol) nameCol + 1 else -1

        return ColMap(dateCol, janCol, nameCol, specCol, qtyCol, note = -1)
    }

    /**
     * 1行をColMapに従ってDeliveryRecordに変換
     */
    private fun parseTableRow(
        row: List<String>,
        colMap: ColMap,
        project: String,
        rowNum: Int
    ): ParseResult {
        fun cell(idx: Int) = if (idx >= 0 && idx < row.size) row[idx].trim() else ""

        val rawJan = cell(colMap.jan).replace("-", "").replace(" ", "")
        if (!JAN_REGEX.matches(rawJan)) {
            return ParseResult.Failed(
                row.joinToString(" | "),
                "行$rowNum: JANコードなし/不正 (\"$rawJan\")"
            )
        }

        val fallbackYear = currentYear()
        val date = normalizeDate(cell(colMap.date), fallbackYear).let {
            if (it.isBlank()) todayStr() else it
        }

        val name = cell(colMap.name).ifBlank {
            // フォールバック: JAN・数量・日付以外で最も長いセル
            row.filterIndexed { i, _ ->
                i != colMap.jan && i != colMap.qty && i != colMap.date
            }.maxByOrNull { it.length }?.trim() ?: "不明"
        }

        val spec = cell(colMap.spec)

        val qtyStr = cell(colMap.qty).filter { it.isDigit() }
        val qty = qtyStr.toIntOrNull()
            ?: return ParseResult.Failed(
                row.joinToString(" | "),
                "行$rowNum: 数量パース失敗 (\"${cell(colMap.qty)}\")"
            )
        if (qty <= 0 || qty > 99999) {
            return ParseResult.Failed(
                row.joinToString(" | "),
                "行$rowNum: 数量範囲外 ($qty)"
            )
        }

        val note = cell(colMap.note)

        return ParseResult.Success(
            DeliveryRecord(
                projectName = project,
                date        = date,
                janCode     = rawJan,
                productName = name,
                spec        = spec,
                quantity    = qty,
                note        = note
            )
        )
    }

    // ─────────────────────────────────────────────────────────
    // Strategy B: rawText 正規表現パース（従来実装を整理）
    // ─────────────────────────────────────────────────────────

    private fun parseByRegex(
        rawText: String,
        project: String,
        fallbackYear: String = currentYear()
    ): List<ParseResult> =
        rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, project, fallbackYear) }

    fun parseLine(
        line: String,
        project: String = "通常",
        fallbackYear: String = currentYear()
    ): ParseResult? {
        val cols = when {
            line.contains('\t') ->
                line.split('\t').map { it.trim() }.filter { it.isNotBlank() }
            line.contains("  ") ->
                line.split(Regex("  +")).map { it.trim() }.filter { it.isNotBlank() }
            else ->
                line.split(' ').map { it.trim() }.filter { it.isNotBlank() }
        }

        // ── JAN + 数量の最低2列あればパース試行（列数チェック緩和）──
        if (cols.size < 2) return ParseResult.Failed(line, "列数不足(${cols.size})")

        // 全列からJANコードを探す
        val janIdx = cols.indexOfFirst { JAN_REGEX.matches(it.replace("-", "").replace(" ", "")) }
        if (janIdx < 0) return ParseResult.Failed(line, "JANコードなし")
        val rawJan = cols[janIdx].replace("-", "").replace(" ", "")

        // 日付: JANより前の列、またはYYYYMMDD形式の列
        val dateRaw = cols.take(janIdx).firstOrNull { normalizeDate(it).isNotBlank() }
            ?: cols.firstOrNull { c ->
                c.length == 8 && c.all { it.isDigit() }
            }
        val date = dateRaw?.let { normalizeDate(it, fallbackYear) }.orEmpty()
            .ifBlank { todayStr() }

        // 数量: JANより後の列で純粋な数字（1〜5桁）
        val qtyStr = cols.drop(janIdx + 1)
            .firstOrNull { it.all { c -> c.isDigit() } && it.length in 1..5 }
            ?: ""
        val quantity = qtyStr.toIntOrNull()
            ?: return ParseResult.Failed(line, "数量なし (JAN=$rawJan)")

        // 商品名: JANと数量の間で最も長い列
        val between = cols.drop(janIdx + 1).dropLast(
            maxOf(0, cols.size - cols.indexOf(qtyStr) - 1).coerceAtMost(cols.size)
        )
        val prodName = between.maxByOrNull { it.length }?.takeIf { it.isNotBlank() } ?: ""

        // 規格・備考: 残りの列
        val rest = cols.drop(janIdx + 1)
            .filter { it != qtyStr && it != prodName }
        val spec = rest.firstOrNull() ?: ""
        val note = rest.drop(1).joinToString(" ")

        return ParseResult.Success(
            DeliveryRecord(
                projectName = project,
                date        = date,
                janCode     = rawJan,
                productName = prodName,
                spec        = spec,
                quantity    = quantity,
                note        = note
            )
        )
    }

    // ─────────────────────────────────────────────────────────
    // 共通ユーティリティ
    // ─────────────────────────────────────────────────────────

    /** 日付を YYYY-MM-DD 形式に正規化 */
    fun normalizeDate(raw: String, fallbackYear: String = currentYear()): String {
        val cleaned = raw.trim()

        // 8桁連続数字: 20260501 形式
        if (cleaned.length == 8 && cleaned.all { it.isDigit() }) {
            val y = cleaned.substring(0, 4)
            val m = cleaned.substring(4, 6)
            val d = cleaned.substring(6, 8)
            return "$y-$m-$d"
        }

        // 6桁連続数字: 260501 (年2桁) 形式
        if (cleaned.length == 6 && cleaned.all { it.isDigit() }) {
            val y = "20" + cleaned.substring(0, 2)
            val m = cleaned.substring(2, 4)
            val d = cleaned.substring(4, 6)
            return "$y-$m-$d"
        }

        // 既存: スラッシュ・ハイフン区切り
        val parts = cleaned.split(Regex("[/\\-]")).mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3    -> "%04d-%02d-%02d".format(parts[0], parts[1], parts[2])
            2    -> "%s-%02d-%02d".format(fallbackYear, parts[0], parts[1])
            else -> ""
        }
    }

    private fun currentYear() =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()

    private fun todayStr(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /** 列インデックスマッピング。-1 = 未検出 */
    private data class ColMap(
        val date: Int,
        val jan:  Int,
        val name: Int,
        val spec: Int,
        val qty:  Int,
        val note: Int
    )
}
