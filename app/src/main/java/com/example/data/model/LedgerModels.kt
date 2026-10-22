package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@IgnoreExtraProperties
data class WorkspaceConfig(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val adminEmail: String = "",
    val businessName: String = "NodeSplit VPN",
    val costPerGbToman: Long = 1_000L,
    val adminProfitPercent: Int = 50,
    val settingsRevision: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val sellerProfitPercent: Int
        get() = (100 - adminProfitPercent.coerceIn(0, 100))
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val workspaceAdminUid: String = "",
    val email: String = "",
    val emailKey: String = "",
    val displayName: String = "",
    val role: String = "SELLER", // "ADMIN" or "SELLER"
    val status: String = "PENDING", // "APPROVED", "PENDING", "REVOKED"
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isAdmin: Boolean get() = role == "ADMIN" && status == "APPROVED"
    // SEC-01: Exact approval check — unknown, null, or PENDING status is never treated as approved
    val isApproved: Boolean get() = status == "APPROVED"
    val isRevoked: Boolean get() = status == "REVOKED"
    val isPending: Boolean get() = status == "PENDING"
}

@IgnoreExtraProperties
data class ApprovedEmail(
    @DocumentId val id: String = "",
    val emailKey: String = "",
    val email: String = "",
    val sellerName: String = "",
    val workspaceAdminUid: String = "",
    val status: String = "APPROVED", // "APPROVED" or "REVOKED"
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * SaleEntry stores a customer VPN sale record.
 *
 * GB Contract (MONEY-01):
 * - [dataGb]: Total allocated GB (`baseDataGb + bonusGb`).
 * - [bonusGb]: Extra GB included for a New User (0.0 when not a new user).
 * - [baseDataGb]: Purchased base plan GB (`(dataGb - bonusGb).coerceAtLeast(0.0)`).
 * Do NOT add [bonusGb] to [dataGb] a second time during calculations or migrations.
 */
@IgnoreExtraProperties
data class SaleEntry(
    @DocumentId val id: String = "",
    val ledgerOwnerUid: String = "",
    val workspaceAdminUid: String = "",
    val customerName: String = "",
    val dataGb: Double = 0.0,
    val priceToman: Long = 0L,
    val originalPresetPriceToman: Long = 0L,
    val presetName: String = "",
    @get:PropertyName("isPaid")
    val isPaid: Boolean = false,
    @get:PropertyName("isNewUser")
    val isNewUser: Boolean = false,
    val bonusGb: Double = 0.0,
    val note: String = "",
    val hasPendingSync: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val safeDataGb: Double
        get() = if (dataGb.isFinite() && dataGb >= 0.0) dataGb.coerceAtMost(MAX_ALLOWED_GB) else 0.0

    val safeBonusGb: Double
        get() = if (bonusGb.isFinite() && bonusGb >= 0.0) bonusGb.coerceAtMost(safeDataGb) else 0.0

    val baseDataGb: Double
        get() = (safeDataGb - safeBonusGb).coerceAtLeast(0.0)

    val safePriceToman: Long
        get() = priceToman.coerceIn(0L, MAX_ALLOWED_TOMAN)

    val hasDiscount: Boolean
        get() = originalPresetPriceToman > safePriceToman && originalPresetPriceToman > 0L

    val discountAmountToman: Long
        get() = if (hasDiscount) (originalPresetPriceToman - safePriceToman).coerceAtLeast(0L) else 0L

    fun toArchiveItemMap(index: Int = 0): Map<String, Any> {
        val createdMillis = (createdAt?.toDate()?.time) ?: 0L
        val stableItemId = id.ifBlank { "item_${index}_${customerName.hashCode()}" }
        return mapOf(
            "itemId" to stableItemId,
            "customerName" to customerName,
            "dataGb" to safeDataGb,
            "priceToman" to safePriceToman,
            "originalPresetPriceToman" to originalPresetPriceToman.coerceIn(0L, MAX_ALLOWED_TOMAN),
            "presetName" to presetName,
            "isPaid" to isPaid,
            "isNewUser" to isNewUser,
            "bonusGb" to safeBonusGb,
            "note" to note,
            "createdEpochMillis" to createdMillis
        )
    }

    companion object {
        const val MAX_ALLOWED_GB = 100_000.0
        const val MAX_ALLOWED_TOMAN = 10_000_000_000L
    }
}

data class ArchivedSaleItem(
    val itemId: String = "",
    val customerName: String = "",
    val dataGb: Double = 0.0,
    val priceToman: Long = 0L,
    val originalPresetPriceToman: Long = 0L,
    val presetName: String = "",
    val isPaid: Boolean = false,
    val isNewUser: Boolean = false,
    val bonusGb: Double = 0.0,
    val note: String = "",
    val createdEpochMillis: Long = 0L
) {
    val id: String
        get() = itemId

    val safeDataGb: Double
        get() = if (dataGb.isFinite() && dataGb >= 0.0) dataGb.coerceAtMost(SaleEntry.MAX_ALLOWED_GB) else 0.0

    val safeBonusGb: Double
        get() = if (bonusGb.isFinite() && bonusGb >= 0.0) bonusGb.coerceAtMost(safeDataGb) else 0.0

    val baseDataGb: Double
        get() = (safeDataGb - safeBonusGb).coerceAtLeast(0.0)

    fun toSaleEntry(ownerUid: String, workspaceAdminUid: String, index: Int): SaleEntry {
        val ts = if (createdEpochMillis in 1L..4_102_444_800_000L) {
            Timestamp(java.util.Date(createdEpochMillis))
        } else {
            null
        }
        val resolvedId = itemId.ifBlank { "submitted_${index}_${customerName.hashCode()}" }
        return SaleEntry(
            id = resolvedId,
            ledgerOwnerUid = ownerUid,
            workspaceAdminUid = workspaceAdminUid,
            customerName = customerName,
            dataGb = safeDataGb,
            priceToman = priceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
            originalPresetPriceToman = originalPresetPriceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
            presetName = presetName,
            isPaid = isPaid,
            isNewUser = isNewUser,
            bonusGb = safeBonusGb,
            note = note,
            createdAt = ts,
            updatedAt = ts
        )
    }

    fun toMap(index: Int = 0): Map<String, Any> {
        val resolvedId = itemId.ifBlank { "item_${index}_${customerName.hashCode()}" }
        return mapOf(
            "itemId" to resolvedId,
            "customerName" to customerName,
            "dataGb" to safeDataGb,
            "priceToman" to priceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
            "originalPresetPriceToman" to originalPresetPriceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
            "presetName" to presetName,
            "isPaid" to isPaid,
            "isNewUser" to isNewUser,
            "bonusGb" to safeBonusGb,
            "note" to note,
            "createdEpochMillis" to createdEpochMillis.coerceAtLeast(0L)
        )
    }

    companion object {
        fun fromMap(map: Map<*, *>, index: Int = 0, paidOverrides: Map<String, Boolean> = emptyMap()): ArchivedSaleItem? {
            val rawName = (map["customerName"] as? String)?.trim() ?: return null
            val rawGb = (map["dataGb"] as? Number)?.toDouble() ?: 0.0
            val rawBonus = (map["bonusGb"] as? Number)?.toDouble() ?: 0.0
            val rawPrice = (map["priceToman"] as? Number)?.toLong() ?: 0L
            val rawOrigPrice = (map["originalPresetPriceToman"] as? Number)?.toLong() ?: 0L
            if (!rawGb.isFinite() || !rawBonus.isFinite() || rawGb < 0.0 || rawPrice < 0L) {
                return null
            }
            val safeGb = rawGb.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB)
            val safeBonus = rawBonus.coerceIn(0.0, safeGb)
            val rawItemId = (map["itemId"] as? String)?.takeIf { it.isNotBlank() }
                ?: "item_${index}_${rawName.hashCode()}"
            val basePaid = (map["isPaid"] as? Boolean) ?: (map["paid"] as? Boolean) ?: false
            val effectivePaid = paidOverrides[rawItemId] ?: basePaid
            val rawEpoch = (map["createdEpochMillis"] as? Number)?.toLong() ?: 0L

            return ArchivedSaleItem(
                itemId = rawItemId,
                customerName = rawName.ifBlank { "Unnamed Sale" },
                dataGb = safeGb,
                priceToman = rawPrice.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
                originalPresetPriceToman = rawOrigPrice.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
                presetName = (map["presetName"] as? String) ?: "",
                isPaid = effectivePaid,
                isNewUser = (map["isNewUser"] as? Boolean) ?: (map["newUser"] as? Boolean) ?: false,
                bonusGb = safeBonus,
                note = (map["note"] as? String) ?: "",
                createdEpochMillis = if (rawEpoch in 0L..4_102_444_800_000L) rawEpoch else 0L
            )
        }
    }
}

