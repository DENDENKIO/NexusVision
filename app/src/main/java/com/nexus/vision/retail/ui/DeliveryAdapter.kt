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

        // 列幅比率
        private val COL_WEIGHTS = floatArrayOf(
            1.1f, // 日付
            1.5f, // JANコード
            2.0f, // 商品名
            1.2f, // 規格
            0.7f, // 数量
            1.5f  // 備考
        )
    }

    inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val cells = (0 until row.childCount).map { row.getChildAt(it) as TextView }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp  = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1C1C2E"))
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
        }
        COL_WEIGHTS.forEach { w ->
            row.addView(TextView(ctx).apply {
                textSize = 11f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, -2, w)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        // 縞模様
        holder.row.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#1C1C2E")
            else                   Color.parseColor("#161622")
        )
        val cells = holder.cells
        cells[0].text = r.date
        cells[1].text = r.janCode
        cells[2].text = r.productName
        cells[3].text = r.spec
        cells[4].text = r.quantity.toString()
        cells[5].text = r.note

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
