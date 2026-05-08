package com.nexus.vision.retail.ui

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.nexus.vision.retail.ocr.BitmapPreprocessor
import com.nexus.vision.retail.ocr.DocumentScannerHelper
import com.nexus.vision.retail.repository.RetailRepository
import com.nexus.vision.ocr.MlKitOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
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
    private lateinit var docScanHelper: DocumentScannerHelper

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

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        docScanHelper.handleResult(result.resultCode, result.data)
    }

    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo          = RetailRepository(RetailDatabase.getInstance(this))
        confirmAdapter = OcrConfirmAdapter { pos, record -> parsedRecords[pos] = record }

        setContentView(buildLayout())
        title = "📷 納品伝票 OCR"

        docScanHelper = DocumentScannerHelper(
            activity   = this,
            onScanned  = { uris ->
                uris.firstOrNull()?.let { uri ->
                    photoUri = uri
                    runOcr(uri)
                }
            },
            onError = { e ->
                Log.w("DocScanner", "スキャナー使用不可: ${e.message}")
                toast("スキャナー起動失敗。通常カメラを使用します")
                launchCamera()
            }
        )
        docScanHelper.init(maxPages = 1, fullMode = true)
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
        val scanBtn   = makeButton("🔍 スキャン", "#1A3A6A") { docScanHelper.startScan(scanLauncher) }
        val gallBtn   = makeButton("🖼 ギャラリー", "#225544") { galleryLauncher.launch("image/*") }
        retakeBtn     = makeButton("🔄 再撮影", "#444455") { checkCameraAndLaunch() }
        retakeBtn.visibility = View.GONE
        btnRow.addView(cameraBtn, lp(0, 44.dp(), 1f))
        btnRow.addView(scanBtn,   lp(0, 44.dp(), 1f).apply { marginStart = 8.dp() })
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
            listOf(
                "日付"     to 1.1f,
                "部門"     to 0.6f,
                "JAN"     to 1.4f,
                "メーカー" to 1.0f,
                "商品名"   to 1.8f,
                "規格"     to 1.0f,
                "数量"     to 0.6f,
                "備考"     to 1.0f
            ).forEach { (name, w) ->
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
        
        // 巨大画像によるクラッシュ回避のためサムネイル表示
        showThumbnail(uri)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ① Uri → Bitmap 変換
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }

                // ② MlKitOcrEngine.recognize(bitmap) → OcrResult（座標付き）
                val engine    = MlKitOcrEngine()
                val rotated   = correctRotation(bitmap, uri)
                val processed = BitmapPreprocessor.process(rotated)
                val ocrResult = engine.recognize(processed)
                engine.close()

                // ③ DeliveryOcrParser.parseWithTable（TableReconstructor連携）
                val project = projectEt.text.toString().ifBlank { "通常" }
                val results = DeliveryOcrParser.parseWithTable(ocrResult, project)

                withContext(Dispatchers.Main) { processOcrResult(results) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("❌ OCR失敗: ${e.message}")
                }
            }
        }
    }

    private fun processOcrResult(results: List<DeliveryOcrParser.ParseResult>) {
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
            "$packageName.retail.provider", file)
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

    private fun correctRotation(bitmap: Bitmap, uri: Uri): Bitmap {
        val exif = try {
            contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it)
            }
        } catch (e: Exception) { null } ?: return bitmap

        val rotation = when (
            exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 巨大画像による Canvas: trying to draw too large bitmap クラッシュを回避
     * ImageView のサイズに合わせてダウンサンプリングして表示
     */
    private fun showThumbnail(uri: Uri) {
        val targetW = previewImg.width.takeIf  { it > 0 } ?: 800
        val targetH = previewImg.height.takeIf { it > 0 } ?: 600

        try {
            // サイズのみ取得
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val w = opts.outWidth; val h = opts.outHeight
            if (w <= 0 || h <= 0) {
                previewImg.setImageURI(uri)
                return
            }

            // 適切な sampleSize を計算 (ImageViewの2倍程度まで許容)
            var sampleSize = 1
            while (w / sampleSize > targetW * 2 || h / sampleSize > targetH * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val thumb = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
            previewImg.setImageBitmap(thumb)
        } catch (e: Exception) {
            Log.e("DeliveryOcr", "Thumbnail failed", e)
            previewImg.setImageURI(uri)
        }
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
        private val COL_WEIGHTS = floatArrayOf(1.1f, 0.6f, 1.4f, 1.0f, 1.8f, 1.0f, 0.6f, 1.0f)
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
        holder.cells[1].text = r.department
        holder.cells[2].text = r.janCode
        holder.cells[3].text = r.maker
        holder.cells[4].text = r.productName
        holder.cells[5].text = r.spec
        holder.cells[6].text = r.quantity.toString()
        holder.cells[7].text = r.note

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

        val etDate   = field("日付 (YYYY-MM-DD)", r.date)
        val etDept   = field("部門 (数字)",       r.department)
        val etJan    = field("JANコード",         r.janCode)
        val etMaker  = field("メーカー",          r.maker)
        val etName   = field("商品名",            r.productName)
        val etSpec   = field("規格",              r.spec)
        val etQty    = field("数量",              r.quantity.toString()).also {
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etNote   = field("備考",              r.note)
        val etProj   = field("企画名",            r.projectName)

        AlertDialog.Builder(ctx)
            .setTitle("✏️ 行を編集 (${pos+1}行目)")
            .setView(layout)
            .setPositiveButton("確定") { _, _ ->
                val updated = r.copy(
                    date        = etDate.text.toString(),
                    department  = etDept.text.toString(),
                    janCode     = etJan.text.toString(),
                    maker       = etMaker.text.toString(),
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
