package dev.swart.inklab.core.importing

import android.content.Context
import android.net.Uri
import dev.swart.inklab.core.export.PageRange
import dev.swart.inklab.core.model.InkBoard

/** Range-aware PDF import. The immutable original asset is still stored once by DocumentImportService. */
class PdfRangeImportService(private val context: Context) {
    fun import(uri: Uri, pageRange: String, folderId: String? = null): InkBoard {
        val complete = DocumentImportService(context).import(uri, ImportDocumentKind.PDF, folderId)
        val indices = PageRange.parse(pageRange, complete.pages.size)
        return complete.copy(
            pages = indices.map(complete.pages::get),
            lastPageIndex = 0
        )
    }
}
