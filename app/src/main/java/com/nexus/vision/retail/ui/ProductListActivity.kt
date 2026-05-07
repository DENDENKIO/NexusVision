package com.nexus.vision.retail.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexus.vision.retail.db.*
import com.nexus.vision.retail.repository.RetailRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * 商品データベース画面
 *
 * - 全商品一覧 (RecyclerView)
 * - JANコード / 商品名 / 規格 でリアルタイム検索
 * - 行タップ → 詳細ダイアログ (その商品の納品履歴も表示)
 * - 長押し → 編集 / 削除
 */
class ProductListActivity : AppCompatActivity() {

    private lateinit var repo:    RetailRepository
    private lateinit var adapter: ProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo    = RetailRepository(RetailDatabase.getInstance(this))
        adapter = ProductAdapter(
            onTap    = { showDetailDialog(it) },
            onEdit   = { showEditDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        setContentView(buildLayout())
        title = "🏷️ 商品データベース"
        loadAll()
    }

    // ── UI構築 ────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D14"))
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }

        // 検索バー
        val searchBar = EditText(this).apply {
            hint = "JANコード / 商品名 / 規格 で検索"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555566"))
            setBackgroundColor(Color.parseColor("#1C1C2E"))
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { search(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(searchBar, lp(-1, 48.dp()))

        // 件数表示
        val countTv = TextView(this).apply {
            setTextColor(Color.parseColor("#6677AA")); textSize = 11f
            setPadding(4.dp(), 4.dp(), 0, 4.dp())
            tag = "count"
        }
        root.addView(countTv, lp(-1, -2))

        // テーブルヘッダー
        root.addView(buildHeader(), lp(-1, -2))

        // 一覧
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ProductListActivity)
            adapter = this@ProductListActivity.adapter
        }
        root.addView(rv, lp(-1, 0, 1f))

        return root
    }