/**
 * A frozen snapshot: either a closed cycle (`archives/`) or a report the seller sent to the Admin
 * (`submissions/`). Items, totals and captured rates never change after creation; only the
 * per-item [paidMap], a one-way void and (for reports) the Admin's settlement status can.
 */
@IgnoreExtraProperties
data class LedgerArchive(
    @DocumentId val id: String = "",
    val ledgerOwnerUid: String = "",
    val workspaceAdminUid: String = "",
    val ownerName: String = "",
    val cycleTitle: String = "",
    val totalSalesToman: Long = 0L,
    val paidToman: Long = 0L,
    val unpaidToman: Long = 0L,
    val totalGb: Double = 0.0,
    val costPerGbToman: Long = 1_000L,
    val adminProfitPercent: Int = 50,
    val hasCapturedCostPerGb: Boolean = true,
    val hasCapturedAdminPercent: Boolean = true,
    val entryCount: Int = 0,
    val items: List<Map<String, Any>> = emptyList(),
    val paidMap: Map<String, Boolean> = emptyMap(),
    val hasPendingSync: Boolean = false,
    val malformedItemCount: Int = 0,
    val sourceCollection: String = COLLECTION_ARCHIVES,
    val isVoided: Boolean = false,
    val voidReason: String = "",
    val voidedAt: Timestamp? = null,
    val adminStatus: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    // Alias for backward compatibility in tests/callers
    val totalDataGb: Double get() = totalGb

    val isSubmission: Boolean
        get() = sourceCollection == COLLECTION_SUBMISSIONS

    /** The pre-2.0 overwrite-only report document (`archives/submitted_current`). */
    val isLegacyReport: Boolean
        get() = sourceCollection == COLLECTION_ARCHIVES && id == LEGACY_SUBMITTED_DOC_ID

    val isSettled: Boolean
        get() = adminStatus == ADMIN_STATUS_SETTLED

    fun parsedItems(): List<ArchivedSaleItem> =
        items.mapIndexedNotNull { idx, raw -> ArchivedSaleItem.fromMap(raw, idx, paidMap) }

    fun computeMetrics(
        fallbackCostPerGb: Long = costPerGbToman,
        fallbackAdminPercent: Int = adminProfitPercent
    ): LedgerSummaryMetrics {
        val parsed = parsedItems()
        // MONEY-01: Preserve an intentionally captured 0 Toman/GB rate; only fall back if field was absent in legacy data
        val effectiveCostPerGb = if (hasCapturedCostPerGb) {
            costPerGbToman.coerceAtLeast(0L)
        } else {
            fallbackCostPerGb.coerceAtLeast(0L)
        }
        val effectiveAdminPct = if (hasCapturedAdminPercent) {
            adminProfitPercent.coerceIn(0, 100)
        } else {
            fallbackAdminPercent.coerceIn(0, 100)
        }
        return FinancialCalculator.computeArchiveMetrics(
            archive = this,
            parsedItems = parsed,
            effectiveCostPerGbToman = effectiveCostPerGb,
            effectiveAdminProfitPercent = effectiveAdminPct
        )
    }

    companion object {
        const val COLLECTION_ARCHIVES = "archives"
        const val COLLECTION_SUBMISSIONS = "submissions"
        const val LEGACY_SUBMITTED_DOC_ID = "submitted_current"
        const val ADMIN_STATUS_RECEIVED = "RECEIVED"
        const val ADMIN_STATUS_SETTLED = "SETTLED"
    }
}

