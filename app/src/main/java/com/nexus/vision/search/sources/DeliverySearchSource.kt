package com.nexus.vision.search.sources

import android.content.Context
import com.nexus.vision.retail.db.RetailDatabase
import com.nexus.vision.retail.search.FuzzyNormalizer
import com.nexus.vision.search.IdentifiableSource
import com.nexus.vision.search.SearchResult
import com.nexus.vision.search.SearchSource
import com.nexus.vision.search.scoreOf

class DeliverySearchSource(private val context: Context) : SearchSource, IdentifiableSource {

    override val sourceId    = "delivery"
    override val displayName = "納品DB"
    override val description = "日付・JAN・商品名・メーカー"
    override val icon        = "🚚"

    override suspend fun search(
        query:    List<String>,
        rawQuery: String,
        limit:    Int
    ): List<SearchResult> {
        val dao = RetailDatabase.getInstance(context).deliveryDao()
        var results = dao.getAll()  // ← 全件取得してメモリ内フィルタ

        for (token in query) {
            val kana = FuzzyNormalizer.hiraganaToKatakana(token)
            results = results.filter { r ->
                listOf(
                    r.date, r.department, r.janCode,
                    r.maker, r.productName, r.spec,
                    r.quantity.toString(), r.note, r.projectName
                ).any { field ->
                    val n = FuzzyNormalizer.normalize(field)
                    n.contains(token) || n.contains(kana) ||
                    field.contains(token, ignoreCase = true)
                }
            }
        }

        return results.take(limit).map { r ->
            SearchResult(
                sourceId   = sourceId,
                sourceName = displayName,
                key        = r.id.toString(),
                fields     = linkedMapOf(
                    "日付"      to r.date,
                    "部門"      to r.department,
                    "JAN"      to r.janCode,
                    "メーカー"  to r.maker,
                    "商品名"    to r.productName,
                    "規格"      to r.spec,
                    "数量"      to r.quantity.toString(),
                    "備考"      to r.note
                ),
                score = scoreOf(r.productName + r.maker + r.janCode, query)
            )
        }
    }

    override suspend fun isAvailable() = true
}
