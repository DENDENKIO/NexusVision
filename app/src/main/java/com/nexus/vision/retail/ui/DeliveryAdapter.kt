package com.nexus.vision.retail.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexus.vision.retail.db.DeliveryRecord

class DeliveryAdapter(
    private val onEdit:   (DeliveryRecord) -> Unit,
    private val onDelete: (DeliveryRecord) -> Unit
) : ListAdapter<DeliveryRecord, DeliveryAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeliveryRecord>() {
            override fun areItemsTheSame(a: DeliveryRecord, b: DeliveryRecord) = a.id == b.id
            override fun areContentsTheSame(a: DeliveryRecord, b: DeliveryRecord) = a == b
        }

        // 列定義: ラベル to 固定幅dp（ヘッダと完全一致）
        val COLUMNS = listOf(
            "日付"      to 90,
            "部門"      to 44,
            "JAN"      to 112,
            "メーカー"  to 100,
            "商品名"    to 200,
            "規格"      to 90,
            "数量"      to 48,
            "備考"      to 120
        )
    }

    inner class VH(
        val row:   LinearLayout,
        val cells: List<TextView>
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp  = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4.dp(), 5.dp(), 4.dp(), 5.dp())
        }

        val cells = COLUMNS.map { (_, widthDp) ->
            TextView(ctx).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#DDDDEE"))
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(2.dp(), 0, 2.dp(), 0)
                layoutParams = LinearLayout.LayoutParams(widthDp.dp(), -2)
            }.also { row.addView(it) }
        }

        return VH(row, cells)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.row.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#1C1C2E")
            else                   Color.parseColor("#161622")
        )
        holder.cells[0].text = r.date
        holder.cells[1].text = r.department
        holder.cells[2].text = r.janCode
        holder.cells[3].text = r.maker
        holder.cells[4].text = r.productName
        holder.cells[5].text = r.spec
        holder.cells[6].text = r.quantity.toString()
        holder.cells[7].text = r.note

        holder.row.setOnLongClickListener {
            android.app.AlertDialog.Builder(holder.row.context)
                .setTitle("操作")
                .setItems(arrayOf("✏️ 編集", "🗑️ 削除")) { _, which ->
                    if (which == 0) onEdit(r) else onDelete(r)
                }.show()
            true
        }
    }
}