@IgnoreExtraProperties
data class DataPreset(
    @DocumentId val id: String = "",
    val ledgerOwnerUid: String = "",
    val workspaceAdminUid: String = "",
    val label: String = "",
    val dataGb: Double = 0.0,
    val defaultPriceToman: Long = 0L,
    val newUserBonusGb: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Shared domain calculation contract (MONEY-01).
 * Used identically across Dashboard, Submitted Report, Archive cards, Archive Detail sheet, Export dialog, and PDF.
 *
 * Arithmetic & Rounding Policy:
 * - Infrastructure cost = roundHalfUp(totalAllocatedGb * costPerGbToman) using exact BigDecimal arithmetic.
 * - Net profit = totalSalesToman - infraCostToman (may be negative if sales were heavily discounted or free).
 * - Admin profit = (netProfitToman * adminProfitPercent) / 100L (truncating toward zero for whole Toman).
 * - Seller profit = netProfitToman - adminProfitToman (derived by subtraction so infra + seller + admin == gross always holds).
 * - Contractual amount Seller pays Admin = infraCostToman + adminProfitToman.
 * - Paid/unpaid checkboxes NEVER alter gross sales, infrastructure cost, profit shares, or amount due to Admin.
 */
object FinancialCalculator {
    fun computeActiveMetrics(
        entries: List<SaleEntry>,
        costPerGbToman: Long,
        adminProfitPercent: Int
    ): LedgerSummaryMetrics {
        val validEntries = entries.filter { it.dataGb.isFinite() && it.dataGb >= 0.0 && it.priceToman >= 0L }
        val totalToman = validEntries.fold(0L) { acc, item ->
            (acc + item.safePriceToman).coerceAtMost(Long.MAX_VALUE / 2)
        }
        val paidToman = validEntries.filter { it.isPaid }.fold(0L) { acc, item ->
            (acc + item.safePriceToman).coerceAtMost(Long.MAX_VALUE / 2)
        }
        val unpaidToman = (totalToman - paidToman).coerceAtLeast(0L)
        val totalGb = validEntries.sumOf { it.safeDataGb }
        val totalBonusGb = validEntries.sumOf { it.safeBonusGb }
        val newUsersCount = validEntries.count { it.isNewUser }
        val paidCount = validEntries.count { it.isPaid }
        val unpaidCount = validEntries.size - paidCount

        return LedgerSummaryMetrics(
            totalSalesToman = totalToman,
            paidToman = paidToman,
            unpaidToman = unpaidToman,
            totalGb = totalGb,
            totalBonusGb = totalBonusGb,
            newUsersCount = newUsersCount,
            totalCustomers = validEntries.size,
            paidCount = paidCount,
            unpaidCount = unpaidCount,
            costPerGbToman = costPerGbToman.coerceIn(0L, 1_000_000_000L),
            adminProfitPercent = adminProfitPercent.coerceIn(0, 100)
        )
    }

    /**
     * SEC-05: Totals are re-derived from the validated items rather than trusted from the stored
     * summary fields. A disagreement (or skipped malformed items) is flagged for the UI.
     */
    fun computeArchiveMetrics(
        archive: LedgerArchive,
        parsedItems: List<ArchivedSaleItem>,
        effectiveCostPerGbToman: Long,
        effectiveAdminProfitPercent: Int
    ): LedgerSummaryMetrics {
        val derivedGross = parsedItems.fold(0L) { acc, item ->
            (acc + item.priceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN)).coerceAtMost(Long.MAX_VALUE / 2)
        }
        val derivedGb = parsedItems.sumOf { it.safeDataGb }
        val paidSum = parsedItems.filter { it.isPaid }.sumOf { it.priceToman.coerceAtLeast(0L) }
        val unpaidSum = (derivedGross - paidSum).coerceAtLeast(0L)
        val totalsMismatch = archive.malformedItemCount > 0 ||
            archive.totalSalesToman != derivedGross ||
            abs(archive.totalGb - derivedGb) > 0.001 ||
            archive.entryCount != parsedItems.size

        return LedgerSummaryMetrics(
            totalSalesToman = derivedGross,
            paidToman = paidSum,
            unpaidToman = unpaidSum,
            totalGb = derivedGb,
            totalBonusGb = parsedItems.sumOf { it.safeBonusGb },
            newUsersCount = parsedItems.count { it.isNewUser },
            totalCustomers = parsedItems.size,
            paidCount = parsedItems.count { it.isPaid },
            unpaidCount = parsedItems.count { !it.isPaid },
            costPerGbToman = effectiveCostPerGbToman.coerceIn(0L, 1_000_000_000L),
            adminProfitPercent = effectiveAdminProfitPercent.coerceIn(0, 100),
            hasLegacyAssumedRates = !archive.hasCapturedCostPerGb || !archive.hasCapturedAdminPercent,
            hasTotalsMismatch = totalsMismatch
        )
    }

    fun computeInfraCostToman(totalAllocatedGb: Double, costPerGbToman: Long): Long {
        if (!totalAllocatedGb.isFinite() || totalAllocatedGb <= 0.0 || costPerGbToman <= 0L) {
            return 0L
        }
        return try {
            BigDecimal.valueOf(totalAllocatedGb)
                .multiply(BigDecimal.valueOf(costPerGbToman.coerceAtLeast(0L)))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE / 4
        }
    }

    fun calculate(
        grossSalesToman: Long,
        totalAllocatedGb: Double,
        costPerGbToman: Long,
        adminSharePercent: Int
    ): FinancialBreakdown {
        val safeGross = grossSalesToman.coerceAtLeast(0L)
        val safeGb = if (totalAllocatedGb.isFinite() && totalAllocatedGb >= 0.0) totalAllocatedGb else 0.0
        val safeCostPerGb = costPerGbToman.coerceIn(0L, 1_000_000_000L)
        val safeAdminPct = adminSharePercent.coerceIn(0, 100)
        val safeSellerPct = 100 - safeAdminPct

        val infraCost = computeInfraCostToman(safeGb, safeCostPerGb)
        val netProfit = safeGross - infraCost
        val adminProfit = (netProfit * safeAdminPct) / 100L
        val sellerProfit = netProfit - adminProfit
        val sellerPayable = adminProfit + infraCost

        return FinancialBreakdown(
            grossSalesToman = safeGross,
            totalAllocatedGb = safeGb,
            costPerGbToman = safeCostPerGb,
            adminSharePercent = safeAdminPct,
            sellerSharePercent = safeSellerPct,
            infrastructureCostToman = infraCost,
            netProfitToman = netProfit,
            sellerProfitToman = sellerProfit,
            adminProfitToman = adminProfit,
            amountSellerPaysAdminToman = sellerPayable
        )
    }
}