    private fun buildHeader(): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A3A"))
            setPadding((4*dp).toInt(), (6*dp).toInt(), (4*dp).toInt(), (6*dp).toInt())
            listOf("JANコード" to 1.8f, "商品名" to 2.2f,
                   "規格" to 1.2f, "初回登録日" to 1.2f).forEach { (n, w) ->
                addView(TextView(context).apply {
                    text = n; textSize = 11f
                    setTextColor(Color.parseColor("#AACCFF"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, w)
                })
            }
        }
    }

    // ── データ読み込み ────────────────────────────────────────

    private fun loadAll() {
        lifecycleScope.launch {
            repo.allProducts.collect { list ->
                adapter.submitList(list)
                updateCount(list.size)
            }
        }
    }

    private fun search(query: String) {
        lifecycleScope.launch {
            val result = if (query.isBlank()) repo.allProducts.first()
                         else repo.searchProducts(query)
            adapter.submitList(result)
            updateCount(result.size)
        }
    }

    private fun updateCount(n: Int) {
        (window.decorView.findViewWithTag<TextView>("count"))?.text = "全 $n 件"
    }

    // ── 詳細ダイアログ (納品履歴付き) ─────────────────────────

    private fun showDetailDialog(p: ProductMaster) {
        lifecycleScope.launch {
            // この商品の納品履歴を取得
            val history = repo.searchDeliveries(p.janCode)
            val dp = resources.displayMetrics.density

            val layout = LinearLayout(this@ProductListActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
            }

            // 商品情報カード
            layout.addView(TextView(this@ProductListActivity).apply {
                text = buildString {
                    appendLine("━━━ 商品情報 ━━━")
                    appendLine("JANコード : ${p.janCode}")
                    appendLine("商品名    : ${p.productName}")
                    appendLine("規格      : ${p.spec}")
                    appendLine("初回登録  : ${p.registeredAt.toDateStr()}")
                    append(    "最終納品  : ${p.lastDeliveryDate.ifBlank { "-" }}")
                }
                setTextColor(Color.parseColor("#CCCCEE"))
                textSize = 13f
                setBackgroundColor(Color.parseColor("#151528"))
                setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt())
            })

            // 納品履歴タイトル
            layout.addView(TextView(this@ProductListActivity).apply {
                text = "━━━ 納品履歴 (${history.size}件) ━━━"
                setTextColor(Color.parseColor("#7788BB")); textSize = 12f
                setPadding(0, (12*dp).toInt(), 0, (4*dp).toInt())
            })

            // 履歴テーブル (ScrollView内)
            val scrollView = ScrollView(this@ProductListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, (200*dp).toInt())
            }
            val histTable = LinearLayout(this@ProductListActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            if (history.isEmpty()) {
                histTable.addView(TextView(this@ProductListActivity).apply {
                    text = "納品履歴なし"
                    setTextColor(Color.parseColor("#555566")); textSize = 12f
                    setPadding(8.dp(), 8.dp(), 0, 0)
                })
            } else {
                history.forEach { rec ->
                    histTable.addView(TextView(this@ProductListActivity).apply {
                        text = "  ${rec.date}  数量: ${rec.quantity}  [${rec.projectName}]" +
                               if (rec.note.isNotBlank()) "  ${rec.note}" else ""
                        setTextColor(Color.parseColor("#AABBCC")); textSize = 11f
                        setPadding((4*dp).toInt(), (4*dp).toInt(), 0, (4*dp).toInt())
                    })
                    histTable.addView(View(this@ProductListActivity).apply {
                        setBackgroundColor(Color.parseColor("#1E1E30"))
                        layoutParams = LinearLayout.LayoutParams(-1, 1)
                    })
                }
            }

            scrollView.addView(histTable)
            layout.addView(scrollView)

            AlertDialog.Builder(this@ProductListActivity)
                .setTitle("🏷️ ${p.productName}")
                .setView(layout)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("✏️ 編集") { _, _ -> showEditDialog(p) }
                .show()
        }
    }

    // ── 編集ダイアログ ────────────────────────────────────────

    private fun showEditDialog(p: ProductMaster) {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
        }

        fun field(hint: String, value: String) = EditText(this).apply {
            this.hint = hint; setText(value)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (8*dp).toInt()
            }
            layout.addView(this)
        }

        // JANコードは変更不可 (主キー)
        layout.addView(TextView(this).apply {
            text = "JANコード: ${p.janCode} (変更不可)"
            setTextColor(Color.parseColor("#8888AA")); textSize = 12f
            setPadding(0, 0, 0, (8*dp).toInt())
        })
        val etName = field("商品名", p.productName)
        val etSpec = field("規格",   p.spec)

        AlertDialog.Builder(this)
            .setTitle("✏️ 商品編集")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    repo.updateProduct(p.copy(
                        productName = etName.text.toString(),
                        spec        = etSpec.text.toString()
                    ))
                    Toast.makeText(this@ProductListActivity,
                        "保存しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ── 削除確認 ──────────────────────────────────────────────

    private fun confirmDelete(p: ProductMaster) {
        AlertDialog.Builder(this)
            .setTitle("削除確認")
            .setMessage("「${p.productName}」を商品DBから削除しますか？\n※納品データは残ります")
            .setPositiveButton("削除") { _, _ ->
                lifecycleScope.launch { repo.deleteProduct(p) }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun Long.toDateStr(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.JAPAN)
        return sdf.format(java.util.Date(this))
    }
}

// ── ProductAdapter ────────────────────────────────────────────

class ProductAdapter(
    private val onTap:    (ProductMaster) -> Unit,
    private val onEdit:   (ProductMaster) -> Unit,
    private val onDelete: (ProductMaster) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ProductMaster, ProductAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProductMaster>() {
            override fun areItemsTheSame(a: ProductMaster, b: ProductMaster) =
                a.janCode == b.janCode
            override fun areContentsTheSame(a: ProductMaster, b: ProductMaster) = a == b
        }
    }

    inner class VH(val row: LinearLayout, val cells: List<TextView>) :
        RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp  = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4*dp).toInt(), (7*dp).toInt(), (4*dp).toInt(), (7*dp).toInt())
        }
        val weights = floatArrayOf(1.8f, 2.2f, 1.2f, 1.2f)
        val cells = weights.map { w ->
            TextView(ctx).apply {
                textSize = 11f; setTextColor(Color.parseColor("#DDDDEE"))
                layoutParams = LinearLayout.LayoutParams(0, -2, w)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }.also { row.addView(it) }
        }
        return VH(row, cells)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        holder.row.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#15152A")
            else                   Color.parseColor("#1A1A32")
        )
        val sdf = java.text.SimpleDateFormat("yy/MM/dd", java.util.Locale.JAPAN)
        holder.cells[0].text = p.janCode
        holder.cells[1].text = p.productName
        holder.cells[2].text = p.spec
        holder.cells[3].text = sdf.format(java.util.Date(p.registeredAt))

        holder.row.setOnClickListener { onTap(p) }
        holder.row.setOnLongClickListener {
            android.app.AlertDialog.Builder(holder.row.context)
                .setTitle("操作")
                .setItems(arrayOf("✏️ 編集", "🗑️ 削除")) { _, which ->
                    if (which == 0) onEdit(p) else onDelete(p)
                }.show()
            true
        }
    }
}
