package ir.keyhansaze.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Product(val id: Int, val name: String)

class InventoryDb(context: android.content.Context) :
    SQLiteOpenHelper(context, "scanner_inventory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE serials(code TEXT PRIMARY KEY, product_id INTEGER NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(product_id) REFERENCES products(id))")
        db.execSQL("CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, product_id INTEGER NOT NULL, action TEXT NOT NULL, at TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun products(): List<Product> {
        val result = mutableListOf<Product>()
        readableDatabase.rawQuery("SELECT id,name FROM products ORDER BY id", null).use { c ->
            while (c.moveToNext()) result.add(Product(c.getInt(0), c.getString(1)))
        }
        return result
    }

    fun addProduct(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "نام محصول را وارد کنید."
        val values = ContentValues().apply { put("name", clean) }
        return try {
            writableDatabase.insertOrThrow("products", null, values)
            null
        } catch (_: Exception) { "این نام قبلاً ثبت شده است." }
    }

    fun record(productId: Int, codeInput: String, incoming: Boolean): String {
        val code = codeInput.trim()
        if (code.isEmpty()) return "سریال خالی است."
        val db = writableDatabase
        val existingProduct = db.rawQuery("SELECT product_id,status FROM serials WHERE code=?", arrayOf(code)).use { c ->
            if (c.moveToFirst()) Pair(c.getInt(0), c.getString(1)) else null
        }
        val action = if (incoming) "IN" else "OUT"
        val timestamp = now()
        if (incoming) {
            if (existingProduct != null && existingProduct.first != productId) return "این سریال قبلاً برای محصول دیگری ثبت شده است."
            if (existingProduct != null && existingProduct.second == "IN") return "سریال تکراری است؛ کالا هم‌اکنون موجود است."
            if (existingProduct == null) {
                val values = ContentValues().apply {
                    put("code", code); put("product_id", productId); put("status", "IN")
                    put("created_at", timestamp); put("updated_at", timestamp)
                }
                db.insert("serials", null, values)
            } else {
                db.execSQL("UPDATE serials SET status='IN', updated_at=? WHERE code=?", arrayOf(timestamp, code))
            }
        } else {
            if (existingProduct == null || existingProduct.first != productId) return "این سریال در محصول انتخاب‌شده ثبت نشده است."
            if (existingProduct.second != "IN") return "این کالا قبلاً از انبار خارج شده است."
            db.execSQL("UPDATE serials SET status='OUT', updated_at=? WHERE code=?", arrayOf(timestamp, code))
        }
        db.execSQL("INSERT INTO movements(code,product_id,action,at) VALUES(?,?,?,?)", arrayOf(code, productId, action, timestamp))
        return if (incoming) "ورود کالا ثبت شد." else "خروج کالا ثبت شد."
    }

    fun deleteProduct(productId: Int): String? {
        val hasSerials = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM serials WHERE product_id=?",
            arrayOf(productId.toString())
        ).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (hasSerials) return "برای این محصول سابقه سریال ثبت شده است؛ برای حفظ سوابق، حذف آن مجاز نیست."
        writableDatabase.delete("products", "id=?", arrayOf(productId.toString()))
        return null
    }

    fun stockCount(productId: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM serials WHERE product_id=? AND status='IN'", arrayOf(productId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun outCount(productId: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM serials WHERE product_id=? AND status='OUT'", arrayOf(productId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun totalCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM serials WHERE status='IN'", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun totalOutCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM serials WHERE status='OUT'", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun serialRows(): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT p.name,s.code,CASE s.status WHEN 'IN' THEN 'موجود' ELSE 'خارج‌شده' END,s.created_at,s.updated_at FROM serials s JOIN products p ON p.id=s.product_id ORDER BY p.name,s.code", null).use { c ->
            while (c.moveToNext()) rows.add(Array(5) { i -> c.getString(i) ?: "" })
        }
        return rows
    }

    fun productSummary(): List<Pair<String, Int>> = products().map { it.name to stockCount(it.id) }

    fun productOutSummary(): List<Pair<String, Int>> = products().map { it.name to outCount(it.id) }

    fun movementRows(): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT p.name, m.action, m.at, m.code FROM movements m JOIN products p ON p.id=m.product_id ORDER BY m.id DESC", null).use { c ->
            while (c.moveToNext()) rows.add(arrayOf(c.getString(0) ?: "", if (c.getString(1) == "IN") "ورود" else "خروج", c.getString(2) ?: "", c.getString(3) ?: ""))
        }
        return rows
    }
}