data class FinancialBreakdown(
    val grossSalesToman: Long,
    val totalAllocatedGb: Double,
    val costPerGbToman: Long,
    val adminSharePercent: Int,
    val sellerSharePercent: Int,
    val infrastructureCostToman: Long,
    val netProfitToman: Long,
    val sellerProfitToman: Long,
    val adminProfitToman: Long,
    val amountSellerPaysAdminToman: Long
)

// --- Deterministic DocumentSnapshot Mappers (Defensive against NaN / corrupt fields — SEC-05) ---

fun DocumentSnapshot.toWorkspaceConfigOrNull(): WorkspaceConfig? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawCost = getLong("costPerGbToman") ?: 1_000L
    val rawAdminPct = (getLong("adminProfitPercent") ?: 50L).toInt()
    val rawRev = getLong("settingsRevision") ?: 1L
    return WorkspaceConfig(
        id = id,
        adminUid = getString("adminUid") ?: "",
        adminEmail = getString("adminEmail") ?: "",
        businessName = getString("businessName") ?: "NodeSplit VPN",
        costPerGbToman = rawCost.coerceIn(0L, 1_000_000_000L),
        adminProfitPercent = rawAdminPct.coerceIn(0, 100),
        settingsRevision = rawRev.coerceAtLeast(1L),
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null },
        updatedAt = try { getTimestamp("updatedAt", behavior) } catch (_: Exception) { null }
    )
}

