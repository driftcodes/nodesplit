package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.LedgerArchive
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun bootstrapWorkspace_addSale_togglePaid_andResetArchive_succeedsForAdminAndSeller() = runBlocking {
        // 1. The owner has created config/workspace in the console; the admin signs in
        clearEmulatorData()
        val adminUid = signInTestUser(ADMIN_EMAIL)
        seedWorkspace(adminUid)
        val repo = LedgerRepository(firestore, auth)

        val bootstrapResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(adminUid, ADMIN_EMAIL, "Admin Boss")
        }
        assertTrue("Admin bootstrap should succeed", bootstrapResult.isSuccess)
        val (workspace, adminProfile) = bootstrapResult.getOrThrow()
        assertEquals(adminUid, workspace.adminUid)
        assertEquals("ADMIN", adminProfile.role)
        assertEquals("APPROVED", adminProfile.status)

        // 2. Admin updates workspace Cost-per-GB rate and profit split
        val updateRatesResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateWorkspaceRates(costPerGbToman = 1_500L, adminProfitPercent = 50)
        }
        assertTrue("Admin updating workspace rates should succeed", updateRatesResult.isSuccess)

        // 3. Admin pre-approves Seller A's Google email
        val approveEmailResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addOrApproveSellerEmail(adminUid, SELLER_A_EMAIL, "Seller Alpha")
        }
        assertTrue("Approving Seller A email should succeed", approveEmailResult.isSuccess)

        // 4. Seller A signs in and is automatically APPROVED via pre-approved email
        val sellerAUid = signInTestUser(SELLER_A_EMAIL)
        val sellerBootstrap = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(sellerAUid, SELLER_A_EMAIL, "Seller Alpha")
        }
        assertTrue("Seller A bootstrap should succeed", sellerBootstrap.isSuccess)
        val sellerProfile = sellerBootstrap.getOrThrow().second
        assertEquals("SELLER", sellerProfile.role)
        assertEquals("APPROVED", sellerProfile.status)

        // Ensure clean slate of active entries before recording the test sale
        withTimeout(DEFAULT_TIMEOUT_MS) {
            val leftover = repo.getLedgerEntries(sellerAUid, adminUid).getOrDefault(emptyList())
            for (item in leftover) {
                repo.deleteSaleEntry(sellerAUid, item.id)
            }
        }

        // 5. Seller A adds a customer purchase in Toman with New User bonus GB (50 GB base + 10 GB bonus = 60 GB total allocated)
        val addSaleResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addSaleEntry(
                ledgerOwnerUid = sellerAUid,
                workspaceAdminUid = adminUid,
                customerName = "Kian M.",
                dataGb = 60.0,
                priceToman = 210_000L,
                originalPresetPriceToman = 240_000L,
                presetName = "50 GB Pro",
                isPaid = false,
                note = "30k loyalty discount",
                isNewUser = true,
                bonusGb = 10.0
            )
        }
        assertTrue("Seller A adding sale should succeed", addSaleResult.isSuccess)
        val saleId = addSaleResult.getOrThrow()

        // 6. Seller A observes own entries and toggles paid status
        val initialEntries = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLedgerEntries(sellerAUid, adminUid).first { list -> list.any { it.id == saleId } }
        }
        val addedEntry = initialEntries.first { it.id == saleId }
        assertFalse(addedEntry.isPaid)
        assertEquals(210_000L, addedEntry.priceToman)
        assertTrue(addedEntry.isNewUser)
        assertEquals(60.0, addedEntry.dataGb, 0.001)
        assertEquals(50.0, addedEntry.baseDataGb, 0.001)
        assertEquals(10.0, addedEntry.bonusGb, 0.001)

        val toggleResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.toggleSalePaidStatus(sellerAUid, saleId, isPaid = true)
        }
        assertTrue(toggleResult.isSuccess)

        val updatedEntries = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLedgerEntries(sellerAUid, adminUid).first { list ->
                list.any { it.id == saleId && it.isPaid }
            }
        }
        assertTrue(updatedEntries.first { it.id == saleId }.isPaid)

        // 7. Seller A resets and archives ledger with captured settlement rates
        val resetResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.resetAndArchiveLedger(
                ledgerOwnerUid = sellerAUid,
                workspaceAdminUid = adminUid,
                ownerName = "Seller Alpha",
                cycleTitle = "October Settlement",
                activeEntries = updatedEntries,
                keepArchive = true,
                costPerGbToman = 1_500L,
                adminProfitPercent = 50
            )
        }
        assertTrue(resetResult.isSuccess)
        val archiveId = resetResult.getOrThrow()
        assertNotNull(archiveId)

        // Verify active entries are wiped and archive contains the snapshot (50 base + 10 bonus = 60 GB total)
        val afterResetEntries = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getLedgerEntries(sellerAUid, adminUid).getOrThrow()
        }
        assertTrue(afterResetEntries.isEmpty())

        val archives = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLedgerArchives(sellerAUid, adminUid).first { list -> list.any { it.id == archiveId } }
        }
        val createdArchive = archives.first { it.id == archiveId }
        assertEquals(210_000L, createdArchive.totalSalesToman)
        assertEquals(60.0, createdArchive.totalDataGb, 0.001)
        assertEquals(1, createdArchive.entryCount)
        val archiveMetrics = createdArchive.computeMetrics()
        assertEquals(90_000L, archiveMetrics.infraCostToman)
        assertEquals(120_000L, archiveMetrics.netProfitToman)
        assertEquals(60_000L, archiveMetrics.adminProfitToman)
        assertEquals(60_000L, archiveMetrics.sellerProfitToman)
        assertEquals(150_000L, archiveMetrics.sellerPayableToAdminToman)

        // 8. Seller A ticks one payment checkbox inside the archive (narrow paidMap update)
        val archivedItemId = createdArchive.parsedItems().single().itemId
        val archiveToggleResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateSnapshotItemPaidStatus(
                ledgerOwnerUid = sellerAUid,
                collection = LedgerArchive.COLLECTION_ARCHIVES,
                snapshotId = archiveId!!,
                itemId = archivedItemId,
                isPaid = false
            )
        }
        assertTrue("Updating one archive checkbox should succeed", archiveToggleResult.isSuccess)

        // 9. The archive can be voided with a reason, never deleted
        val voidResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.voidArchive(sellerAUid, archiveId!!, "Entered twice")
        }
        assertTrue("Voiding the archive should succeed", voidResult.isSuccess)
    }

    @Test
    fun sendReport_createsImmutableHistory_andNewCycleClearsCurrentReport() = runBlocking {
        clearEmulatorData()
        val adminUid = signInTestUser(ADMIN_EMAIL)
        seedWorkspace(adminUid)
        val repo = LedgerRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) { repo.getOrBootstrapWorkspaceAndProfile(adminUid, ADMIN_EMAIL, "Admin") }
        withTimeout(DEFAULT_TIMEOUT_MS) { repo.addOrApproveSellerEmail(adminUid, SELLER_A_EMAIL, "Seller Alpha") }

        val sellerUid = signInTestUser(SELLER_A_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) { repo.getOrBootstrapWorkspaceAndProfile(sellerUid, SELLER_A_EMAIL, "Seller Alpha") }
        val saleId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addSaleEntry(sellerUid, adminUid, "Sara", 25.0, 135_000L, 135_000L, "25 GB Standard", false, "")
        }.getOrThrow()
        val entries = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLedgerEntries(sellerUid, adminUid).first { list -> list.any { it.id == saleId } }
        }

        val first = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.submitCurrentListToAdmin(sellerUid, adminUid, "Seller Alpha", entries, 1_000L, 50, submissionId = "sub_test_1")
        }
        assertTrue(first.isSuccess)
        val second = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.submitCurrentListToAdmin(sellerUid, adminUid, "Seller Alpha", entries, 2_000L, 50, submissionId = "sub_test_2")
        }
        assertTrue(second.isSuccess)

        val history = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSubmissionHistory(sellerUid, adminUid).first { it.size >= 2 }
        }
        assertEquals(setOf("sub_test_1", "sub_test_2"), history.map { it.id }.toSet())
        // Each report keeps the rate it was sent with.
        assertEquals(25_000L, history.first { it.id == "sub_test_1" }.computeMetrics().infraCostToman)
        assertEquals(50_000L, history.first { it.id == "sub_test_2" }.computeMetrics().infraCostToman)

        val latest = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLatestSubmission(sellerUid, adminUid).first { it?.id == "sub_test_2" }
        }
        assertNotNull(latest)

        // Re-sending under an existing id is refused: reports are immutable.
        val overwrite = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.submitCurrentListToAdmin(sellerUid, adminUid, "Seller Alpha", emptyList(), 0L, 50, submissionId = "sub_test_1")
        }
        assertTrue(overwrite.isFailure)

        // A new cycle clears the current-report pointer but keeps the history.
        val reset = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.resetAndArchiveLedger(sellerUid, adminUid, "Seller Alpha", "Cycle 1", entries, keepArchive = true)
        }
        assertTrue(reset.isSuccess)
        val afterReset = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLatestSubmission(sellerUid, adminUid).first { it == null }
        }
        assertEquals(null, afterReset)
    }

    @Test
    fun bootstrap_withoutWorkspace_reportsNotConfigured_andCannotCreateIt() = runBlocking {
        clearEmulatorData()
        val strangerUid = signInTestUser("stranger@veloledger.test")
        val repo = LedgerRepository(firestore, auth)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(strangerUid, "stranger@veloledger.test", "Stranger")
        }
        assertTrue(result.exceptionOrNull() is WorkspaceNotConfiguredException)
    }

    @Test
    fun crossUserAndAdminPrivateLedgerIsolation_sellerCannotReadAdminOrOtherSellerLedger() = runBlocking {
        clearEmulatorData()
        val adminUid = signInTestUser(ADMIN_EMAIL)
        seedWorkspace(adminUid)
        val repo = LedgerRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(adminUid, ADMIN_EMAIL, "Admin")
        }

        // Admin records a private sale in Admin's own hidden ledger
        val adminPrivateSale = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addSaleEntry(
                ledgerOwnerUid = adminUid,
                workspaceAdminUid = adminUid,
                customerName = "Private VIP",
                dataGb = 200.0,
                priceToman = 750_000L,
                originalPresetPriceToman = 780_000L,
                presetName = "200 GB Family",
                isPaid = true,
                note = "Hidden from sellers"
            )
        }
        assertTrue(adminPrivateSale.isSuccess)

        // Sign in as Seller B (unapproved / PENDING) and verify they cannot read Admin's private ledger OR their own ledger before approval
        val sellerBUid = signInTestUser(SELLER_B_EMAIL)
        val sellerBBootstrap = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(sellerBUid, SELLER_B_EMAIL, "Seller Beta")
        }
        assertTrue(sellerBBootstrap.isSuccess)
        assertEquals("PENDING", sellerBBootstrap.getOrThrow().second.status)
        assertFalse(sellerBBootstrap.getOrThrow().second.isApproved)

        val pendingOwnWriteResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addSaleEntry(
                ledgerOwnerUid = sellerBUid,
                workspaceAdminUid = adminUid,
                customerName = "Blocked Pending Sale",
                dataGb = 20.0,
                priceToman = 100_000L,
                originalPresetPriceToman = 100_000L,
                presetName = "20 GB Standard",
                isPaid = false,
                note = "Should fail while PENDING"
            )
        }
        assertTrue("PENDING Seller B must not be able to write ledger entries", pendingOwnWriteResult.isFailure)

        val crossReadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getLedgerEntries(ledgerOwnerUid = adminUid, workspaceAdminUid = adminUid)
        }
        assertTrue("Seller B must not be able to read Admin private ledger", crossReadResult.isFailure)
        val exception = crossReadResult.exceptionOrNull() as? FirebaseFirestoreException
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    @Test
    fun unauthenticatedCaller_cannotQueryLedgerEntries() = runBlocking {
        clearEmulatorData()
        val adminUid = signInTestUser(ADMIN_EMAIL)
        seedWorkspace(adminUid)
        val repo = LedgerRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrBootstrapWorkspaceAndProfile(adminUid, ADMIN_EMAIL, "Admin")
        }

        // Sign out to test unauthenticated rejection
        auth.signOut()

        val unauthResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getLedgerEntries(ledgerOwnerUid = adminUid, workspaceAdminUid = adminUid)
        }
        assertTrue("Unauthenticated query must fail", unauthResult.isFailure)
        val exception = unauthResult.exceptionOrNull() as? FirebaseFirestoreException
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    private companion object {
        const val ADMIN_EMAIL = "admin@veloledger.test"
        const val SELLER_A_EMAIL = "sellera@veloledger.test"
        const val SELLER_B_EMAIL = "sellerb@veloledger.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