class MainActivity : Activity() {
    private lateinit var db: InventoryDb
    private lateinit var productSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var countText: TextView
    private lateinit var detailText: TextView
    private lateinit var feedbackLight: TextView
    private var toneGenerator: ToneGenerator? = null
    private var products: List<Product> = emptyList()
    private var lastIncoming: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        db = InventoryDb(this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 6, 12, 8)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF4F6F2.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.scanner_logo)
            contentDescription = "لوگوی اپلیکیشن اسکنر"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(logo, LinearLayout.LayoutParams(56, 56).apply { marginEnd = 10 })
        val title = TextView(this).apply {
            text = "اسکنر | مدیریت انبار"
            textSize = 20f
            setTextColor(0xFF2A4A32.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 })

        countText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A2E20.toInt())
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFFDCE8D4.toInt())
        }
        root.addView(countText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })

        val productLabel = TextView(this).apply {
            text = "نوع محصول"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(4, 0, 4, 2)
        }
        root.addView(productLabel)
        productSpinner = Spinner(this).apply {
            minimumHeight = 96
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(productSpinner, LinearLayout.LayoutParams(-1, 96).apply { bottomMargin = 6 })

        val modeLabel = TextView(this).apply {
            text = "نوع عملیات"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(4, 0, 4, 2)
        }
        root.addView(modeLabel)
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("ورود به انبار", "خروج از انبار")).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            minimumHeight = 72
            setPadding(14, 10, 14, 10)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(modeSpinner, LinearLayout.LayoutParams(-1, 72).apply { bottomMargin = 8 })

        addButton(root, "▦  اسکن بارکد با دوربین", 0xFF0D6B63.toInt(), 92) { startScan() }

        feedbackLight = TextView(this).apply {
            text = "آماده اسکن"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFF546E7A.toInt())
        }
        root.addView(feedbackLight, LinearLayout.LayoutParams(-1, 40).apply { topMargin = 4; bottomMargin = 8 })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 4, 2, 8)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
        }

        addButton(content, "＋ ثبت محصول", 0xFF4A6B3A.toInt(), 56) { addProductDialog() }
        addButton(content, "− حذف محصول", 0xFF9A3B34.toInt(), 56) { deleteProductDialog() }
        addButton(content, "＋ ثبت دستی سریال", 0xFF3F5A72.toInt(), 56) { manualEntryDialog() }
        addButton(content, "⇩ خروجی Excel (XLSX)", 0xFF556B40.toInt(), 56) { exportXlsx() }
        addButton(content, "▤ خروجی PDF", 0xFF8A5F30.toInt(), 56) { exportPdf() }
        addButton(content, "▦ جدول موجودی و گردش کالا", 0xFF2C5568.toInt(), 56) { showInventoryTable() }

        detailText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(6, 10, 6, 6)
            gravity = Gravity.RIGHT
        }
        content.addView(detailText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })

        val note = TextView(this).apply {
            text = "اطلاعات فقط روی همین گوشی ذخیره می‌شود.\nپس از اسکن، محصول و نوع عملیات حفظ می‌شود تا زمانی که خودتان تغییر دهید."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(0, 8, 0, 4)
        }
        content.addView(note)

        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addButton(root: LinearLayout, label: String, color: Int, height: Int = 56, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = if (height >= 88) 18f else 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isAllCaps = false
            minHeight = height
            minimumHeight = height
            gravity = Gravity.CENTER
            setPadding(10, 6, 10, 6)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            setOnClickListener { action() }
        }
        root.addView(b, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = 6 })
    }

    private fun showInventoryTable() {
        val movements = db.movementRows()
        val serials = db.serialRows()
        val scroll = ScrollView(this)
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(table)
        fun cell(value: String, bold: Boolean = false): TextView = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xFF202820.toInt())
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
        }
        fun row(values: List<String>, header: Boolean = false) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (header) 0xFFDCE8D8.toInt() else 0xFFFFFFFF.toInt())
            }
            values.forEach { value -> line.addView(cell(value, header), LinearLayout.LayoutParams(0, -2, 1f)) }
            table.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 1 })
        }
        table.addView(cell("گردش ورود و خروج کالا", true))
        row(listOf("محصول", "عملیات", "تاریخ و ساعت", "سریال"), true)
        if (movements.isEmpty()) row(listOf("—", "گردشی ثبت نشده", "—", "—")) else movements.forEach { row(it.toList()) }
        table.addView(cell("وضعیت موجودی هر سریال", true))
        row(listOf("محصول", "سریال", "وضعیت", "زمان ثبت ورود"), true)
        if (serials.isEmpty()) row(listOf("—", "سریالی ثبت نشده", "—", "—")) else serials.forEach { row(listOf(it[0], it[1], it[2], it[3])) }
        AlertDialog.Builder(this).setTitle("جدول موجودی و خروجی").setView(scroll).setPositiveButton("بستن", null).show()
    }

    private fun addProductDialog() {
        val input = EditText(this).apply { hint = "مثلاً دستگاه مدل A"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("تعریف نوع محصول").setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val error = db.addProduct(input.text.toString())
                if (error != null) toast(error) else { toast("محصول ثبت شد."); refresh() }
            }.show()
    }

    private fun deleteProductDialog() {
        if (products.isEmpty()) {
            toast("محصولی برای حذف وجود ندارد.")
            return
        }
        val names = products.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتخاب محصول برای حذف")
            .setItems(names) { _, which ->
                val product = products[which]
                AlertDialog.Builder(this)
                    .setTitle("حذف محصول انتخاب‌شده؟")
                    .setMessage("محصول فقط در صورتی حذف می‌شود که هیچ سریالی برای آن ثبت نشده باشد.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ ->
                        val error = db.deleteProduct(product.id)
                        if (error != null) toast(error) else {
                            toast("محصول حذف شد.")
                            refresh()
                        }
                    }
                    .show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun selectedProduct(): Product? {
        if (products.isEmpty() || productSpinner.selectedItemPosition !in products.indices) {
            toast("ابتدا یک نوع محصول تعریف کنید.")
            return null
        }
        return products[productSpinner.selectedItemPosition]
    }

    private fun isIncoming() = modeSpinner.selectedItemPosition == 0

    private fun manualEntryDialog() {
        val product = selectedProduct() ?: return
        val input = EditText(this).apply {
            hint = "سریال یا شماره بارکد"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this).setTitle(if (isIncoming()) "ثبت دستی ورود" else "ثبت دستی خروج")
            .setView(input).setNegativeButton("لغو", null)
            .setPositiveButton("ثبت") { _, _ ->
                lastIncoming = isIncoming()
                showRecordFeedback(db.record(product.id, input.text.toString(), isIncoming()), isIncoming())
                refresh()
            }.show()
    }

    private fun startScan() {
        if (selectedProduct() == null) return
        lastIncoming = isIncoming()
        val integrator = IntentIntegrator(this)
        integrator.setCaptureActivity(CaptureActivity::class.java)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
        integrator.setPrompt(if (isIncoming()) "بارکد کالا را اسکن کنید - ورود به انبار" else "بارکد کالا را اسکن کنید - خروج از انبار")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        integrator.setBarcodeImageEnabled(false)
        try {
            integrator.initiateScan()
        } catch (e: Exception) {
            toast("باز کردن دوربین اسکن ناموفق بود: " + (e.localizedMessage ?: "خطای ناشناخته"))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val p = selectedProduct()
                if (p != null) {
                    val incoming = isIncoming()
                    lastIncoming = incoming
                    showRecordFeedback(db.record(p.id, result.contents, incoming), incoming)
                }
                refresh()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun playFeedbackSound(success: Boolean, incoming: Boolean) {
        val tg = toneGenerator ?: return
        if (!success) {
            tg.startTone(ToneGenerator.TONE_PROP_NACK, 140)
            Handler(Looper.getMainLooper()).postDelayed({
                tg.startTone(ToneGenerator.TONE_PROP_NACK, 140)
            }, 180)
        } else if (incoming) {
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 220)
        } else {
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 280)
        }
    }

    private fun showRecordFeedback(message: String, incoming: Boolean = lastIncoming) {
        val success = message == "ورود کالا ثبت شد." || message == "خروج کالا ثبت شد."
        feedbackLight.text = if (success) "✓ ثبت موفق" else "✕ خطا: $message"
        feedbackLight.setBackgroundColor(if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        playFeedbackSound(success, incoming)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({
            if (::feedbackLight.isInitialized) {
                feedbackLight.text = "آماده اسکن"
                feedbackLight.setBackgroundColor(0xFF546E7A.toInt())
            }
        }, 1200)
    }

    private fun refresh() {
        val prevProductPos = if (::productSpinner.isInitialized) productSpinner.selectedItemPosition else 0
        val prevModePos = if (::modeSpinner.isInitialized) modeSpinner.selectedItemPosition else 0

        products = db.products()
        productSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            if (products.isEmpty()) listOf("ابتدا محصول اضافه کنید") else products.map { it.name }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (products.isNotEmpty() && prevProductPos in products.indices) {
            productSpinner.setSelection(prevProductPos)
        }
        modeSpinner.setSelection(prevModePos.coerceIn(0, 1))

        countText.text = "موجودی کل: ${db.totalCount()}  |  خارج‌شده: ${db.totalOutCount()}"
        detailText.text = if (products.isEmpty()) {
            "برای شروع، نوع محصول را اضافه کنید.\n\nهر سریال فقط یک‌بار در موجودی ثبت می‌شود و ورود و خروج آن در تاریخچه ذخیره می‌شود."
        } else {
            products.joinToString("\n") {
                "• ${it.name}: ${db.stockCount(it.id)} موجود  /  ${db.outCount(it.id)} خارج‌شده"
            } + "\n\nتعداد کل سریال‌های ثبت‌شده: ${db.serialRows().size}"
        }
    }

    private fun exportXlsx() {
        val rows = db.serialRows()
        val summary = db.productSummary()
        val outSummary = db.productOutSummary()
        val name = "Scanner_inventory_${System.currentTimeMillis()}.xlsx"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("امکان ساخت فایل وجود ندارد")
            contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    fun entry(path: String, content: String) {
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")
                    entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
                    entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="گزارش انبار" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                    entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")
                    val table = mutableListOf<List<String>>()
                    table.add(listOf("محصول", "سریال", "وضعیت", "تاریخ ثبت", "آخرین تغییر"))
                    rows.forEach { table.add(it.toList()) }
                    table.add(listOf("", "", "", "", ""))
                    table.add(listOf("خلاصه موجودی", "تعداد موجود"))
                    summary.forEach { table.add(listOf(it.first, it.second.toString())) }
                    table.add(listOf("", "", "", "", ""))
                    table.add(listOf("خلاصه خروجی", "تعداد خارج‌شده"))
                    outSummary.forEach { table.add(listOf(it.first, it.second.toString())) }
                    table.add(listOf("جمع کل خارج‌شده", db.totalOutCount().toString()))
                    fun colName(n: Int): String {
                        var x = n
                        var result = ""
                        while (x > 0) {
                            val rem = (x - 1) % 26
                            result = ('A'.code + rem).toChar() + result
                            x = (x - 1) / 26
                        }
                        return result
                    }
                    fun xmlEscape(s: String) = s.replace("&", "&").replace("<", "<").replace(">", ">").replace("\"", """).replace("'", "'")
                    val sheet = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                    table.forEachIndexed { ri, row ->
                        val rowNum = ri + 1
                        sheet.append("<row r=\"").append(rowNum).append("\">")
                        row.forEachIndexed { ci, value ->
                            val ref = colName(ci + 1) + rowNum
                            sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                .append(xmlEscape(value)).append("</t></is></c>")
                        }
                        sheet.append("</row>")
                    }
                    sheet.append("</sheetData></worksheet>")
                    entry("xl/worksheets/sheet1.xml", sheet.toString())
                }
            }
            toast("فایل Excel در پوشه Downloads ذخیره شد.")
            openFile(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } catch (e: Exception) { toast("خطا در خروجی Excel: ${e.message}") }
    }

    private fun exportPdf() {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        val paint = Paint().apply { textSize = 14f; color = 0xFF202020.toInt(); textAlign = Paint.Align.RIGHT }
        var y = 42f
        canvas.drawText("گزارش موجودی انبار Scanner", 560f, y, paint); y += 28
        canvas.drawText("تاریخ تهیه: " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date()), 560f, y, paint); y += 30
        canvas.drawText("— خلاصه موجودی —", 560f, y, paint); y += 22
        db.productSummary().forEach { (name, count) ->
            canvas.drawText("$name : $count عدد موجود", 560f, y, paint); y += 20
        }
        y += 8
        canvas.drawText("— خلاصه خروجی —", 560f, y, paint); y += 22
        db.productOutSummary().forEach { (name, count) ->
            canvas.drawText("$name : $count عدد خارج‌شده", 560f, y, paint); y += 20
        }
        canvas.drawText("جمع کل خارج‌شده: ${db.totalOutCount()}", 560f, y, paint); y += 24
        canvas.drawText("فهرست سریال‌ها (موجود و خارج‌شده)", 560f, y, paint); y += 24
        db.serialRows().forEach { row ->
            if (y > 800) {
                doc.finishPage(page)
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                y = 42f
                canvas.drawText("ادامه فهرست سریال‌ها", 560f, y, paint); y += 28
            }
            canvas.drawText("${row[0]} | ${row[1]} | ${row[2]}", 560f, y, paint)
            y += 18
        }
        doc.finishPage(page)
        val name = "Scanner_report_${System.currentTimeMillis()}.pdf"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("امکان ساخت فایل وجود ندارد")
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            toast("گزارش PDF در پوشه Downloads ذخیره شد.")
            openFile(uri, "application/pdf")
        } catch (e: Exception) {
            doc.close()
            toast("خطا در PDF: ${e.message}")
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
