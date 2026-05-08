package com.nexus.vision.retail.search

/**
 * あいまい検索用テキスト正規化
 *
 * 対応:
 *  - カタカナ → ひらがな変換（逆も）
 *  - 全角英数 → 半角
 *  - 大文字 → 小文字
 *  - 長音「ー」「－」「‐」→「ー」統一
 *  - スペース・ハイフン正規化
 */
object FuzzyNormalizer {

    /** 検索クエリを正規化して複数トークンに分解 */
    fun tokenize(query: String): List<String> {
        val normalized = normalize(query)
        // スペース区切りでAND検索（空要素除去）
        return normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** テキストを正規化 */
    fun normalize(text: String): String = text
        .trim()
        .let { toHalfWidth(it) }           // 全角英数→半角
        .let { katakanaToHiragana(it) }     // カタカナ→ひらがな
        .replace(Regex("[ー－‐−-]"), "ー") // 長音統一
        .replace(Regex("[\\s　]+"), " ")    // 全角スペース→半角
        .lowercase()

    /** 全角英数・記号 → 半角 */
    private fun toHalfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(when (c) {
                in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar() // 全角英数記号
                '\u3000'             -> ' '                          // 全角スペース
                else                 -> c
            })
        }
        return sb.toString()
    }

    /** カタカナ → ひらがな */
    private fun katakanaToHiragana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar()  // ァ-ヶ → ぁ-ゖ
                else c
            )
        }
        return sb.toString()
    }

    /** ひらがな → カタカナ */
    fun hiraganaToKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                if (c in '\u3041'..'\u3096') (c.code + 0x60).toChar()  // ぁ-ゖ → ァ-ヶ
                else c
            )
        }
        return sb.toString()
    }
}
