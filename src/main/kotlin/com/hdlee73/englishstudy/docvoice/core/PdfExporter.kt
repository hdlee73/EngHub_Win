package com.hdlee73.englishstudy.docvoice.core

import android.content.Context
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Color
import java.io.ByteArrayOutputStream

/** 받아쓰기 결과를 PDF 로 저장 (NanumGothic 내장 → 한글/영문 모두 깨지지 않음). */
object PdfExporter {
    private const val W = 595f
    private const val H = 842f
    private const val MARGIN = 56f

    /** [exportDetailed] 의 결과. 글꼴에 없어 [replacedChars] 개 만큼 글자를 `?` 로 바꿨다. */
    class PdfResult(val bytes: ByteArray, val replacedChars: Int)

    fun export(
        context: Context,
        paragraphs: List<Paragraph>,
        title: String,
        includeTime: Boolean,
        showSpeaker: Boolean,
    ): ByteArray = exportDetailed(context, paragraphs, title, includeTime, showSpeaker).bytes

    /**
     * PDF 를 만들어 바이트와 함께, 글꼴에 없어 `?` 로 대체한 글자 수를 돌려준다.
     * 대체한 글자가 있으면 호출한 쪽에서 사용자에게 알릴 수 있다.
     */
    fun exportDetailed(
        context: Context,
        paragraphs: List<Paragraph>,
        title: String,
        includeTime: Boolean,
        showSpeaker: Boolean,
    ): PdfResult {
        var replaced = 0
        PDDocument().use { doc ->
            val font = context.assets.open("fonts/NanumGothic-Regular.ttf").use { PDType0Font.load(doc, it) }
            val contentW = W - MARGIN * 2f
            val bodySize = 11f
            val smallSize = 8.5f
            val headSize = 18f
            val lineH = bodySize * 1.45f

            var page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
            var stream = PDPageContentStream(doc, page)
            var y = H - MARGIN

            fun newPage() {
                stream.close()
                page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
                stream = PDPageContentStream(doc, page)
                y = H - MARGIN
            }

            fun drawLineText(text: String, size: Float, color: Color, yBaseline: Float) {
                // 글꼴에 없는 글자는 버리지 않고 '?' 로 바꾸고 개수를 세어, 내용이 조용히 빠지지 않게 한다.
                val safe = StringBuilder(text.length)
                for (ch in text) {
                    if (ch < ' ') continue
                    if (runCatching { font.encode(ch.toString()) }.isSuccess) safe.append(ch)
                    else { safe.append('?'); replaced++ }
                }
                if (safe.isEmpty()) return
                stream.beginText()
                stream.setFont(font, size)
                stream.setNonStrokingColor(color)
                stream.newLineAtOffset(MARGIN, yBaseline)
                stream.showText(safe.toString())
                stream.endText()
            }

            fun wrapLines(text: String, size: Float): List<String> {
                val result = mutableListOf<String>()
                for (rawLine in text.split('\n')) {
                    if (rawLine.isEmpty()) {
                        result += ""
                        continue
                    }
                    val sb = StringBuilder()
                    for (ch in rawLine) {
                        val candidate = sb.toString() + ch
                        val width = runCatching { font.getStringWidth(candidate) / 1000f * size }.getOrDefault(candidate.length * size * 0.6f)
                        if (width > contentW && sb.isNotEmpty()) {
                            result += sb.toString()
                            sb.setLength(0)
                        }
                        sb.append(ch)
                    }
                    if (sb.isNotEmpty()) result += sb.toString()
                }
                return result
            }

            if (title.isNotEmpty()) {
                drawLineText(title, headSize, Color(0x6B, 0x5B, 0xB5), y - headSize)
                y -= headSize + 10f
                stream.setStrokingColor(Color(0xE6, 0xE1, 0xF5))
                stream.setLineWidth(1.2f)
                stream.moveTo(MARGIN, y)
                stream.lineTo(W - MARGIN, y)
                stream.stroke()
                y -= 18f
            }

            for (p in paragraphs) {
                val prefix = Exporter.prefixOf(p, includeTime, showSpeaker)
                if (prefix.isNotEmpty()) {
                    if (y - (smallSize * 1.4f + lineH) < MARGIN) newPage()
                    drawLineText(prefix, smallSize, Color(0x9A, 0x90, 0xC0), y - smallSize)
                    y -= smallSize * 1.4f + 2f
                }
                // One sentence per line (a long sentence still wraps).
                for (line in wrapLines(p.lines, bodySize)) {
                    if (y - lineH < MARGIN) newPage()
                    drawLineText(line, bodySize, Color(0x4A, 0x45, 0x60), y - bodySize)
                    y -= lineH
                }
                y -= 12f
            }

            stream.close()
            val out = ByteArrayOutputStream()
            doc.save(out)
            return PdfResult(out.toByteArray(), replaced)
        }
    }
}