fun DocumentSnapshot.toUserProfileOrNull(): UserProfile? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawStatus = getString("status") ?: "PENDING"
    val validStatus = if (rawStatus in setOf("APPROVED", "PENDING", "REVOKED")) rawStatus else "PENDING"
    val rawRole = getString("role") ?: "SELLER"
    val validRole = if (rawRole in setOf("ADMIN", "SELLER")) rawRole else "SELLER"
    return UserProfile(
        id = id,
        userId = getString("userId") ?: id,
        workspaceAdminUid = getString("workspaceAdminUid") ?: "",
        email = getString("email") ?: "",
        emailKey = getString("emailKey") ?: "",
        displayName = getString("displayName") ?: "",
        role = validRole,
        status = validStatus,
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null },
        updatedAt = try { getTimestamp("updatedAt", behavior) } catch (_: Exception) { null }
    )
}

fun DocumentSnapshot.toApprovedEmailOrNull(): ApprovedEmail? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawStatus = getString("status") ?: "APPROVED"
    val validStatus = if (rawStatus in setOf("APPROVED", "REVOKED")) rawStatus else "REVOKED"
    return ApprovedEmail(
        id = id,
        emailKey = getString("emailKey") ?: id,
        email = getString("email") ?: "",
        sellerName = getString("sellerName") ?: "",
        workspaceAdminUid = getString("workspaceAdminUid") ?: "",
        status = validStatus,
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null },
        updatedAt = try { getTimestamp("updatedAt", behavior) } catch (_: Exception) { null }
    )
}

