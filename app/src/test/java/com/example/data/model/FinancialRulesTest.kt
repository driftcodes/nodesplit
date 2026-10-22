package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the shared money contract (MONEY-01/02), snapshot re-derivation (SEC-05)
 * and invitation keys (SEC-02). No Android or Firebase runtime is needed.
 */
class FinancialRulesTest {

    private fun item(id: String, gb: Double, price: Long, bonus: Double = 0.0, paid: Boolean = false): Map<String, Any> =
        mapOf(
            "itemId" to id,
            "customerName" to "Customer $id",
            "dataGb" to gb,
            "bonusGb" to bonus,
            "isNewUser" to (bonus > 0.0),
            "priceToman" to price,
            "isPaid" to paid
        )

    private fun assertInvariants(b: FinancialBreakdown) {
        assertEquals(b.grossSalesToman, b.infrastructureCostToman + b.sellerProfitToman + b.adminProfitToman)
        assertEquals(b.amountSellerPaysAdminToman, b.infrastructureCostToman + b.adminProfitToman)
    }

    @Test
    fun screenshotFixtureA_activeDashboard() {
        val b = FinancialCalculator.calculate(375_000L, 75.0, 1_500L, 50)
        assertEquals(112_500L, b.infrastructureCostToman)
        assertEquals(131_250L, b.sellerProfitToman)
        assertEquals(131_250L, b.adminProfitToman)
        assertEquals(243_750L, b.amountSellerPaysAdminToman)
        assertInvariants(b)
    }

    @Test
    fun screenshotFixtureB_archiveKeepsItsCapturedRate() {
        val archive = LedgerArchive(
            id = "arch_b",
            totalSalesToman = 555_000L,
            totalGb = 125.0,
            costPerGbToman = 1_000L,
            adminProfitPercent = 50,
            entryCount = 2,
            items = listOf(item("a", 75.0, 340_000L), item("b", 50.0, 215_000L))
        )
        // Today's rate (1,500) must not reprice the archive captured at 1,000.
        val m = archive.computeMetrics(fallbackCostPerGb = 1_500L, fallbackAdminPercent = 50)
        assertEquals(555_000L, m.totalSalesToman)
        assertEquals(2, m.salesCount)
        assertEquals(125.0, m.totalGb, 0.0001)
        assertEquals(125_000L, m.infraCostToman)
        assertEquals(215_000L, m.sellerProfitToman)
        assertEquals(215_000L, m.adminProfitToman)
        assertEquals(340_000L, m.sellerPayableToAdminToman)
        assertFalse(m.hasTotalsMismatch)
        assertFalse(m.hasLegacyAssumedRates)
    }

    @Test
    fun oddAmountsFractionalGbAndLosses_reconcile() {
        listOf(
            FinancialCalculator.calculate(100_001L, 10.0, 1_000L, 50),
            FinancialCalculator.calculate(99_999L, 12.3, 1_111L, 33),
            FinancialCalculator.calculate(10_000L, 12.5, 1_500L, 60),
            FinancialCalculator.calculate(0L, 5.0, 1_000L, 50),
            FinancialCalculator.calculate(500_000L, 50.0, 0L, 50),
            FinancialCalculator.calculate(500_000L, 50.0, 1_000L, 0),
            FinancialCalculator.calculate(500_000L, 50.0, 1_000L, 100)
        ).forEach(::assertInvariants)

        val loss = FinancialCalculator.calculate(10_000L, 12.5, 1_500L, 60)
        assertEquals(18_750L, loss.infrastructureCostToman)
        assertEquals(-8_750L, loss.netProfitToman)
        assertEquals(-5_250L, loss.adminProfitToman)
        assertEquals(-3_500L, loss.sellerProfitToman)

        // 12.3 GB × 1,111 = 13,665.3 → 13,665 (half-up, whole Toman)
        assertEquals(13_665L, FinancialCalculator.calculate(99_999L, 12.3, 1_111L, 33).infrastructureCostToman)
    }

    @Test
    fun zeroAndFullShares() {
        val adminNone = FinancialCalculator.calculate(500_000L, 50.0, 1_000L, 0)
        assertEquals(0L, adminNone.adminProfitToman)
        assertEquals(450_000L, adminNone.sellerProfitToman)
        assertEquals(50_000L, adminNone.amountSellerPaysAdminToman)
        val adminAll = FinancialCalculator.calculate(500_000L, 50.0, 1_000L, 100)
        assertEquals(450_000L, adminAll.adminProfitToman)
        assertEquals(0L, adminAll.sellerProfitToman)
    }

    @Test
    fun bonusIsCountedOnceAndPaidStateNeverChangesMoney() {
        // dataGb already holds the total allocation (50 base + 10 bonus).
        val unpaid = SaleEntry(id = "s1", customerName = "Kian", dataGb = 60.0, bonusGb = 10.0, isNewUser = true, priceToman = 210_000L)
        assertEquals(50.0, unpaid.baseDataGb, 0.0001)
        val paid = unpaid.copy(isPaid = true)
        val a = FinancialCalculator.computeActiveMetrics(listOf(unpaid), 1_500L, 50)
        val b = FinancialCalculator.computeActiveMetrics(listOf(paid), 1_500L, 50)
        assertEquals(60.0, a.totalGb, 0.0001)
        assertEquals(90_000L, a.infraCostToman)
        assertEquals(a.sellerPayableToAdminToman, b.sellerPayableToAdminToman)
        assertEquals(a.sellerProfitToman, b.sellerProfitToman)
        assertEquals(150_000L, a.sellerPayableToAdminToman)
    }

