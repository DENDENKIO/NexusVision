package com.nexus.vision.retail.ocr

import com.nexus.vision.retail.db.DeliveryRecord

/**
 * OCRテキスト → DeliveryRecord リスト変換
 *
 * 想定フォーマット (1行=1レコード、タブまたはスペース区切り):
 *   日付  JANコード  商品名  規格  数量  備考(省略可)
 *
 * 例:
 *   2026/04/26  4902102142014  トマトジュース  500ml×24  60  本部送り込み
 *   2026-04-26  4902102142014  トマトジュース  500ml×24  60
 */
object DeliveryOcrParser {

    // JANコード判定: 8桁または13桁の数字
    private val JAN_REGEX = Regex("""^[0-9]{8}$|^[0-9]{13}$""")

    // 日付判定: YYYY/MM/DD または YYYY-MM-DD または MM/DD または M/D
    private val DATE_REGEX = Regex(
        """(\d{4}[/\-]\d{1,2}[/\-]\d{1,2})|(\d{1,2}[/\-]\d{1,2})"""
    )

    /**
     * OCRで取得した生テキスト全体をパース
     *
     * @param rawText  ML Kit等から得たテキスト
     * @param project  企画名 (呼び出し元で指定)
     * @param fallbackYear 年省略時に補完する年 (例: "2026")
     * @return パース成功したレコードのリスト
     */
    fun parse(
        rawText: String,
        project: String = "通常",
        fallbackYear: String = java.util.Calendar.getInstance()
            .get(java.util.Calendar.YEAR).toString()
    ): List<ParseResult> {

        return rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, project, fallbackYear) }
    }

    /**
     * 1行をパース
     * カラム順: 日付 | JAN | 商品名 | 規格 | 数量 | 備考(省略可)
     */
    fun parseLine(
        line: String,
        project: String = "通常",
        fallbackYear: String = "2026"
    ): ParseResult? {

        // 区切り文字: タブ優先、なければ2個以上スペース、それもなければ単スペース
        val cols = when {
            line.contains('\t') ->
                line.split('\t').map { it.trim() }.filter { it.isNotBlank() }
            line.contains("  ") ->
                line.split(Regex("  +")).map { it.trim() }.filter { it.isNotBlank() }
            else ->
                line.split(' ').map { it.trim() }.filter { it.isNotBlank() }
        }

        if (cols.size < 5) return ParseResult.Failed(line, "列数不足(${cols.size})")

        val rawDate  = cols[0]
        val rawJan   = cols[1].replace("-", "").replace(" ", "")
        val prodName = cols[2]
        val spec     = cols[3]
        val rawQty   = cols[4]
        val note     = if (cols.size >= 6) cols.drop(5).joinToString(" ") else ""

        // 日付正規化
        val date = normalizeDate(rawDate, fallbackYear)
            ?: return ParseResult.Failed(line, "日付不正: $rawDate")

        // JANコード検証
        if (!JAN_REGEX.matches(rawJan))
            return ParseResult.Failed(line, "JANコード不正: $rawJan")

        // 数量
        val quantity = rawQty.filter { it.isDigit() }.toIntOrNull()
            ?: return ParseResult.Failed(line, "数量不正: $rawQty")

        val record = DeliveryRecord(
            projectName = project,
            date        = date,
            janCode     = rawJan,
            productName = prodName,
            spec        = spec,
            quantity    = quantity,
            note        = note
        )
        return ParseResult.Success(record)
    }

    /**
     * 日付を YYYY-MM-DD 形式に正規化
     * 入力例: "4/26", "04/26", "2026/04/26", "2026-4-26"
     */
    fun normalizeDate(raw: String, fallbackYear: String): String? {
        val cleaned = raw.trim()
        val parts   = cleaned.split(Regex("[/\\-]")).mapNotNull { it.toIntOrNull() }

        return when (parts.size) {
            3 -> "%04d-%02d-%02d".format(parts[0], parts[1], parts[2])
            2 -> "%s-%02d-%02d".format(fallbackYear, parts[0], parts[1])
            else -> null
        }
    }

    /** パース結果 */
    sealed class ParseResult {
        data class Success(val record: DeliveryRecord) : ParseResult()
        data class Failed(val rawLine: String, val reason: String) : ParseResult()

        val isSuccess get() = this is Success
    }
}
