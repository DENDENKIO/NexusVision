package com.nexus.vision.retail.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexus.vision.retail.db.RetailDatabase
import com.nexus.vision.retail.repository.RetailRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 小売業務モジュール ハブ画面
 */
class RetailMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        title = "🛒 NexusVision 業務ツール"
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A12"))
            gravity = android.view.Gravity.CENTER
            setPadding(24.dp(), 32.dp(), 24.dp(), 32.dp())
        }

        // タイトル
        root.addView(TextView(this).apply {
            text = "🛒 業務ツール"
            textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8.dp())
        })
        root.addView(TextView(this).apply {
            text = "小売スーパー 納品管理システム"
            textSize = 13f; setTextColor(Color.parseColor("#6677AA"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32.dp())
        })

        // DB統計カード (動的更新)
        val statTv = TextView(this).apply {
            setTextColor(Color.parseColor("#8899BB")); textSize = 12f
            setBackgroundColor(Color.parseColor("#111120"))
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            gravity = android.view.Gravity.CENTER
        }
        root.addView(statTv, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = 24.dp()
        })

        lifecycleScope.launch {
            val db   = RetailDatabase.getInstance(this@RetailMainActivity)
            val repo = RetailRepository(db)
            val deliveries = repo.allDeliveries.first()
            val products   = repo.allProducts.first()
            statTv.text = "納品レコード: ${deliveries.size}件  |  商品マスター: ${products.size}件"
        }

        // メニューボタン群
        data class MenuItem(val icon: String, val label: String,
                            val sub: String, val color: String,
                            val action: () -> Unit)

        listOf(
            MenuItem("📷", "納品伝票を撮影",
                "OCRで読み取り → DBに保存", "#1A4A7A") {
                startActivity(Intent(this, DeliveryOcrActivity::class.java))
            },
            MenuItem("📦", "納品データベース",
                "一覧・検索・編集・企画別フィルター", "#1A3A5A") {
                startActivity(Intent(this, DeliveryListActivity::class.java))
            },
            MenuItem("🏷️", "商品データベース",
                "JANコード検索・納品履歴確認", "#1A2A4A") {
                startActivity(Intent(this, ProductListActivity::class.java))
            },
            MenuItem("📥", "CSVインポート",
                "一括登録・更新（納品/商品）", "#333355") {
                startActivity(Intent(this, CsvImportActivity::class.java))
            }
        ).forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor(item.color))
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 12.dp()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { item.action() }
            }
            card.addView(TextView(this).apply {
                text = item.icon; textSize = 28f
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = 16.dp()
                }
            })
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = item.label; textSize = 15f; setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(this).apply {
                text = item.sub; textSize = 11f
                setTextColor(Color.parseColor("#AABBCC"))
            })
            card.addView(textCol)
            card.addView(TextView(this).apply {
                text = "›"; textSize = 20f; setTextColor(Color.parseColor("#6677AA"))
            })
            root.addView(card)
        }

        return root
    }
}
