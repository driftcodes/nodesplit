package com.example

import android.content.Context
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.FinancialCalculator
import com.example.data.model.InputValidator
import com.example.data.model.LedgerArchive
import com.example.data.model.LedgerSummaryMetrics
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import com.example.data.model.UserProfile
import com.example.util.GraphicalPdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
// Real text measurement, so the PDF wrapping assertions mean something.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleRobolectricTest {

    @Test
    fun `T01_T07_T08_T09_T10 verify approval gate, FinancialCalculator, zero-rate archive, InputValidator, and PDF wrapping`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NodeSplit", appName)

        // T01: Verify exact status == APPROVED check on UserProfile
        assertFalse(UserProfile(status = "PENDING").isApproved)
        assertFalse(UserProfile(status = "REVOKED").isApproved)
        assertFalse(UserProfile(status = "").isApproved)
        assertTrue(UserProfile(status = "APPROVED").isApproved)

        // Verify Toman formatting and email sanitization
        assertEquals("240,000 Toman", TomanFormatter.format(240_000L))
        assertEquals("240,000 Toman", TomanFormatter.formatShort(240_000L))
        assertEquals("50 GB", TomanFormatter.formatGb(50.0))
        assertFalse(TomanFormatter.sanitizeOwnerDisplayName("Admin • admin@gmail.com").contains("@"))

        // T07: Verify shared FinancialCalculator across standard, fractional GB, New User bonus, negative profit, and custom split
        val standardCalc = FinancialCalculator.calculate(
            grossSalesToman = 135_000L,
            totalAllocatedGb = 25.0, // 20 GB + 5 GB bonus
            costPerGbToman = 1_200L,
            adminSharePercent = 50
        )
        assertEquals(30_000L, standardCalc.infrastructureCostToman)
        assertEquals(105_000L, standardCalc.netProfitToman)
        assertEquals(52_500L, standardCalc.adminProfitToman)
        assertEquals(52_500L, standardCalc.sellerProfitToman)
        assertEquals(82_500L, standardCalc.amountSellerPaysAdminToman)

        // Odd/fractional GB rounding consistency + custom 60/40 split + negative net profit
        val negativeCalc = FinancialCalculator.calculate(
            grossSalesToman = 10_000L,
            totalAllocatedGb = 12.5,
            costPerGbToman = 1_500L, // 12.5 * 1500 = 18,750
            adminSharePercent = 60
        )
        assertEquals(18_750L, negativeCalc.infrastructureCostToman)
        assertEquals(-8_750L, negativeCalc.netProfitToman)
        assertEquals(-5_250L, negativeCalc.adminProfitToman)
        assertEquals(-3_500L, negativeCalc.sellerProfitToman)
        assertEquals(13_500L, negativeCalc.amountSellerPaysAdminToman)

        // T08: Verify captured zero costPerGbToman == 0L is preserved when hasCapturedCostPerGb == true
        val oneItem = listOf<Map<String, Any>>(
            mapOf("itemId" to "s1", "customerName" to "Ali", "dataGb" to 50.0, "priceToman" to 200_000L, "isPaid" to false)
        )
        val zeroRateArchive = LedgerArchive(
            id = "arch_zero",
            totalSalesToman = 200_000L,
            totalGb = 50.0,
            entryCount = 1,
            items = oneItem,
            costPerGbToman = 0L,
            adminProfitPercent = 50,
            hasCapturedCostPerGb = true,
            hasCapturedAdminPercent = true
        )
        val zeroMetrics = zeroRateArchive.computeMetrics(fallbackCostPerGb = 2_000L, fallbackAdminPercent = 70)
        assertEquals(0L, zeroMetrics.costPerGbToman)
        assertEquals(0L, zeroMetrics.infraCostToman)
        assertEquals(100_000L, zeroMetrics.adminProfitToman)
        assertEquals(100_000L, zeroMetrics.sellerProfitToman)

        // Legacy archive without captured field falls back to workspace rate
        val legacyArchive = LedgerArchive(
            id = "arch_legacy",
            totalSalesToman = 200_000L,
            totalGb = 50.0,
            entryCount = 1,
            items = oneItem,
            costPerGbToman = 0L,
            adminProfitPercent = 50,
            hasCapturedCostPerGb = false,
            hasCapturedAdminPercent = false
        )
        val legacyMetrics = legacyArchive.computeMetrics(fallbackCostPerGb = 1_000L, fallbackAdminPercent = 50)
        assertEquals(1_000L, legacyMetrics.costPerGbToman)
        assertEquals(50_000L, legacyMetrics.infraCostToman)

        // T09: Verify InputValidator normalizes Persian/Arabic digits and rejects invalid inputs
        assertEquals(240_000L, InputValidator.validatePriceToman("۲۴۰,۰۰۰").getOrThrow())
        assertEquals(25.5, InputValidator.validateDataGb("٢٥.٥").getOrThrow(), 0.001)
        assertTrue(InputValidator.validateDataGb("0").isFailure)
        assertTrue(InputValidator.validateDataGb("abc").isFailure)
        assertTrue(InputValidator.validatePriceToman("-500").isFailure)
        assertTrue(InputValidator.validateAdminSharePercent("150").isFailure)

        // T10: Verify GraphicalPdfExporter wraps long text and generates valid PDF without truncation crash
        val paint = Paint().apply { textSize = 10f }
        val longCustomerName = "Mohammad Reza Ebrahimi-Fard (@mohammad_reza_vip_account_2026)"
        val wrappedLines = GraphicalPdfExporter.wrapTextToWidth(longCustomerName, paint, 120f)
        assertTrue("Long customer name should wrap into multiple lines", wrappedLines.size > 1)
        assertEquals(longCustomerName.replace(" ", ""), wrappedLines.joinToString("").replace(" ", ""))

        val sampleEntries = listOf(
            SaleEntry(
                id = "s1",
                customerName = longCustomerName,
                dataGb = 60.0,
                priceToman = 125_000_000L,
                originalPresetPriceToman = 130_000_000L,
                presetName = "50 GB Enterprise Multi-Region Plan",
                isPaid = true,
                note = "Long detailed customer note with discount and special server routing instructions",
                isNewUser = true,
                bonusGb = 10.0
            )
        )
        val payload = GraphicalPdfExporter.buildPayloadFromActiveLedger(
            bookTitle = "My private book",
            ownerName = "admin@gmail.com",
            entries = sampleEntries,
            costPerGbToman = 1_200L,
            adminProfitPercent = 50,
            isPrivateAdminBook = true
        )
        assertFalse(payload.ownerName.contains("@"))
        // Writing the actual PDF file needs Android's native PdfDocument, which Robolectric does not
        // implement; that part is covered by PdfExportInstrumentedTest on a device or emulator.
    }
}