    @Test
    fun capturedZeroRateIsKept_missingRateFallsBack() {
        val items = listOf(item("a", 50.0, 200_000L))
        val zero = LedgerArchive(id = "z", totalSalesToman = 200_000L, totalGb = 50.0, entryCount = 1, items = items, costPerGbToman = 0L)
        assertEquals(0L, zero.computeMetrics(2_000L, 70).infraCostToman)
        val legacy = zero.copy(hasCapturedCostPerGb = false, hasCapturedAdminPercent = false)
        val m = legacy.computeMetrics(1_000L, 50)
        assertEquals(50_000L, m.infraCostToman)
        assertTrue(m.hasLegacyAssumedRates)
    }

    @Test
    fun storedTotalsThatDisagreeWithItemsAreRecomputedAndFlagged() {
        val tampered = LedgerArchive(
            id = "t",
            totalSalesToman = 1_000L, // items really add up to 300,000
            totalGb = 1.0,
            entryCount = 2,
            items = listOf(item("a", 10.0, 100_000L), item("b", 20.0, 200_000L))
        )
        val m = tampered.computeMetrics(1_000L, 50)
        assertEquals(300_000L, m.totalSalesToman)
        assertEquals(30.0, m.totalGb, 0.0001)
        assertTrue(m.hasTotalsMismatch)
    }

    @Test
    fun malformedItemsAreSkippedNotCrashing() {
        val broken = listOf(
            item("ok", 10.0, 50_000L),
            mapOf<String, Any>("itemId" to "nan", "customerName" to "X", "dataGb" to Double.NaN, "priceToman" to 1L, "isPaid" to false),
            mapOf<String, Any>("itemId" to "neg", "customerName" to "Y", "dataGb" to 5.0, "priceToman" to -5L, "isPaid" to false)
        )
        val parsed = broken.mapIndexedNotNull { i, m -> ArchivedSaleItem.fromMap(m, i) }
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed.single().itemId)
    }

    @Test
    fun paidMapOverridesPerItem() {
        val archive = LedgerArchive(
            id = "p",
            entryCount = 2,
            items = listOf(item("a", 10.0, 1_000L), item("b", 10.0, 2_000L)),
            paidMap = mapOf("b" to true)
        )
        val parsed = archive.parsedItems()
        assertFalse(parsed.first { it.itemId == "a" }.isPaid)
        assertTrue(parsed.first { it.itemId == "b" }.isPaid)
    }

    @Test
    fun inputValidation_rejectsInsteadOfCoercing() {
        assertTrue(InputValidator.validatePriceToman("").isFailure)
        assertTrue(InputValidator.validatePriceToman("12a").isFailure)
        assertTrue(InputValidator.validatePriceToman("-1").isFailure)
        assertTrue(InputValidator.validatePriceToman("99999999999999").isFailure)
        assertEquals(0L, InputValidator.validatePriceToman("0").getOrThrow())
        assertEquals(240_000L, InputValidator.validatePriceToman("۲۴۰,۰۰۰").getOrThrow())
        assertEquals(25.5, InputValidator.validateDataGb("٢٥٫٥").getOrThrow(), 0.0001)
        assertTrue(InputValidator.validateDataGb("1.2.3").isFailure)
        assertTrue(InputValidator.validateDataGb("0").isFailure)
        assertTrue(InputValidator.validateDataGb("200000").isFailure)
        assertTrue(InputValidator.validateAdminSharePercent("101").isFailure)
        assertEquals(0, InputValidator.validateAdminSharePercent("0").getOrThrow())
        assertEquals(100, InputValidator.validateAdminSharePercent("100").getOrThrow())
    }

    @Test
    fun invitationKeys_noLongerCollide() {
        // The old scheme mapped these to the same document.
        assertEquals(TomanFormatter.legacyEmailToSafeKey("a.b@x.com"), TomanFormatter.legacyEmailToSafeKey("a_dot_b@x.com"))
        assertNotEquals(TomanFormatter.invitationKeyFor("a.b@x.com"), TomanFormatter.invitationKeyFor("a_dot_b@x.com"))
        assertNotEquals(TomanFormatter.invitationKeyFor("a+b@x.com"), TomanFormatter.invitationKeyFor("a_b@x.com"))
        assertEquals("seller.one@gmail.com", TomanFormatter.invitationKeyFor("  Seller.One@Gmail.com "))
        assertNull(TomanFormatter.invitationKeyFor("not an email"))
        assertNull(TomanFormatter.invitationKeyFor("a/b@x.com"))
        // Old keys are still looked up so existing invitations keep working until migrated.
        val candidates = TomanFormatter.candidateInvitationKeys("a.b@x.com")
        assertEquals("a.b@x.com", candidates.first())
        assertTrue("a_dot_b_at_x_dot_com" in candidates)
    }

    @Test
    fun approvalIsExact() {
        assertFalse(UserProfile(status = "PENDING").isApproved)
        assertFalse(UserProfile(status = "REVOKED").isApproved)
        assertFalse(UserProfile(status = "").isApproved)
        assertFalse(UserProfile(role = "ADMIN", status = "PENDING").isAdmin)
        assertTrue(UserProfile(status = "APPROVED").isApproved)
    }
}
