package com.hdlee73.englishstudy.docvoice.core

import android.content.Context
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

object PdfText {
    fun reader(context: Context): (ByteArray) -> String = { data ->
        try {
            PDDocument.load(data).use { doc ->
                if (doc.isEncrypted) throw ExtractException("암호가 걸린 PDF 는 읽을 수 없습니다.")
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        } catch (e: ExtractException) {
            throw e
        } catch (e: Exception) {
            throw ExtractException("PDF 를 읽지 못했습니다. (${e.message})")
        }
    }
}
