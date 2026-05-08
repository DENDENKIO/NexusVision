package com.nexus.vision.search.sources

import android.content.Context
import com.nexus.vision.retail.db.RetailDatabase
import com.nexus.vision.retail.search.FuzzyNormalizer
import com.nexus.vision.search.*

class ProductSearchSource(private val context: Context)
    : SearchSource, IdentifiableSource {

    override val sourceId    = "product"
    override val displayName = "商品DB"
    override val description = "JAN・商品名・メーカー・規格"
    override val icon        = "🏷️"

    override suspend fun search(
        query:    List<String>,
        rawQuery: String,
        limit:    Int
    ): List<SearchResult> {
        val dao = RetailDatabase.getInstance(context).productDao()
        var results = dao.getAll()

        for (token in query) {
            val kana = FuzzyNormalizer.hiraganaToKatakana(token)
            results = results.filter { p ->
                listOf(p.janCode, p.productName, p.maker, p.spec)
                    .any { field ->
                        val n = FuzzyNormalizer.normalize(field)
                        n.contains(token) || n.contains(kana) ||
                        field.contains(token, ignoreCase = true)
                    }
            }
        }

        return results.take(limit).map { p ->
            SearchResult(
                sourceId   = sourceId,
                sourceName = displayName,
                key        = p.janCode,
                fields     = linkedMapOf(
                    "JAN"   to p.janCode,
                    "商品名" to p.productName,
                    "メーカー" to p.maker,
                    "規格"   to p.spec
                ),
                score = scoreOf(p.productName + p.maker, query)
            )
        }
    }

    override suspend fun isAvailable() = true
}