fun DocumentSnapshot.toSaleEntryOrNull(): SaleEntry? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawGb = getDouble("dataGb") ?: return null
    val rawBonus = getDouble("bonusGb") ?: 0.0
    val rawPrice = getLong("priceToman") ?: return null
    if (!rawGb.isFinite() || !rawBonus.isFinite() || rawGb <= 0.0 || rawPrice < 0L) {
        return null
    }
    return SaleEntry(
        id = id,
        ledgerOwnerUid = getString("ledgerOwnerUid") ?: "",
        workspaceAdminUid = getString("workspaceAdminUid") ?: "",
        customerName = getString("customerName") ?: "",
        dataGb = rawGb.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB),
        priceToman = rawPrice.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
        originalPresetPriceToman = (getLong("originalPresetPriceToman") ?: rawPrice).coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
        presetName = getString("presetName") ?: "",
        isPaid = getBoolean("isPaid") ?: getBoolean("paid") ?: false,
        isNewUser = getBoolean("isNewUser") ?: getBoolean("newUser") ?: false,
        bonusGb = rawBonus.coerceIn(0.0, rawGb),
        note = getString("note") ?: "",
        hasPendingSync = metadata.hasPendingWrites(),
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null },
        updatedAt = try { getTimestamp("updatedAt", behavior) } catch (_: Exception) { null }
    )
}

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toLedgerArchiveOrNull(
    sourceCollection: String = LedgerArchive.COLLECTION_ARCHIVES
): LedgerArchive? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawList = (get("items") as? List<*>) ?: emptyList<Any?>()
    val rawItems = rawList.mapNotNull { it as? Map<String, Any> }
    val rawPaidMap = (get("paidMap") as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (k, v) ->
            val key = k as? String
            val boolVal = v as? Boolean
            if (key != null && boolVal != null) key to boolVal else null
        }
        ?.toMap()
        ?: emptyMap()

    val hasCapturedCost = contains("costPerGbToman") && getLong("costPerGbToman") != null
    val hasCapturedAdminPct = contains("adminProfitPercent") && getLong("adminProfitPercent") != null
    val rawTotalGb = getDouble("totalGb") ?: 0.0
    val safeTotalGb = if (rawTotalGb.isFinite() && rawTotalGb >= 0.0) rawTotalGb else 0.0
    val rawCost = (getLong("costPerGbToman") ?: 1_000L).coerceIn(0L, 1_000_000_000L)
    val rawAdminPct = (getLong("adminProfitPercent") ?: 50L).toInt().coerceIn(0, 100)

    val parsedValidCount = rawItems.mapIndexedNotNull { idx, m -> ArchivedSaleItem.fromMap(m, idx, rawPaidMap) }.size
    val malformedCount = (rawList.size - parsedValidCount).coerceAtLeast(0)

    return LedgerArchive(
        id = id,
        ledgerOwnerUid = getString("ledgerOwnerUid") ?: "",
        workspaceAdminUid = getString("workspaceAdminUid") ?: "",
        ownerName = getString("ownerName") ?: "",
        cycleTitle = getString("cycleTitle") ?: "",
        totalSalesToman = (getLong("totalSalesToman") ?: 0L).coerceAtLeast(0L),
        paidToman = (getLong("paidToman") ?: 0L).coerceAtLeast(0L),
        unpaidToman = (getLong("unpaidToman") ?: 0L).coerceAtLeast(0L),
        totalGb = safeTotalGb,
        costPerGbToman = rawCost,
        adminProfitPercent = rawAdminPct,
        hasCapturedCostPerGb = hasCapturedCost,
        hasCapturedAdminPercent = hasCapturedAdminPct,
        entryCount = (getLong("entryCount") ?: parsedValidCount.toLong()).toInt().coerceAtLeast(0),
        items = rawItems,
        paidMap = rawPaidMap,
        hasPendingSync = metadata.hasPendingWrites(),
        malformedItemCount = malformedCount,
        sourceCollection = sourceCollection,
        isVoided = getBoolean("voided") ?: false,
        voidReason = getString("voidReason") ?: "",
        voidedAt = try { getTimestamp("voidedAt", behavior) } catch (_: Exception) { null },
        adminStatus = getString("adminStatus") ?: "",
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null }
    )
}

fun DocumentSnapshot.toDataPresetOrNull(): DataPreset? {
    if (!exists()) return null
    val behavior = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    val rawGb = getDouble("dataGb") ?: return null
    val rawBonus = getDouble("newUserBonusGb") ?: 0.0
    if (!rawGb.isFinite() || !rawBonus.isFinite() || rawGb <= 0.0) return null
    return DataPreset(
        id = id,
        ledgerOwnerUid = getString("ledgerOwnerUid") ?: "",
        workspaceAdminUid = getString("workspaceAdminUid") ?: "",
        label = getString("label") ?: "",
        dataGb = rawGb.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB),
        defaultPriceToman = (getLong("defaultPriceToman") ?: 0L).coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
        newUserBonusGb = rawBonus.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB),
        createdAt = try { getTimestamp("createdAt", behavior) } catch (_: Exception) { null },
        updatedAt = try { getTimestamp("updatedAt", behavior) } catch (_: Exception) { null }
    )
}

data class LedgerSummaryMetrics(
    val totalSalesToman: Long = 0L,
    val paidToman: Long = 0L,
    val unpaidToman: Long = 0L,
    val totalGb: Double = 0.0,
    val totalBonusGb: Double = 0.0,
    val newUsersCount: Int = 0,
    val totalCustomers: Int = 0,
    val paidCount: Int = 0,
    val unpaidCount: Int = 0,
    val costPerGbToman: Long = 1_000L,
    val adminProfitPercent: Int = 50,
    val hasLegacyAssumedRates: Boolean = false,
    val hasTotalsMismatch: Boolean = false
) {
    val salesCount: Int
        get() = totalCustomers

    val sellerProfitPercent: Int
        get() = (100 - adminProfitPercent.coerceIn(0, 100))

    // 1. Infrastructure & VPS Cost (Total Allocated GB * Cost per GB, rounded HALF_UP)
    val infraCostToman: Long
        get() = FinancialCalculator.computeInfraCostToman(totalGb, costPerGbToman)

    // 2. Remaining Net Profit after deducting Infrastructure Cost
    val netProfitToman: Long
        get() = totalSalesToman - infraCostToman

    // 3. Admin Share of Net Profit (default 50%)
    val adminProfitToman: Long
        get() = (netProfitToman * adminProfitPercent.coerceIn(0, 100)) / 100L

    // 4. Seller Share of Net Profit (derived by subtraction so shares always sum to netProfitToman)
    val sellerProfitToman: Long
        get() = netProfitToman - adminProfitToman

    // 5. Total Contractual Amount Seller Must Pay Admin = Admin Profit + Infrastructure Cost
    val sellerPayableToAdminToman: Long
        get() = adminProfitToman + infraCostToman
}

