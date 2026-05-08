package com.nexus.vision.search

/**
 * 汎用検索ソースインターフェース
 *
 * 新しいDBを追加する手順:
 *  1. このインターフェースを実装したクラスを作る
 *  2. SearchSourceRegistry.register() で登録するだけ
 */
interface SearchSource {

    /** 画面表示名 (例: "納品DB", "商品DB") */
    val displayName: String

    /** 検索対象の説明 (例: "日付・JAN・商品名・メーカー") */
    val description: String

    /** アイコン絵文字 */
    val icon: String get() = "🗄️"

    /**
     * 検索実行
     * @param query 正規化済みトークンリスト（FuzzyNormalizerで処理済み）
     * @param rawQuery 元の入力文字列
     * @param limit 最大件数（0=無制限）
     * @return SearchResult のリスト
     */
    suspend fun search(
        query:    List<String>,
        rawQuery: String,
        limit:    Int = 50
    ): List<SearchResult>

    /** このソースが有効か（DBが存在するか等） */
    suspend fun isAvailable(): Boolean = true
}

/**
 * 統一検索結果型
 *
 * どのDBからの結果も必ずこの型に変換して返す
 */
data class SearchResult(
    /** ソース識別子 (例: "delivery", "product") */
    val sourceId:   String,

    /** ソース表示名 */
    val sourceName: String,

    /** 一意キー (行クリック時の詳細表示用) */
    val key:        String,

    /**
     * 表示フィールド（順序付きMap）
     * key=列名, value=値
     * 例: mapOf("日付" to "2026-05-01", "商品名" to "コカ・コーラ")
     */
    val fields:     LinkedHashMap<String, String>,

    /** 関連度スコア（高いほど上位表示）*/
    val score:      Float = 1.0f,

    /** 詳細画面を開くIntent生成用（null=詳細なし）*/
    val detailAction: (() -> Unit)? = null
)
