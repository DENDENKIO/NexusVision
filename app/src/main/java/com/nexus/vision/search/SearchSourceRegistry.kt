package com.nexus.vision.search

import android.content.Context
import com.nexus.vision.search.sources.DeliverySearchSource
import com.nexus.vision.search.sources.ProductSearchSource

/**
 * 全検索ソースの登録管理
 *
 * 新DBを追加する時は register() を1行呼ぶだけ
 */
object SearchSourceRegistry {

    private val sources = mutableListOf<SearchSource>()

    /** 検索ソースを登録 */
    fun register(source: SearchSource) {
        if (sources.none { it.displayName == source.displayName }) {
            sources.add(source)
        }
    }

    /** 全登録ソースを取得 */
    fun getAll(): List<SearchSource> = sources.toList()

    /** ID指定で取得 */
    fun getById(id: String): SearchSource? =
        sources.filterIsInstance<IdentifiableSource>()
            .firstOrNull { it.sourceId == id }

    /** アプリ起動時に全ソースを初期化 */
    fun initAll(context: Context) {
        register(DeliverySearchSource(context))
        register(ProductSearchSource(context))
        // 将来: register(StockSearchSource(context))
        // 将来: register(OrderSearchSource(context))
        // 将来: register(CsvFileSearchSource(context, uri))
    }
}

/** IDを持つSearchSourceのマーカーインターフェース */
interface IdentifiableSource : SearchSource {
    val sourceId: String
}
