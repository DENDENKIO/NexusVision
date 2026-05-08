package com.nexus.vision.search

import android.content.Context
import com.nexus.vision.search.sources.DeliverySearchSource
import com.nexus.vision.search.sources.ProductSearchSource

/**
 * 全検索ソースを管理するレジストリ。
 * 新しいDBを追加するときは register() を1行追加するだけ。
 */
object SearchSourceRegistry {

    private val sources = mutableListOf<SearchSource>()

    /**
     * アプリ起動時に一度だけ呼ぶ（MainViewModel.init より）
     * 新DBを追加するたびにここに1行追加する
     */
    fun initAll(context: Context) {
        if (sources.isNotEmpty()) return  // 二重初期化防止
        register(DeliverySearchSource(context))
        register(ProductSearchSource(context))
        // 将来: register(StockSearchSource(context))
        // 将来: register(OrderSearchSource(context))
    }

    /** 検索ソースを手動登録 */
    fun register(source: SearchSource) {
        sources.removeAll { it.displayName == source.displayName }
        sources.add(source)
    }

    /** 全ソース取得 */
    fun getAll(): List<SearchSource> = sources.toList()

    /** sourceId で1件取得（IdentifiableSource のみ） */
    fun getById(id: String): SearchSource? =
        sources.filterIsInstance<IdentifiableSource>()
            .firstOrNull { it.sourceId == id } as? SearchSource

    /** 登録済みソース一覧（UI表示用） */
    fun allSourceNames(): List<Pair<String, String>> =
        sources.filterIsInstance<IdentifiableSource>()
            .map { it.sourceId to "${it.icon} ${it.displayName}" }
}
