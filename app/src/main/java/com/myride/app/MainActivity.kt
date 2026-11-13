package com.myride.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: ReceiptDb
    private lateinit var root: LinearLayout
    private var currentTab = "Beranda"
    private var selectedAttachment = ""
    private val green = Color.rgb(0, 170, 91)
    private val ink = Color.rgb(28, 32, 36)
    private val muted = Color.rgb(111, 119, 126)
    private val nf = NumberFormat.getNumberInstance(Locale("id", "ID"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ReceiptDb(this)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        showHome()
    }

    private fun base(title: String, subtitle: String? = null) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246,247,248)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 18, 16, 14); setBackgroundColor(Color.WHITE) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        texts.addView(label(title, 23f, ink, true))
        if (subtitle != null) texts.addView(label(subtitle, 12f, muted, false))
        header.addView(texts)
        val more = TextView(this).apply { text = "⋮"; textSize = 28f; setTextColor(ink); gravity = Gravity.CENTER; setPadding(14,0,8,0); setOnClickListener { showMore() } }
        header.addView(more)
        root.addView(header)
        setContentView(root)
    }

    private fun label(s: String, size: Float, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun card(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,14,16,14); setBackgroundColor(Color.WHITE); elevation = 2f }
    private fun addGap(parent: LinearLayout, h: Int = 10) { parent.addView(View(this), LinearLayout.LayoutParams(1,h)) }
    private fun money(v: Long) = "Rp${nf.format(v)}"

    private fun showHome() {
        currentTab = "Beranda"; base("Halo! 👋", "Semua nota perjalanan dan pesanan dalam satu tempat")
        val scroll = ScrollView(this); val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,14,16,18) }
        val search = EditText(this).apply { hint = "Cari nota, lokasi, atau transaksi"; singleLine = true; textSize = 14f; setPadding(14,4,14,4); setBackgroundColor(Color.WHITE) }
        body.addView(search, LinearLayout.LayoutParams(-1,52)); addGap(body,14)
        body.addView(label("Layanan",18f,ink,true)); addGap(body,8)
        val services = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        services.addView(serviceTile("🚗","GoCar"), LinearLayout.LayoutParams(0,104,1f)); services.addView(serviceTile("🍜","GoFood"), LinearLayout.LayoutParams(0,104,1f)); services.addView(serviceTile("📦","GoSend"), LinearLayout.LayoutParams(0,104,1f)); services.addView(serviceTile("⋯","Lainnya"), LinearLayout.LayoutParams(0,104,1f))
        body.addView(services); addGap(body,16)
        body.addView(label("Aktivitas terakhir",18f,ink,true)); addGap(body,8)
        val recentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshRecent(q: String = "") {
            recentContainer.removeAllViews()
            val filtered = db.all().filter { listOf(it.service,it.date,it.origin,it.destination,it.driver).joinToString(" ").contains(q, true) }.take(8)
            if (filtered.isEmpty()) recentContainer.addView(emptyCard("Belum ada nota", "Pilih menu ⋮ untuk upload screenshot, foto, atau PDF nota."))
            else filtered.forEach { recentContainer.addView(receiptCard(it) { showDetail(it.id) }); addGap(recentContainer,8) }
        }
        refreshRecent()
        body.addView(recentContainer)
        search.addTextChangedListener(SimpleWatcher { refreshRecent(it) })
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); root.addView(bottomNav())
    }

    private fun serviceTile(emoji: String, name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(4,12,4,12)
        val e = label(emoji,27f); e.gravity = Gravity.CENTER; addView(e)
        val t = label(name,12f,ink,true); t.gravity = Gravity.CENTER; addView(t)
        setOnClickListener { showActivity(name) }
    }

    private fun emptyCard(title: String, desc: String) = card().apply { addView(label(title,16f,ink,true)); addGap(this,4); addView(label(desc,13f,muted)) }

    private fun receiptCard(r: Receipt, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14,13,14,13); setBackgroundColor(Color.WHITE); elevation = 1f
        val icon = label(if (r.service.contains("Food",true)) "🍜" else "🚗",25f); addView(icon)
        val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(12,0,4,0); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        info.addView(label(r.note.substringBefore(" • ID").ifBlank { r.service },15f,ink,true)); info.addView(label(r.service+" • "+r.date,11f,muted)); info.addView(label(if (r.origin.isNotBlank() || r.destination.isNotBlank()) "${r.origin} → ${r.destination}" else "Nota tersimpan",12f,muted))
        addView(info); addView(label(money(r.amount),14f,ink,true)); setOnClickListener { click() }
    }

    private fun showActivity(filter: String = "Semua") {
        currentTab = "Aktivitas"; base("Aktivitas", "Riwayat nota yang tersimpan di perangkat")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,12,16,12) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Semua","GoCar","GoFood","GoSend").forEach { c -> val b = Button(this).apply { text=c; textSize=11f; isAllCaps=false; setOnClickListener { showActivity(c) } }; chips.addView(b) }
        body.addView(chips)
        val search = EditText(this).apply { hint="Cari transaksi atau lokasi"; singleLine=true }
        body.addView(search)
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun refresh(q:String="") { list.removeAllViews(); val rows=db.all().filter { (filter=="Semua" || it.service.equals(filter,true)) && listOf(it.service,it.date,it.origin,it.destination,it.driver).joinToString(" ").contains(q,true) }; if(rows.isEmpty()) list.addView(emptyCard("Nota tidak ditemukan","Upload nota melalui menu titik tiga.")) else rows.forEach { list.addView(receiptCard(it){showDetail(it.id)}); addGap(list,8) } }
        refresh(); search.addTextChangedListener(SimpleWatcher { refresh(it) })
        val sc=ScrollView(this); sc.addView(list); body.addView(sc,LinearLayout.LayoutParams(-1,0,1f)); root.addView(body,LinearLayout.LayoutParams(-1,0,1f)); root.addView(bottomNav())
    }

    private fun showMap() {
        currentTab="Maps"; base("Maps", "Peta dan simulasi rute historis")
        val map = MapView(this); map.setTileSource(TileSourceFactory.MAPNIK); map.setMultiTouchControls(true)
        val start=GeoPoint(-6.2297,106.8295); val end=GeoPoint(-6.2146,106.8451)
        map.controller.setZoom(13.5); map.controller.setCenter(start)
        val line=Polyline().apply { setPoints(listOf(start,GeoPoint(-6.225,106.835),GeoPoint(-6.220,106.840),end)); outlinePaint.color=green; outlinePaint.strokeWidth=9f }
        map.overlays.add(line)
        val a=Marker(map).apply { position=start; title="Penjemputan"; setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM) }
        val b=Marker(map).apply { position=end; title="Tujuan"; setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM) }
        map.overlays.add(a); map.overlays.add(b)
        root.addView(map,LinearLayout.LayoutParams(-1,0,1f))
        val note=card(); note.addView(label("Contoh rute historis",15f,ink,true)); addGap(note,4); note.addView(label("Peta menggunakan OpenStreetMap. Rute contoh ini bukan lokasi GPS langsung.",12f,muted)); root.addView(note); root.addView(bottomNav())
    }

    private fun showDetail(id: Long) {
        val r=db.find(id) ?: return
        currentTab="Detail"; base("Detail nota",r.service+" • "+r.date)
        val sc=ScrollView(this); val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,14,16,18) }
        val total=card(); total.addView(label("Total pembayaran",13f,muted)); addGap(total,5); total.addView(label(money(r.amount),27f,ink,true)); body.addView(total); addGap(body,12)
        val route=card(); route.addView(label("Rute perjalanan",17f,ink,true)); addGap(route,8); route.addView(label("●  ${r.origin.ifBlank { "Lokasi penjemputan belum tersedia" }}",14f)); addGap(route,7); route.addView(label("●  ${r.destination.ifBlank { "Lokasi tujuan belum tersedia" }}",14f)); body.addView(route); addGap(body,12)
        val details=card(); details.addView(label("Informasi transaksi",17f,ink,true)); addGap(details,8)
        listOf("Layanan" to r.service,"Tanggal" to r.date,"Driver" to r.driver.ifBlank { "Belum tersedia" },"Nomor kendaraan" to r.plate.ifBlank { "Belum tersedia" },"Catatan" to r.note.ifBlank { "-" }).forEach { details.addView(label(it.first,11f,muted)); details.addView(label(it.second,14f,ink,true)); addGap(details,7) }
        body.addView(details); addGap(body,12)
        val map=MapView(this); map.setTileSource(TileSourceFactory.MAPNIK); map.setMultiTouchControls(true); val p1=GeoPoint(-6.2297,106.8295); val p2=GeoPoint(-6.2146,106.8451); map.controller.setZoom(13.5); map.controller.setCenter(p1); map.overlays.add(Polyline().apply { setPoints(listOf(p1,GeoPoint(-6.222,106.838),p2)); outlinePaint.color=green; outlinePaint.strokeWidth=8f }); body.addView(map,LinearLayout.LayoutParams(-1,420)); addGap(body,10)
        val download=Button(this).apply { text="Download nota PDF"; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(green); setOnClickListener { savePdf(r) } }; body.addView(download)
        sc.addView(body); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f)); root.addView(bottomNav())
    }

    private fun showMore() {
        val options=arrayOf("Upload foto / screenshot nota","Upload PDF nota","Batal")
        AlertDialog.Builder(this).setTitle("Menu lainnya").setItems(options) { _, which -> when(which) { 0 -> pickFile("image/*"); 1 -> pickFile("application/pdf") } }.show()
    }

    private fun pickFile(type: String) {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); this.type=type; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        startActivityForResult(i,1001)
    }

    @Deprecated("Legacy activity result used to keep compatibility with the simple Activity implementation")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 1002) { val uri=data?.data; val receipt=pendingPdf; if(uri!=null && receipt!=null) writePdf(uri,receipt); pendingPdf=null; return }
        if (requestCode != 1001) return
        val uri=data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
        selectedAttachment=uri.toString()
        val mime=contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) {
            try {
                val image=InputImage.fromFilePath(this,uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                    .addOnSuccessListener { showImportForm(uri,it.text) }
                    .addOnFailureListener { showImportForm(uri,"") }
            } catch (_:Exception) { showImportForm(uri,"") }
        } else if (mime == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) {
            readFirstPdfPage(uri) { text -> showImportForm(uri,text) }
        } else showImportForm(uri,"")
    }

    // Render only page 1 of a PDF, then run on-device ML Kit OCR. No upload to a server.
    private fun readFirstPdfPage(uri: Uri, done: (String) -> Unit) {
        Toast.makeText(this,"Membaca halaman pertama nota…",Toast.LENGTH_SHORT).show()
        Thread {
            var extracted=""
            try {
                val input=contentResolver.openFileDescriptor(uri,"r")
                if (input != null) {
                    val renderer=PdfRenderer(input)
                    if (renderer.pageCount > 0) {
                        val page=renderer.openPage(0)
                        val bitmap=android.graphics.Bitmap.createBitmap((page.width*2).coerceAtLeast(1000),(page.height*2).coerceAtLeast(1400),android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close(); renderer.close(); input.close()
                        val latch=java.util.concurrent.CountDownLatch(1)
                        try {
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                .process(InputImage.fromBitmap(bitmap,0))
                                .addOnSuccessListener { extracted=it.text; latch.countDown() }
                                .addOnFailureListener { latch.countDown() }
                            latch.await(30,java.util.concurrent.TimeUnit.SECONDS)
                        } finally { bitmap.recycle() }
                    } else { renderer.close(); input.close() }
                }
            } catch (_: Exception) { extracted="" }
            runOnUiThread { done(extracted) }
        }.start()
    }

    private fun first(pattern: String, text: String, group: Int = 0): String = try { Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(group)?.trim().orEmpty() } catch (_:Exception) { "" }
    private fun parseReceipt(text: String): Map<String,String> {
        val service=when { text.contains("GoCar",true)->"GoCar Comfort"; text.contains("GoRide",true)->"GoRide"; text.contains("GoFood",true)->"GoFood"; text.contains("GoSend",true)->"GoSend"; else->"Transaksi" }
        val id=first("""ID\s*pesanan\s*:\s*([A-Z0-9-]+)""",text,1)
        val date=first("""(?:Senin|Selasa|Rabu|Kamis|Jumat|Sabtu|Minggu),?\s*\d{1,2}\s+[A-Za-z]+\s+\d{4}""",text)
        val amountRaw=first("""Total\s*(?:dibayar|pembayaran)[^\n]*?Rp\s*([0-9.]+)""",text,1).ifBlank { first("""Rp\s*([0-9.]{4,})""",text,1) }
        val origin=first("""(?:dari|Dari)\s*\n?\s*([^\n]+)""",text,1).takeIf { it.length>2 } ?: ""
        val dest=first("""(?:di|tujuan)\s*\n?\s*([^\n]+)""",text,1).takeIf { it.length>2 } ?: ""
        val driver=first("""(?:Detail perjalanan\s*\n)?\s*([A-Z][a-z]+(?:\s+[A-Z][a-z]+){1,2})\s+\n?\s*[A-Z]""",text,1)
        val plate=first("""\b([A-Z]{1,2}\d{1,4}[A-Z]{1,3})\b""",text,1)
        return mapOf("service" to service,"id" to id,"date" to date,"amount" to amountRaw.filter { it.isDigit() },"origin" to origin,"destination" to dest,"driver" to driver,"plate" to plate)
    }

    private fun showImportForm(uri: Uri, ocr: String) {
        val parsed=parseReceipt(ocr)
        val autoTitle= listOf(parsed["origin"],parsed["destination"]).filter { !it.isNullOrBlank() }.joinToString(" ke ").ifBlank { parsed["service"] ?: "Nota transaksi" }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,0) }
        val title=EditText(this).apply { hint="Judul nota"; setText(autoTitle) }
        val service=EditText(this).apply { hint="Layanan"; setText(parsed["service"] ?: "Transaksi") }
        val date=EditText(this).apply { hint="Tanggal transaksi"; setText(parsed["date"] ?: "") }
        val amount=EditText(this).apply { hint="Nominal (angka saja)"; inputType=2; setText(parsed["amount"] ?: "") }
        val origin=EditText(this).apply { hint="Lokasi asal"; setText(parsed["origin"] ?: "") }
        val dest=EditText(this).apply { hint="Lokasi tujuan"; setText(parsed["destination"] ?: "") }
        val driver=EditText(this).apply { hint="Nama driver / merchant"; setText(parsed["driver"] ?: "") }
        val plate=EditText(this).apply { hint="Nomor kendaraan"; setText(parsed["plate"] ?: "") }
        listOf(title,service,date,amount,origin,dest,driver,plate).forEach { box.addView(it); addGap(box,3) }
        val summary=label(if(ocr.isNotBlank()) "Nota dibaca otomatis dari halaman pertama. Periksa hasilnya sebelum menyimpan." else "File terlampir. Teks tidak terbaca otomatis; isi bagian yang kosong.",12f,muted)
        box.addView(summary)
        AlertDialog.Builder(this).setTitle("Hasil pembacaan nota").setMessage(if(ocr.isNotBlank()) "Pembacaan otomatis selesai" else "Tidak ditemukan teks yang dapat dibaca")
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Batal",null)
            .setPositiveButton("Simpan nota",null).create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val amt=amount.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val generatedNote=title.text.toString().ifBlank { "Nota transaksi" } + if(parsed["id"].orEmpty().isNotBlank()) " • ID ${parsed["id"]}" else ""
                    db.add(service.text.toString().ifBlank{"Transaksi"},date.text.toString(),amt,origin.text.toString(),dest.text.toString(),driver.text.toString(),plate.text.toString(),generatedNote,uri.toString())
                    dialog.dismiss(); Toast.makeText(this,"Nota berhasil disimpan",Toast.LENGTH_SHORT).show(); showActivity()
                } }
                dialog.show()
            }
    }

    private fun savePdf(r:Receipt) {
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/pdf"; putExtra(Intent.EXTRA_TITLE,"${r.note.substringAfter("ID ", r.id.toString()).takeWhile { it.isLetterOrDigit() || it == '-' }.ifBlank { r.id.toString() }}.pdf") }
        pendingPdf=r; startActivityForResult(intent,1002)
    }
    private var pendingPdf:Receipt?=null
    private fun writePdf(uri:Uri,r:Receipt) {
        val doc=PdfDocument(); val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create()); val p=Paint().apply { color=ink; textSize=18f; isFakeBoldText=true }
        page.canvas.drawText("MY RIDE - DETAIL NOTA",40f,55f,p); p.textSize=13f; p.isFakeBoldText=false
        val lines=listOf("Layanan: ${r.service}","Tanggal: ${r.date}","Total: ${money(r.amount)}","Asal: ${r.origin}","Tujuan: ${r.destination}","Driver / Merchant: ${r.driver}","Kendaraan: ${r.plate}","Catatan: ${r.note}","","Dokumen ini dibuat dari data yang tersimpan di My Ride.")
        var y=100f; lines.forEach { page.canvas.drawText(it.take(85),40f,y,p); y+=28f }; doc.finishPage(page)
        try { contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }; Toast.makeText(this,"PDF berhasil disimpan",Toast.LENGTH_LONG).show() } catch(e:Exception) { Toast.makeText(this,"Gagal menyimpan PDF: ${e.message}",Toast.LENGTH_LONG).show() } finally { doc.close() }
    }

    private fun bottomNav(): LinearLayout = LinearLayout(this).apply {
        setBackgroundColor(Color.WHITE); gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL
        listOf("⌂\nBeranda","▤\nAktivitas","⌖\nMaps","♙\nProfil").forEach { item -> val parts=item.split("\n"); val t=TextView(this@MainActivity).apply { text=item; gravity=Gravity.CENTER; textSize=12f; setTextColor(if(parts[1]==currentTab) green else muted); setPadding(4,10,4,10); layoutParams=LinearLayout.LayoutParams(0,58,1f); setOnClickListener { when(parts[1]) { "Beranda"->showHome(); "Aktivitas"->showActivity(); "Maps"->showMap(); else->showProfile() } } }; addView(t) }
    }
    private fun showProfile() { currentTab="Profil"; base("Profil", "Pengaturan aplikasi"); val b=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,18,18,18) }; b.addView(emptyCard("My Ride • Versi 1.0.0","Aplikasi penyimpanan dan pembaca nota lokal. Tidak terhubung dengan akun Gojek dan tidak dapat melakukan pemesanan atau pembayaran.")); root.addView(b,LinearLayout.LayoutParams(-1,0,1f)); root.addView(bottomNav()) }

    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }
}

private class SimpleWatcher(val changed:(String)->Unit): android.text.TextWatcher {
    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s?.toString() ?: "") }
    override fun afterTextChanged(s:android.text.Editable?) {}
}
