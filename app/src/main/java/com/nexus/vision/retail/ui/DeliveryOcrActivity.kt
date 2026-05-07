package com.nexus.vision.retail.ui

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.nexus.vision.retail.db.*
import com.nexus.vision.retail.ocr.DeliveryOcrParser
import com.nexus.vision.retail.repository.RetailRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * 納品伝票撮影 → OCR解析 → 確認・編集 → DB保存
 *
 * フロー:
 *  ① カメラ起動 or ギャラリー選択
 *  ② ML Kit OCR (日本語)
 *  ③ DeliveryOcrParser でパース
 *  ④ 確認テーブル表示 (編集可)
 *  ⑤ 保存 → RetailRepository.saveAllWithProduct()
 */
class DeliveryOcrActivity : AppCompatActivity() {

    private lateinit var repo: RetailRepository
    private lateinit var previewImg: ImageView
    private lateinit var statusTv: TextView
    private lateinit var projectEt: EditText
    private lateinit var confirmRv: RecyclerView
    private lateinit var saveBtn: Button
    private lateinit var retakeBtn: Button

    private var photoUri: Uri? = null
    private val parsedRecords = mutableListOf<DeliveryRecord>()
    private lateinit var confirmAdapter: OcrConfirmAdapter

    // ── ランチャー ────────────────────────────────────────────

    private val cameraLauncher = registerForActivityResult(TakePicture()) { ok ->
        if (ok) photoUri?.let { runOcr(it) }
    }

    private val galleryLauncher = registerForActivityResult(GetContent()) { uri ->
        uri?.let { photoUri = it; runOcr(it) }
    }

