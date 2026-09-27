package app.sicumi.protocol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import app.sicumi.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Экспорт протокола в PDF (PdfDocument + StaticLayout, RTL) и DOCX (минимальный OOXML вручную).
 * Файлы кладутся в cacheDir/exports и отдаются через FileProvider.
 */
object ProtocolExporter {

    private enum class Kind { Title, Meta, Heading, Body, Bullet, Task }
    private data class Block(val kind: Kind, val text: String)

    fun labels(context: Context) = ProtocolLabels(
        participants = context.getString(R.string.protocol_participants),
        summary = context.getString(R.string.protocol_summary),
        decisions = context.getString(R.string.protocol_decisions),
        tasks = context.getString(R.string.protocol_tasks),
        openIssues = context.getString(R.string.protocol_open_issues),
        nextMeeting = context.getString(R.string.protocol_next_meeting),
        until = context.getString(R.string.protocol_until),
    )

    private fun blocks(p: Protocol, l: ProtocolLabels): List<Block> = buildList {
        add(Block(Kind.Title, p.title))
        val meta = listOfNotNull(
            p.date.takeIf { it.isNotBlank() },
            p.participants.takeIf { it.isNotEmpty() }?.joinToString(", ") {
                if (it.role.isBlank()) it.name else "${it.name} (${it.role})"
            }?.let { "${l.participants}: $it" },
        )
        meta.forEach { add(Block(Kind.Meta, it)) }
        if (p.summary.isNotBlank()) {
            add(Block(Kind.Heading, l.summary))
            add(Block(Kind.Body, p.summary))
        }
        p.topics.forEach { t ->
            add(Block(Kind.Heading, t.title))
            t.points.forEach { add(Block(Kind.Bullet, it)) }
        }
        if (p.decisions.isNotEmpty()) {
            add(Block(Kind.Heading, l.decisions))
            p.decisions.forEach { add(Block(Kind.Bullet, it.text)) }
        }
        if (p.actionItems.isNotEmpty()) {
            add(Block(Kind.Heading, l.tasks))
            p.actionItems.forEach { a ->
                val meta = listOf(a.owner, a.due.takeIf { it.isNotBlank() }?.let { "${l.until} $it" })
                    .filter { !it.isNullOrBlank() }.joinToString(", ")
                add(Block(Kind.Task, (if (a.done) "☑ " else "☐ ") + a.task + if (meta.isNotEmpty()) " — $meta" else ""))
            }
        }
        if (p.openIssues.isNotEmpty()) {
            add(Block(Kind.Heading, l.openIssues))
            p.openIssues.forEach { add(Block(Kind.Bullet, it)) }
        }
        if (p.nextMeeting.isNotBlank()) {
            add(Block(Kind.Heading, l.nextMeeting))
            add(Block(Kind.Body, p.nextMeeting))
        }
    }

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun fileName(title: String, ext: String): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60).ifBlank { "protocol" }
        return "$safe.$ext"
    }

    // --- PDF ---

    fun pdf(context: Context, protocol: Protocol): File {
        val file = File(exportDir(context), fileName(protocol.title, "pdf"))
        val res = context.resources
        val heebo = res.getFont(R.font.heebo)
        val heeboBold = if (Build.VERSION.SDK_INT >= 28) Typeface.create(heebo, 700, false) else Typeface.create(heebo, Typeface.BOLD)
        val secular = res.getFont(R.font.secular_one)
        fun paint(face: Typeface, size: Float, color: Int) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
        }
        val ink = Color.rgb(0x17, 0x12, 0x3A)
        val muted = Color.rgb(0x5B, 0x56, 0x80)
        val violet = Color.rgb(0x4B, 0x2F, 0xD6)

        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 48f
        val width = (pageW - 2 * margin).toInt()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            page = p
            canvas = p.canvas
            y = margin
        }

        fun draw(layout: StaticLayout, indent: Float) {
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(line).toFloat()
                val h = bottom - top
                if (y + h > pageH - margin) newPage()
                val c = canvas!!
                c.save()
                c.translate(margin, y - top)
                c.clipRect(0f, top, width.toFloat(), bottom)
                layout.draw(c)
                c.restore()
                y += h
            }
            y += indent
        }

        fun layout(text: String, paint: TextPaint, w: Int = width, spacing: Float = 1.25f) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setLineSpacing(0f, spacing)
                .build()

        newPage()
        blocks(protocol, labels(context)).forEach { b ->
            when (b.kind) {
                Kind.Title -> draw(layout(b.text, paint(secular, 24f, ink)), 6f)
                Kind.Meta -> draw(layout(b.text, paint(heebo, 11f, muted)), 2f)
                Kind.Heading -> {
                    y += 12f
                    // Заголовок не оставляем последней строкой страницы.
                    if (y + 60f > pageH - margin) newPage()
                    draw(layout(b.text, paint(secular, 15f, violet)), 4f)
                }
                Kind.Body -> draw(layout(b.text, paint(heebo, 11.5f, ink)), 4f)
                Kind.Bullet -> draw(layout("• " + b.text, paint(heebo, 11.5f, ink)), 3f)
                Kind.Task -> draw(layout(b.text, paint(heeboBold, 11.5f, ink)), 3f)
            }
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // --- DOCX ---

    fun docx(context: Context, protocol: Protocol): File {
        val file = File(exportDir(context), fileName(protocol.title, "docx"))
        val body = StringBuilder()
        blocks(protocol, labels(context)).forEach { b ->
            val (size, bold, color, before) = when (b.kind) {
                Kind.Title -> Style(40, true, "17123A", 0)
                Kind.Meta -> Style(20, false, "5B5680", 0)
                Kind.Heading -> Style(28, true, "4B2FD6", 240)
                Kind.Body -> Style(22, false, "17123A", 0)
                Kind.Bullet -> Style(22, false, "17123A", 0)
                Kind.Task -> Style(22, true, "17123A", 0)
            }
            val text = if (b.kind == Kind.Bullet) "• ${b.text}" else b.text
            body.append("<w:p><w:pPr><w:bidi/><w:spacing w:before=\"$before\" w:after=\"80\"/></w:pPr>")
            body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>")
            if (bold) body.append("<w:b/><w:bCs/>")
            body.append("<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:rtl/></w:rPr>")
            body.append("<w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>")
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

    private data class Style(val size: Int, val bold: Boolean, val color: String, val before: Int)

    private fun xml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
