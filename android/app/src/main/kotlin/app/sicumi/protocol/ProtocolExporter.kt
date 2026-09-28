package app.sicumi.protocol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import app.sicumi.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Экспорт протокола в PDF и DOCX в формате «סיכום פגישה»: дата, הנדון, נוכחים, строка тем,
 * таблица מס"ד / נושא / הערות / אחריות / תאריך יעד (важные замечания выделены жёлтым),
 * решения, следующая встреча, העתק. Файлы кладутся в cacheDir/exports и отдаются через FileProvider.
 */
object ProtocolExporter {

    fun labels(context: Context) = ProtocolLabels(
        subject = context.getString(R.string.protocol_subject),
        participants = context.getString(R.string.protocol_participants),
        summary = context.getString(R.string.protocol_summary),
        number = context.getString(R.string.protocol_number),
        topic = context.getString(R.string.protocol_topic),
        notes = context.getString(R.string.protocol_notes),
        owner = context.getString(R.string.protocol_owner),
        due = context.getString(R.string.protocol_due),
        decisions = context.getString(R.string.protocol_decisions),
        tasks = context.getString(R.string.protocol_tasks),
        nextMeeting = context.getString(R.string.protocol_next_meeting),
        cc = context.getString(R.string.protocol_cc),
        until = context.getString(R.string.protocol_until),
    )

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun fileName(p: Protocol, ext: String): String {
        val base = p.title.ifBlank { p.subject }
        val safe = base.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60).ifBlank { "protocol" }
        return "$safe.$ext"
    }

    private fun topicsLine(p: Protocol, l: ProtocolLabels): String? =
        p.items.map { it.topic }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = "${l.topic} – ")

    // ---------------------------------------------------------------- PDF

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CELL_PAD = 5f
    private val INK = Color.rgb(0x17, 0x12, 0x3A)
    private val MUTED = Color.rgb(0x5B, 0x56, 0x80)
    private val GRID = Color.rgb(0x9A, 0x96, 0xB0)
    private val HEADER_BG = Color.rgb(0xE0, 0xDA, 0xFF)
    private val HIGHLIGHT = Color.rgb(0xFF, 0xF1, 0x76)

    /** Ширины колонок справа налево: מס"ד, נושא, הערות, אחריות, תאריך יעד (сумма = ширина страницы без полей). */
    private val COLS = floatArrayOf(32f, 72f, 260f, 115f, 36f)

    fun pdf(context: Context, protocol: Protocol): File {
        val file = File(exportDir(context), fileName(protocol, "pdf"))
        val l = labels(context)
        val res = context.resources
        val regular = res.getFont(R.font.heebo)
        val bold = if (Build.VERSION.SDK_INT >= 28) Typeface.create(regular, 700, false) else Typeface.create(regular, Typeface.BOLD)
        fun paint(face: Typeface, size: Float, color: Int = INK, underline: Boolean = false) =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textSize = size
                this.color = color
                isUnderlineText = underline
            }
        val body = paint(regular, 9.5f)
        val bodyBold = paint(bold, 9.5f)
        val gridPaint = Paint().apply {
            color = GRID
            strokeWidth = 0.6f
            style = Paint.Style.STROKE
        }
        val fillPaint = Paint().apply { style = Paint.Style.FILL }

        val doc = PdfDocument()
        val contentW = PAGE_W - 2 * MARGIN
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = MARGIN

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
        }

        fun layout(text: CharSequence, paint: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setLineSpacing(0f, 1.15f)
                .build()

        /** Абзац во всю ширину с переносом по страницам построчно. */
        fun paragraph(text: CharSequence, paint: TextPaint, after: Float = 3f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            val lay = layout(text, paint, contentW, align)
            for (line in 0 until lay.lineCount) {
                val top = lay.getLineTop(line).toFloat()
                val bottom = lay.getLineBottom(line).toFloat()
                if (y + (bottom - top) > PAGE_H - MARGIN) newPage()
                val c = canvas!!
                c.save()
                c.translate(MARGIN, y - top)
                c.clipRect(0f, top, contentW, bottom)
                lay.draw(c)
                c.restore()
                y += bottom - top
            }
            y += after
        }

        // x-координаты колонок: первая колонка — у правого края.
        val colRight = FloatArray(COLS.size)
        run {
            var right = PAGE_W - MARGIN
            COLS.forEachIndexed { i, w ->
                colRight[i] = right
                right -= w
            }
        }

        val headerCells: List<CharSequence> = listOf(l.number, l.topic, l.notes, l.owner, l.due)
        val headerPaints = List(5) { bodyBold }

        fun drawRow(cells: List<CharSequence>, paints: List<TextPaint>, header: Boolean) {
            val layouts = cells.mapIndexed { i, text ->
                val align = if (i == 0 || i == 4) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
                layout(text, paints[i], COLS[i] - 2 * CELL_PAD, align)
            }
            val h = layouts.maxOf { it.height } + 2 * CELL_PAD
            if (y + h > PAGE_H - MARGIN) {
                newPage()
                // Шапка таблицы повторяется на каждой новой странице.
                if (!header) drawRow(headerCells, headerPaints, header = true)
            }
            val c = canvas!!
            if (header) {
                fillPaint.color = HEADER_BG
                c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + h, fillPaint)
            }
            layouts.forEachIndexed { i, lay ->
                val left = colRight[i] - COLS[i]
                c.save()
                c.translate(left + CELL_PAD, y + CELL_PAD)
                lay.draw(c)
                c.restore()
                c.drawRect(left, y, colRight[i], y + h, gridPaint)
            }
            y += h
        }

        newPage()
        if (protocol.date.isNotBlank()) paragraph(protocol.date, body, after = 10f, align = Layout.Alignment.ALIGN_OPPOSITE)
        paragraph("${l.subject}: ${protocol.subject.ifBlank { protocol.title }}", paint(bold, 11.5f, underline = true), after = 10f)
        if (protocol.participants.isNotEmpty()) {
            paragraph("${l.participants}:", paint(bold, 10f, underline = true), after = 1f)
            protocol.participants.forEach { paragraph(Protocol.participantLine(it), body, after = 0.5f) }
            y += 8f
        }
        topicsLine(protocol, l)?.let { paragraph(it, body, after = 10f) }

        if (protocol.items.isNotEmpty()) {
            drawRow(headerCells, headerPaints, header = true)
            protocol.items.forEachIndexed { index, item ->
                val notes = SpannableStringBuilder()
                item.notes.forEachIndexed { i, note ->
                    if (i > 0) notes.append('\n')
                    val start = notes.length
                    notes.append("• ").append(note.text)
                    if (note.important) {
                        notes.setSpan(BackgroundColorSpan(HIGHLIGHT), start, notes.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                drawRow(
                    listOf((index + 1).toString(), item.topic, notes, item.owner, item.due),
                    listOf(body, body, body, body, bodyBold),
                    header = false,
                )
            }
            y += 12f
        }

        if (protocol.decisions.isNotEmpty()) {
            paragraph("${l.decisions}:", paint(bold, 10f, underline = true), after = 1f)
            protocol.decisions.forEach { paragraph("• ${it.text}", body, after = 1f) }
            y += 8f
        }
        if (protocol.nextMeeting.isNotBlank()) {
            paragraph("${l.nextMeeting}: ${protocol.nextMeeting}", bodyBold, after = 10f)
        }
        if (protocol.cc.isNotEmpty()) {
            paragraph("${l.cc}:", paint(bold, 10f, underline = true), after = 1f)
            protocol.cc.forEach { paragraph(it, bodyBold, after = 0.5f) }
        }

        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ---------------------------------------------------------------- DOCX

    /** Ширины колонок в twips (A4 минус поля 2 см = 9638), порядок как в PDF: справа налево. */
    private val DOCX_COLS = intArrayOf(600, 1350, 4860, 2150, 678)

    fun docx(context: Context, protocol: Protocol): File {
        val file = File(exportDir(context), fileName(protocol, "docx"))
        val l = labels(context)
        val body = StringBuilder()

        fun run(text: String, bold: Boolean = false, underline: Boolean = false, size: Int = 21, highlight: Boolean = false) = buildString {
            append("<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>")
            if (bold) append("<w:b/><w:bCs/>")
            if (underline) append("<w:u w:val=\"single\"/>")
            if (highlight) append("<w:highlight w:val=\"yellow\"/>")
            append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:rtl/></w:rPr>")
            append("<w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r>")
        }

        fun para(runs: String, after: Int = 60, jc: String? = null) = buildString {
            append("<w:p><w:pPr><w:bidi/><w:spacing w:before=\"0\" w:after=\"$after\"/>")
            if (jc != null) append("<w:jc w:val=\"$jc\"/>")
            append("</w:pPr>").append(runs).append("</w:p>")
        }

        // В абзаце с <w:bidi/> значение "right" — это логическое «начало» для Word; дату прижимаем к противоположному краю.
        if (protocol.date.isNotBlank()) body.append(para(run(protocol.date), after = 200, jc = "left"))
        body.append(para(run("${l.subject}: ${protocol.subject.ifBlank { protocol.title }}", bold = true, underline = true, size = 24), after = 200))
        if (protocol.participants.isNotEmpty()) {
            body.append(para(run("${l.participants}:", bold = true, underline = true), after = 20))
            protocol.participants.forEach { body.append(para(run(Protocol.participantLine(it)), after = 0)) }
            body.append(para("", after = 120))
        }
        topicsLine(protocol, l)?.let { body.append(para(run(it), after = 160)) }

        if (protocol.items.isNotEmpty()) {
            fun cell(width: Int, content: String, shade: Boolean = false) = buildString {
                append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
                if (shade) append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E0DAFF\"/>")
                append("</w:tcPr>").append(content.ifEmpty { para("") }).append("</w:tc>")
            }
            val grid = DOCX_COLS.joinToString("") { "<w:gridCol w:w=\"$it\"/>" }
            body.append(
                "<w:tbl><w:tblPr><w:bidiVisual/><w:tblW w:w=\"${DOCX_COLS.sum()}\" w:type=\"dxa\"/>" +
                    "<w:tblBorders>" +
                    listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
                        "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>"
                    } +
                    "</w:tblBorders><w:tblLayout w:type=\"fixed\"/>" +
                    "<w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar>" +
                    "</w:tblPr><w:tblGrid>$grid</w:tblGrid>",
            )
            val headers = listOf(l.number, l.topic, l.notes, l.owner, l.due)
            body.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
            headers.forEachIndexed { i, h -> body.append(cell(DOCX_COLS[i], para(run(h, bold = true), after = 0, jc = "center"), shade = true)) }
            body.append("</w:tr>")
            protocol.items.forEachIndexed { index, item ->
                body.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                body.append(cell(DOCX_COLS[0], para(run((index + 1).toString()), after = 0, jc = "center")))
                body.append(cell(DOCX_COLS[1], para(run(item.topic), after = 0)))
                val notes = item.notes.joinToString("") { n -> para(run("• " + n.text, highlight = n.important), after = 40) }
                body.append(cell(DOCX_COLS[2], notes))
                body.append(cell(DOCX_COLS[3], para(run(item.owner), after = 0)))
                body.append(cell(DOCX_COLS[4], para(run(item.due, bold = true), after = 0, jc = "center")))
                body.append("</w:tr>")
            }
            body.append("</w:tbl>")
            body.append(para("", after = 160))
        }

        if (protocol.decisions.isNotEmpty()) {
            body.append(para(run("${l.decisions}:", bold = true, underline = true), after = 20))
            protocol.decisions.forEach { body.append(para(run("• ${it.text}"), after = 20)) }
            body.append(para("", after = 100))
        }
        if (protocol.nextMeeting.isNotBlank()) {
            body.append(para(run("${l.nextMeeting}: ${protocol.nextMeeting}", bold = true), after = 160))
        }
        if (protocol.cc.isNotEmpty()) {
            body.append(para(run("${l.cc}:", bold = true, underline = true), after = 0))
            protocol.cc.forEach { body.append(para(run(it, bold = true), after = 0)) }
        }

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/><w:bidi/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes)
            entry("_rels/.rels", rels)
            entry("word/document.xml", document)
        }
        return file
    }

    /** XML-экранирование + удаление управляющих символов, недопустимых в XML 1.0. */
    private fun xml(s: String) = s
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