    private val permLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("カメラ権限が必要です")
    }

    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo          = RetailRepository(RetailDatabase.getInstance(this))
        confirmAdapter = OcrConfirmAdapter { pos, record -> parsedRecords[pos] = record }

        setContentView(buildLayout())
        title = "📷 納品伝票 OCR"
    }

    // ── UI構築 ────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        // 企画名入力
        val projRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        projRow.addView(TextView(this).apply {
            text = "企画名："; setTextColor(Color.parseColor("#AAAACC")); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp() }
        })
        projectEt = EditText(this).apply {
            hint = "通常"; setText("通常")
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555566"))
            setBackgroundColor(Color.parseColor("#1C1C2E"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp() }
        }
        projRow.addView(projectEt)
        root.addView(projRow, lp(-1, -2))

        // ステータス
        statusTv = TextView(this).apply {
            text = "写真を撮影またはギャラリーから選択してください"
            setTextColor(Color.parseColor("#7777AA")); textSize = 12f
            setPadding(0, 8.dp(), 0, 4.dp())
        }
        root.addView(statusTv, lp(-1, -2))

        // プレビュー画像
        previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#111120"))
            layoutParams = LinearLayout.LayoutParams(-1, 180.dp())
        }
        root.addView(previewImg)

        // 操作ボタン行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        val cameraBtn = makeButton("📷 撮影", "#2255AA") { checkCameraAndLaunch() }
        val gallBtn   = makeButton("🖼 ギャラリー", "#225544") { galleryLauncher.launch("image/*") }
        retakeBtn     = makeButton("🔄 再撮影", "#444455") { checkCameraAndLaunch() }
        retakeBtn.visibility = View.GONE
        btnRow.addView(cameraBtn, lp(0, 44.dp(), 1f))
        btnRow.addView(gallBtn,   lp(0, 44.dp(), 1f).apply { marginStart = 8.dp() })
        btnRow.addView(retakeBtn, lp(0, 44.dp(), 1f).apply { marginStart = 8.dp() })
        root.addView(btnRow, lp(-1, -2))

        // OCR確認テーブル (RecyclerView)
        val header = buildTableHeader()
        root.addView(header, lp(-1, -2))

        confirmRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DeliveryOcrActivity)
            adapter = confirmAdapter
        }
        root.addView(confirmRv, lp(-1, 0, 1f))

        // 保存ボタン
        saveBtn = makeButton("💾 データベースに保存", "#1A7A3A") {
            saveToDatabase()
        }
        saveBtn.visibility = View.GONE
        root.addView(saveBtn, lp(-1, 52.dp()).apply {
            topMargin = 8.dp()
        })

        return root
    }

    // ── テーブルヘッダー ──────────────────────────────────────

    private fun buildTableHeader(): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A3A"))
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
            listOf("日付" to 1.1f, "JAN" to 1.5f, "商品名" to 2.0f,
                   "規格" to 1.2f, "数量" to 0.7f, "備考" to 1.5f).forEach { (name, w) ->
                addView(TextView(context).apply {
                    text = name; textSize = 11f
                    setTextColor(Color.parseColor("#AACCFF"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, w)
                })
            }
            // 操作列
            addView(TextView(context).apply {
                text = "操作"; textSize = 11f
                setTextColor(Color.parseColor("#AACCFF"))
                layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), -2)
            })
        }
    }

    // ── OCR実行 ───────────────────────────────────────────────

    private fun runOcr(uri: Uri) {
        setStatus("🔍 OCR解析中...")
        saveBtn.visibility  = View.GONE
        retakeBtn.visibility = View.VISIBLE

        // プレビュー表示
        try {
            val bmp = InputImage.fromFilePath(this, uri).bitmapInternal
            previewImg.setImageBitmap(bmp)
        } catch (_: Exception) {
            previewImg.setImageURI(uri)
        }

        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            setStatus("❌ 画像読み込み失敗: ${e.message}"); return
        }

        // ML Kit OCR (日本語対応)
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                processOcrResult(rawText)
            }
            .addOnFailureListener { e ->
                setStatus("❌ OCR失敗: ${e.message}")
            }
    }

    private fun processOcrResult(rawText: String) {
        val project = projectEt.text.toString().ifBlank { "通常" }
        val results = DeliveryOcrParser.parse(rawText, project)

        val successes = results.filterIsInstance<DeliveryOcrParser.ParseResult.Success>()
        val failures  = results.filterIsInstance<DeliveryOcrParser.ParseResult.Failed>()

        parsedRecords.clear()
        parsedRecords.addAll(successes.map { it.record })
        confirmAdapter.submitList(parsedRecords.toMutableList())

        val msg = buildString {
            append("✅ ${successes.size}件パース成功")
            if (failures.isNotEmpty()) append(" / ⚠️ ${failures.size}件スキップ")
        }
        setStatus(msg)

        if (failures.isNotEmpty()) showFailureDetail(failures)

        saveBtn.visibility = if (successes.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ── 保存処理 ──────────────────────────────────────────────

    private fun saveToDatabase() {
        if (parsedRecords.isEmpty()) { toast("保存するデータがありません"); return }

        lifecycleScope.launch {
            setStatus("💾 保存中...")
            saveBtn.isEnabled = false

            val (saved, skipped) = repo.saveAllWithProduct(parsedRecords)

            saveBtn.isEnabled = true
            val msg = "✅ ${saved}件保存完了" +
                if (skipped > 0) "\n⏭️ ${skipped}件スキップ（重複）" else ""
            setStatus(msg)

            AlertDialog.Builder(this@DeliveryOcrActivity)
                .setTitle("保存完了")
                .setMessage(msg)
                .setPositiveButton("納品一覧を見る") { _, _ ->
                    startActivity(android.content.Intent(
                        this@DeliveryOcrActivity, DeliveryListActivity::class.java))
                }
                .setNegativeButton("続けて撮影") { _, _ -> }
                .show()

            if (saved > 0) {
                parsedRecords.clear()
                confirmAdapter.submitList(emptyList())
                saveBtn.visibility = View.GONE
            }
        }
    }

    // ── 失敗詳細ダイアログ ────────────────────────────────────

    private fun showFailureDetail(failures: List<DeliveryOcrParser.ParseResult.Failed>) {
        val msg = failures.joinToString("\n\n") {
            "行: 「${it.rawLine.take(40)}」\n理由: ${it.reason}"
        }
        AlertDialog.Builder(this)
            .setTitle("⚠️ パース失敗行 (${failures.size}件)")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── カメラ起動 ────────────────────────────────────────────

    private fun checkCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val file = File(cacheDir, "delivery_ocr_${System.currentTimeMillis()}.jpg")
        photoUri = FileProvider.getUriForFile(this,
            "$packageName.provider", file)
        cameraLauncher.launch(photoUri!!)
    }

    // ── ユーティリティ ────────────────────────────────────────

    private fun setStatus(msg: String) { statusTv.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun makeButton(label: String, color: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            setOnClickListener { onClick() }
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)
}

// ── OCR確認用Adapter ─────────────────────────────────────────

/**
 * OCR結果を確認テーブルとして表示。
 * 各セルタップで編集ダイアログを開く。
 */
class OcrConfirmAdapter(
    private val onRowEdited: (Int, DeliveryRecord) -> Unit
) : androidx.recyclerview.widget.ListAdapter<DeliveryRecord, OcrConfirmAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeliveryRecord>() {
            override fun areItemsTheSame(a: DeliveryRecord, b: DeliveryRecord) =
                a.janCode == b.janCode && a.date == b.date
            override fun areContentsTheSame(a: DeliveryRecord, b: DeliveryRecord) = a == b
        }
        private val COL_WEIGHTS = floatArrayOf(1.1f, 1.5f, 2.0f, 1.2f, 0.7f, 1.5f)
    }

    inner class VH(val row: LinearLayout, val cells: List<TextView>, val editBtn: ImageButton)
        : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp  = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4*dp).toInt(), (5*dp).toInt(), (4*dp).toInt(), (5*dp).toInt())
        }
        val cells = COL_WEIGHTS.map { w ->
            TextView(ctx).apply {
                textSize = 10f; setTextColor(Color.parseColor("#DDDDEE"))
                layoutParams = LinearLayout.LayoutParams(0, -2, w)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }.also { row.addView(it) }
        }
        val editBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                (40*dp).toInt(), (40*dp).toInt())
        }
        row.addView(editBtn)
        return VH(row, cells, editBtn)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.row.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#15152A")
            else                   Color.parseColor("#1A1A32")
        )
        holder.cells[0].text = r.date
        holder.cells[1].text = r.janCode
        holder.cells[2].text = r.productName
        holder.cells[3].text = r.spec
        holder.cells[4].text = r.quantity.toString()
        holder.cells[5].text = r.note

        holder.editBtn.setOnClickListener {
            showEditDialog(holder.row.context, position, r)
        }
    }

    private fun showEditDialog(ctx: android.content.Context, pos: Int, r: DeliveryRecord) {
        val dp = ctx.resources.displayMetrics.density
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
        }

        fun field(hint: String, value: String) = EditText(ctx).apply {
            this.hint = hint; setText(value)
            setTextColor(Color.parseColor("#222233"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (8*dp).toInt()
            }
            layout.addView(this)
        }

        val etDate = field("日付 (YYYY-MM-DD)",   r.date)
        val etJan  = field("JANコード",             r.janCode)
        val etName = field("商品名",               r.productName)
        val etSpec = field("規格",                 r.spec)
        val etQty  = field("数量",                 r.quantity.toString()).also {
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etNote = field("備考",                 r.note)
        val etProj = field("企画名",               r.projectName)

        AlertDialog.Builder(ctx)
            .setTitle("✏️ 行を編集 (${pos+1}行目)")
            .setView(layout)
            .setPositiveButton("確定") { _, _ ->
                val updated = r.copy(
                    date        = etDate.text.toString(),
                    janCode     = etJan.text.toString(),
                    productName = etName.text.toString(),
                    spec        = etSpec.text.toString(),
                    quantity    = etQty.text.toString().toIntOrNull() ?: r.quantity,
                    note        = etNote.text.toString(),
                    projectName = etProj.text.toString()
                )
                onRowEdited(pos, updated)
                val newList = currentList.toMutableList()
                newList[pos] = updated
                submitList(newList)
            }
            .setNegativeButton("キャンセル", null)
            .setNeutralButton("🗑️ この行を削除") { _, _ ->
                val newList = currentList.toMutableList()
                newList.removeAt(pos)
                submitList(newList)
            }
            .show()
    }
}
