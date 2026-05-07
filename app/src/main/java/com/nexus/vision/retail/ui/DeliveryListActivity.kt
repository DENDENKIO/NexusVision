package com.nexus.vision.retail.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexus.vision.retail.db.*
import com.nexus.vision.retail.repository.RetailRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DeliveryListActivity : AppCompatActivity() {

    private lateinit var repo: RetailRepository
    private lateinit var adapter: DeliveryAdapter

    // フィルター状態
    private var currentProject = ""
    private var currentQuery   = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db   = RetailDatabase.getInstance(this)
        repo     = RetailRepository(db)
        adapter  = DeliveryAdapter(
            onEdit   = { record -> showEditDialog(record) },
            onDelete = { record ->
                lifecycleScope.launch { repo.deleteDelivery(record) }
            }
        )

        // ── レイアウト (コード生成) ─────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }

        // 企画タブ (Spinner)
        val projectSpinner = Spinner(this)
        root.addView(projectSpinner, matchWidth(48.dp))

        // 検索バー
        val searchBar = EditText(this).apply {
            hint = "JANコード / 商品名 / 日付 で検索"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentQuery = s.toString()
                    reloadList()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            })
        }
        root.addView(searchBar, matchWidth(48.dp))

        // RecyclerView (表形式)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DeliveryListActivity)
            this.adapter  = this@DeliveryListActivity.adapter
        }
        root.addView(rv, matchWidth(0, weight = 1f))

        setContentView(root)
        title = "📦 納品データベース"

        // ── 企画一覧をSpinnerに反映 ────────────────────────────
        lifecycleScope.launch {
            repo.allProjects.collect { projects ->
                val items = listOf("すべて") + projects
                val spAdapter = ArrayAdapter(
                    this@DeliveryListActivity,
                    android.R.layout.simple_spinner_item, items
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                projectSpinner.adapter = spAdapter
            }
        }

        projectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentProject = if (pos == 0) "" else projectSpinner.selectedItem.toString()
                reloadList()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ── リスト自動更新 (Flow) ───────────────────────────
        lifecycleScope.launch {
            repo.allDeliveries.collect {
                reloadList()
            }
        }
    }

    private fun reloadList() {
        lifecycleScope.launch {
            val results = if (currentQuery.isBlank()) {
                if (currentProject.isBlank())
                    repo.allDeliveries.first()
                else
                    repo.deliveriesByProject(currentProject).first()
            } else {
                repo.searchDeliveries(currentQuery, currentProject)
            }
            adapter.submitList(results)
        }
    }

    private fun showEditDialog(record: DeliveryRecord) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        }
        val etDate  = EditText(this).apply { hint = "日付";     setText(record.date) }
        val etJan   = EditText(this).apply { hint = "JANコード"; setText(record.janCode) }
        val etName  = EditText(this).apply { hint = "商品名";   setText(record.productName) }
        val etSpec  = EditText(this).apply { hint = "規格";     setText(record.spec) }
        val etQty   = EditText(this).apply { hint = "数量";     setText(record.quantity.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etNote  = EditText(this).apply { hint = "備考";     setText(record.note) }
        val etProj  = EditText(this).apply { hint = "企画名";   setText(record.projectName) }
        listOf(etDate, etJan, etName, etSpec, etQty, etNote, etProj).forEach { layout.addView(it) }

        android.app.AlertDialog.Builder(this)
            .setTitle("✏️ 編集")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val updated = record.copy(
                    projectName = etProj.text.toString(),
                    date        = etDate.text.toString(),
                    janCode     = etJan.text.toString(),
                    productName = etName.text.toString(),
                    spec        = etSpec.text.toString(),
                    quantity    = etQty.text.toString().toIntOrNull() ?: record.quantity,
                    note        = etNote.text.toString()
                )
                lifecycleScope.launch { repo.updateDelivery(updated) }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun matchWidth(h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(-1, if (weight > 0) 0 else h, weight)
}
