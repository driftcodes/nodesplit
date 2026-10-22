package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.SaleEntry
import com.example.util.GraphicalPdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PDF-02 / T24: real PdfDocument output, including page breaks. Runs on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class PdfExportInstrumentedTest {

    @Test
    fun generatesMultiPagePdfWithEveryRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = (1..120).map { i ->
            SaleEntry(
                id = "s$i",
                customerName = if (i % 7 == 0) "محمد رضا ابراهیمی فرد (@long_customer_handle_$i)" else "Customer $i",
                dataGb = 115.0,
                bonusGb = if (i % 2 == 0) 15.0 else 0.0,
                isNewUser = i % 2 == 0,
                priceToman = 1_234_567_890L,
                originalPresetPriceToman = 1_300_000_000L,
                presetName = "100 GB Ultra",
                note = if (i % 5 == 0) "Long note about a referral discount and a second server" else ""
            )
        }
        val payload = GraphicalPdfExporter.buildPayloadFromActiveLedger(
            bookTitle = "My sales",
            ownerName = "Seller",
            entries = entries,
            costPerGbToman = 1_500L,
            adminProfitPercent = 50
        )
        assertEquals(120, payload.rows.size)

        val result = GraphicalPdfExporter.generatePdfFile(context, payload)
        assertTrue("PDF generation failed: ${result.exceptionOrNull()}", result.isSuccess)
        val file = result.getOrThrow()
        val bytes = file.readBytes()
        assertTrue(String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
        // 120 rows cannot fit on one A4 page, so there must be several /Type /Page objects.
        val pageCount = Regex("/Type\\s*/Page[^s]").findAll(String(bytes, Charsets.ISO_8859_1)).count()
        assertTrue("Expected several pages, got $pageCount", pageCount > 1)
        file.delete()
    }
}
