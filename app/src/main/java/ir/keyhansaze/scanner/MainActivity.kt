package ir.keyhansaze.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
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

    fun totalCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM serials WHERE status='IN'", null).use {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = InventoryDb(this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 10)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF6F7F4.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.scanner_logo)
            contentDescription = "لوگوی اپلیکیشن اسکنر"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(logo, LinearLayout.LayoutParams(76, 76).apply { gravity = Gravity.CENTER; bottomMargin = 4 })
        val title = TextView(this).apply {
            text = "اسکنر | مدیریت انبار"
            textSize = 24f
            setTextColor(0xFF34513A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }
        content.addView(title)
        countText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF1D3324.toInt())
            setPadding(8, 12, 8, 12)
            setBackgroundColor(0xFFE4EBDD.toInt())
        }
        content.addView(countText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        productSpinner = Spinner(this)
        productSpinner.minimumHeight = 204
        productSpinner.setPadding(18, 24, 18, 24)
        content.addView(productSpinner, LinearLayout.LayoutParams(-1, 204).apply { bottomMargin = 12 })
        modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("ورود به انبار", "خروج از انبار")).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modeSpinner.minimumHeight = 80
        modeSpinner.setPadding(16, 10, 16, 10)
        content.addView(modeSpinner, LinearLayout.LayoutParams(-1, 80).apply { bottomMargin = 14 })

        addButton(content, "＋ ثبت محصول", 0xFF526B3C.toInt()) { addProductDialog() }
        addButton(content, "− حذف محصول", 0xFF9B3D36.toInt()) { deleteProductDialog() }
        addButton(content, "＋ ثبت دستی سریال", 0xFF47627B.toInt()) { manualEntryDialog() }
        addButton(content, "⇩ خروجی Excel (XLSX)", 0xFF5C6B45.toInt()) { exportXlsx() }
        addButton(content, "▤ خروجی PDF", 0xFF8A6533.toInt()) { exportPdf() }
        addButton(content, "▦ جدول موجودی و گردش کالا", 0xFF315C73.toInt()) { showInventoryTable() }

        detailText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(4, 12, 4, 4)
            gravity = Gravity.RIGHT
        }
        content.addView(detailText, LinearLayout.LayoutParams(-1, 0, 1f))
        val note = TextView(this).apply {
            text = "اطلاعات فقط روی همین گوشی ذخیره می‌شود."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(0, 8, 0, 0)
        }
        content.addView(note)
        feedbackLight = TextView(this).apply {
            text = "آماده اسکن"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 5, 8, 5)
            setBackgroundColor(0xFF607D8B.toInt())
        }
        content.addView(feedbackLight, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 6; bottomMargin = 6 })
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton(root, "▦\nاسکن بارکد با دوربین", 0xFF176B63.toInt(), 204) { startScan() }
        setContentView(root)
    }

    private fun addButton(root: LinearLayout, label: String, color: Int, height: Int = 68, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = if (height >= 150) 23f else 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isAllCaps = false
            minHeight = height
            minimumHeight = height
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            setOnClickListener { action() }
        }
        root.addView(b, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = if (height >= 150) 0 else 10 })
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
                showRecordFeedback(db.record(product.id, input.text.toString(), isIncoming()))
                refresh()
            }.show()
    }

    private fun startScan() {
        if (selectedProduct() == null) return
        val integrator = IntentIntegrator(this)
        integrator.setCaptureActivity(CaptureActivity::class.java)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
        integrator.setPrompt(if (isIncoming()) "بارکد کالا را اسکن کنید - ورود" else "بارکد کالا را اسکن کنید - خروج")
        integrator.setBeepEnabled(true)
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
                if (p != null) showRecordFeedback(db.record(p.id, result.contents, isIncoming()))
                refresh()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun showRecordFeedback(message: String) {
        val success = message == "ورود کالا ثبت شد." || message == "خروج کالا ثبت شد."
        feedbackLight.text = if (success) "✓ ثبت موفق" else "✕ خطا: $message"
        feedbackLight.setBackgroundColor(if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        toneGenerator?.startTone(if (success) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 250)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({
            if (::feedbackLight.isInitialized) {
                feedbackLight.text = "آماده اسکن"
                feedbackLight.setBackgroundColor(0xFF607D8B.toInt())
            }
        }, 1000)
    }

    private fun refresh() {
        products = db.products()
        productSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            if (products.isEmpty()) listOf("ابتدا محصول اضافه کنید") else products.map { it.name }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        productSpinner.minimumHeight = 204
        productSpinner.setPadding(16, 20, 16, 20)
        productSpinner.layoutParams = LinearLayout.LayoutParams(-1, 204).apply { bottomMargin = 8 }
        countText.text = "موجودی کل: ${db.totalCount()} سریال"
        detailText.text = if (products.isEmpty()) {
            "برای شروع، نوع محصول را اضافه کنید.\n\nهر سریال فقط یک‌بار در موجودی ثبت می‌شود و ورود و خروج آن در تاریخچه ذخیره می‌شود."
        } else {
            products.joinToString("\n") { "• ${it.name}: ${db.stockCount(it.id)} عدد موجود" } +
                "\n\nتعداد سریال‌های ثبت‌شده: ${db.serialRows().size}\n\nوضعیت هر سریال در خروجی‌ها درج می‌شود."
        }
    }

    private fun exportXlsx() {
        val rows = db.serialRows()
        val summary = db.productSummary()
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
                    fun xmlEscape(s: String) = s.replace("<", "[").replace(">", "]")
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
        db.productSummary().forEach { (name, count) ->
            canvas.drawText("$name : $count عدد موجود", 560f, y, paint); y += 22
        }
        y += 10
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