data class LedgerTargetBook(
    val ownerUid: String,
    val title: String,
    val subtitle: String,
    val cleanOwnerName: String = "",
    val isPrivateAdminBook: Boolean
)

/**
 * Input validation & localized digit normalization (MONEY-02).
 * Prevents silent `.toLongOrNull() ?: 0L` coercion on malformed inputs while supporting intentional 0 values.
 */
object InputValidator {
    fun normalizeDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                '٫', '،', ',' -> { /* strip thousands separators or normalize in decimal parser */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeDecimalInput(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                '٫' -> sb.append('.')
                ',' -> { /* ignore comma thousands separator */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    fun parseValidToman(raw: String, allowZero: Boolean = true, maxToman: Long = SaleEntry.MAX_ALLOWED_TOMAN): Result<Long> {
        val cleaned = normalizeDigits(raw).replace("_", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter an amount in Toman."))
        }
        if (cleaned.startsWith("-")) {
            return Result.failure(IllegalArgumentException("Amount in Toman cannot be negative."))
        }
        if (!cleaned.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Enter a whole Toman number using digits only."))
        }
        if (cleaned.length > 12) {
            return Result.failure(IllegalArgumentException("Amount exceeds maximum supported Toman limit."))
        }
        val value = cleaned.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid Toman amount."))
        if (!allowZero && value == 0L) {
            return Result.failure(IllegalArgumentException("Amount must be greater than 0 Toman."))
        }
        if (value > maxToman) {
            return Result.failure(IllegalArgumentException("Amount cannot exceed ${TomanFormatter.format(maxToman)}."))
        }
        return Result.success(value)
    }

    fun parseValidGb(raw: String, allowZero: Boolean = false, maxGb: Double = SaleEntry.MAX_ALLOWED_GB): Result<Double> {
        val cleaned = normalizeDecimalInput(raw)
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a GB amount."))
        }
        if (cleaned.startsWith("-")) {
            return Result.failure(IllegalArgumentException("GB amount cannot be negative."))
        }
        if (cleaned.count { it == '.' } > 1) {
            return Result.failure(IllegalArgumentException("GB amount cannot contain multiple decimal points."))
        }
        val value = cleaned.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Enter a valid numeric GB value."))
        if (!value.isFinite()) {
            return Result.failure(IllegalArgumentException("GB value must be finite."))
        }
        if (!allowZero && value <= 0.0) {
            return Result.failure(IllegalArgumentException("GB amount must be greater than 0."))
        }
        if (allowZero && value < 0.0) {
            return Result.failure(IllegalArgumentException("Bonus GB cannot be negative."))
        }
        if (value > maxGb) {
            return Result.failure(IllegalArgumentException("GB amount cannot exceed ${TomanFormatter.formatGb(maxGb)}."))
        }
        return Result.success(value)
    }

    fun parseValidPercent(raw: String): Result<Int> {
        val cleaned = normalizeDigits(raw).trim()
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a percentage between 0 and 100."))
        }
        val pct = cleaned.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Enter a whole percentage between 0 and 100."))
        if (pct !in 0..100) {
            return Result.failure(IllegalArgumentException("Percentage must be between 0% and 100%."))
        }
        return Result.success(pct)
    }

    fun validateDataGb(raw: String, fieldLabel: String = "Data GB"): Result<Double> =
        parseValidGb(raw, allowZero = false).recoverCatching { e ->
            throw IllegalArgumentException("$fieldLabel: ${e.message}")
        }

    fun validateBonusGb(raw: String): Result<Double> =
        if (raw.trim().isEmpty()) Result.success(0.0) else parseValidGb(raw, allowZero = true)

    fun validatePriceToman(raw: String, fieldLabel: String = "Price"): Result<Long> =
        parseValidToman(raw, allowZero = true).recoverCatching { e ->
            throw IllegalArgumentException("$fieldLabel: ${e.message}")
        }

    fun validateAdminSharePercent(raw: String): Result<Int> =
        parseValidPercent(raw)
}

object TomanFormatter {
    private val numberFormat: NumberFormat
        get() = NumberFormat.getNumberInstance(Locale.US)

    fun format(amount: Long, includeSuffix: Boolean = true): String {
        val formatted = numberFormat.format(amount)
        return if (includeSuffix) "$formatted Toman" else formatted
    }

    /**
     * UI-04: Prefer full "Toman" consistently across UI surfaces so units are never ambiguous.
     */
    fun formatShort(amount: Long): String {
        return "${numberFormat.format(amount)} Toman"
    }

    fun formatGbNumberOnly(gb: Double): String {
        if (!gb.isFinite()) return "0"
        return if (gb % 1.0 == 0.0) {
            gb.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", gb)
        }
    }

    fun formatGb(gb: Double): String {
        return "${formatGbNumberOnly(gb)} GB"
    }

    fun formatAllocationSummary(totalGb: Double, bonusGb: Double, isNewUser: Boolean): String {
        val totalStr = formatGb(totalGb)
        return if (isNewUser && bonusGb > 0.0) {
            val baseGb = (totalGb - bonusGb).coerceAtLeast(0.0)
            "$totalStr total (${formatGb(baseGb)} base + ${formatGb(bonusGb)} bonus)"
        } else {
            totalStr
        }
    }

    fun sanitizeOwnerDisplayName(rawName: String): String {
        val withoutEmails = rawName
            .split("•")
            .map { it.trim() }
            .filter { part -> !part.contains("@") && part.isNotBlank() }
            .joinToString(" • ")
        if (withoutEmails.isNotBlank()) return withoutEmails
        val beforeAt = rawName.substringBefore("@").trim()
        return beforeAt.ifBlank { "NodeSplit Account" }
    }

    fun canonicalizeEmail(email: String): String {
        return email.trim().lowercase(Locale.US)
    }

    private val invitationKeyPattern = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+$")

    /**
     * SEC-02: Invitations are keyed by the canonical email itself, so two different addresses can
     * never share a document (the old `a.b@x` / `a_dot_b@x` collision). The Firestore rules let a
     * non-admin read only the key equal to their own signed-in token email.
     * Returns null for an address that cannot be used as a key (it must be added differently).
     */
    fun invitationKeyFor(email: String): String? {
        val canonical = canonicalizeEmail(email)
        return canonical.takeIf { it.length in 3..200 && invitationKeyPattern.matches(it) }
    }

    /** Pre-2.0 invitation key (`a_at_b_dot_c`). Read only for migration; collision-prone. */
    fun legacyEmailToSafeKey(email: String): String {
        val cleaned = canonicalizeEmail(email)
            .replace("@", "_at_")
            .replace(".", "_dot_")
            .replace(Regex("[^a-z0-9_\\-]"), "_")
        return cleaned.take(120).ifBlank { "unknown_email" }
    }

    /** Interim key scheme (legacy key plus a hash suffix for unusual addresses). Read only for migration. */
    fun interimEmailToSafeKey(email: String): String {
        val canonical = canonicalizeEmail(email)
        if (canonical.isBlank()) return "unknown_email"
        val readable = canonical
            .replace("@", "_at_")
            .replace(".", "_dot_")
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .take(88)
        val hasSpecialChars = canonical.any { !(it in 'a'..'z' || it in '0'..'9' || it == '@' || it == '.' || it == '_' || it == '-') }
        return if (!hasSpecialChars && canonical.length <= 80) {
            readable.ifBlank { "unknown_email" }
        } else {
            "${readable}_${sha256Hex(canonical).take(12)}"
        }
    }

    /** Every key an invitation for [email] may be stored under, newest scheme first. */
    fun candidateInvitationKeys(email: String): List<String> =
        listOfNotNull(invitationKeyFor(email), interimEmailToSafeKey(email), legacyEmailToSafeKey(email)).distinct()

    private fun sha256Hex(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toUInt().toString(16)
        }
    }

    val defaultBuiltInPresets: List<DataPreset> = listOf(
        DataPreset(id = "builtin_10", label = "10 GB Starter", dataGb = 10.0, defaultPriceToman = 65_000L, newUserBonusGb = 2.0),
        DataPreset(id = "builtin_25", label = "25 GB Standard", dataGb = 25.0, defaultPriceToman = 135_000L, newUserBonusGb = 5.0),
        DataPreset(id = "builtin_50", label = "50 GB Pro", dataGb = 50.0, defaultPriceToman = 240_000L, newUserBonusGb = 10.0),
        DataPreset(id = "builtin_100", label = "100 GB Ultra", dataGb = 100.0, defaultPriceToman = 420_000L, newUserBonusGb = 15.0),
        DataPreset(id = "builtin_200", label = "200 GB Family", dataGb = 200.0, defaultPriceToman = 780_000L, newUserBonusGb = 25.0)
    )
}
