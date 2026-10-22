package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ApprovedEmail
import com.example.data.model.ArchivedSaleItem
import com.example.data.model.DataPreset
import com.example.data.model.LedgerArchive
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import com.example.data.model.UserProfile
import com.example.data.model.WorkspaceConfig
import com.example.data.model.toApprovedEmailOrNull
import com.example.data.model.toDataPresetOrNull
import com.example.data.model.toLedgerArchiveOrNull
import com.example.data.model.toSaleEntryOrNull
import com.example.data.model.toUserProfileOrNull
import com.example.data.model.toWorkspaceConfigOrNull
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class WriteAckStatus {
    SYNCED_SERVER,
    SAVED_OFFLINE_PENDING_SYNC
}

class LedgerRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    // Convenience secondary constructor resolving custom database ID from resources
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance()
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * OFF-01 / OFF-03: Firestore commits mutations to its local durable persistence immediately when
     * `set`/`update`/`delete`/`batch.commit` is invoked, while the returned Task completes only upon
     * server acknowledgement. Waiting with a bounded window catches immediate server/rule rejections
     * (and passes local emulator tests in <50ms), while returning SAVED_OFFLINE_PENDING_SYNC when
     * offline so the UI never hangs waiting for network connectivity.
     */
    private suspend fun awaitServerOrQueueOffline(
        task: Task<*>,
        operationType: OperationType,
        path: String
    ): WriteAckStatus {
        task.addOnFailureListener { e ->
            handleFirestoreError(e, operationType, path)
        }
        val completedInWindow = withTimeoutOrNull(SERVER_ACK_WAIT_MS) {
            task.await()
            true
        }
        if (completedInWindow == true) {
            return WriteAckStatus.SYNCED_SERVER
        }
        if (task.isComplete) {
            val ex = task.exception
            if (ex != null) throw ex
            return WriteAckStatus.SYNCED_SERVER
        }
        return WriteAckStatus.SAVED_OFFLINE_PENDING_SYNC
    }

    private suspend fun getDocumentWithOfflineFallback(
        pathCollection: String,
        docId: String
    ): DocumentSnapshot {
        val docRef = db.collection(pathCollection).document(docId)
        return try {
            val netSnap = withTimeoutOrNull(BOOTSTRAP_NET_TIMEOUT_MS) {
                docRef.get(Source.DEFAULT).await()
            }
            netSnap ?: docRef.get(Source.CACHE).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                docRef.get(Source.CACHE).await()
            } else {
                throw e
            }
        }
    }

    // --- 1. Workspace Bootstrap, Real-Time Config & User Profile Resolution ---

    suspend fun getOrBootstrapWorkspaceAndProfile(
        uid: String,
        email: String,
        displayName: String
    ): Result<Pair<WorkspaceConfig, UserProfile>> {
        val safeEmail = TomanFormatter.canonicalizeEmail(email).ifBlank { "$uid@user.nodesplit.app" }
        val emailKey = TomanFormatter.emailToSafeKey(safeEmail)
        val legacyEmailKey = TomanFormatter.legacyEmailToSafeKey(safeEmail)
        val safeName = displayName.trim().ifBlank { safeEmail.substringBefore("@").ifBlank { "VPN Seller" } }.take(90)
        val workspaceRef = db.collection("config").document("workspace")

        return try {
            var workspaceSnap = try {
                getDocumentWithOfflineFallback("config", "workspace")
            } catch (_: Exception) {
                workspaceRef.get().await()
            }

            if (!workspaceSnap.exists()) {
                // First user to launch initializes the workspace as Admin
                val initPayload = mapOf(
                    "adminUid" to uid,
                    "adminEmail" to "",
                    "businessName" to "NodeSplit VPN",
                    "costPerGbToman" to 1_000L,
                    "adminProfitPercent" to 50,
                    "settingsRevision" to 1L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                workspaceRef.set(initPayload).await()
                workspaceSnap = workspaceRef.get().await()
            }

            val workspace = workspaceSnap.toWorkspaceConfigOrNull()
                ?: return Result.failure(
                    IllegalStateException("Workspace configuration could not be loaded. Connect to the internet to finish initial setup.")
                )

            val adminUid = workspace.adminUid.ifBlank { uid }
            // SEC-04: Admin authority is bound strictly to workspace.adminUid (no hardcoded email backdoors)
            val isUserWorkspaceAdmin = (uid == adminUid)

            val userRef = db.collection("users").document(uid)
            val userSnap = try {
                getDocumentWithOfflineFallback("users", uid)
            } catch (_: Exception) {
                userRef.get().await()
            }

            if (userSnap.exists()) {
                val existingProfile = userSnap.toUserProfileOrNull()
                    ?: return Result.failure(IllegalStateException("Account profile data is invalid."))

                // SEC-03: Only a PENDING seller can be upgraded via pre-approved email; REVOKED never self-reverses
                if (existingProfile.role == "SELLER" && existingProfile.status == "PENDING") {
                    val approvedCheck = checkEmailPreApproved(emailKey, legacyEmailKey, safeEmail)
                    if (approvedCheck != null && approvedCheck.status == "APPROVED") {
                        userRef.update(
                            mapOf(
                                "status" to "APPROVED",
                                "displayName" to approvedCheck.sellerName.ifBlank { existingProfile.displayName }.take(90),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                        val updatedSnap = userRef.get().await()
                        val updatedProfile = updatedSnap.toUserProfileOrNull()
                            ?: existingProfile.copy(status = "APPROVED")
                        return Result.success(workspace to updatedProfile)
                    }
                }
                Result.success(workspace to existingProfile)
            } else {
                // Check if Admin already pre-approved this Seller email before creating profile
                val preApproved = if (!isUserWorkspaceAdmin) {
                    checkEmailPreApproved(emailKey, legacyEmailKey, safeEmail)
                } else {
                    null
                }
                val isPreApprovedSeller = preApproved != null &&
                    preApproved.status == "APPROVED" &&
                    TomanFormatter.canonicalizeEmail(preApproved.email) == safeEmail

                val effectiveEmailKey = if (isPreApprovedSeller && preApproved != null) {
                    preApproved.emailKey.ifBlank { emailKey }
                } else {
                    emailKey
                }
                val initialRole = if (isUserWorkspaceAdmin) "ADMIN" else "SELLER"
                val initialStatus = if (isUserWorkspaceAdmin || isPreApprovedSeller) "APPROVED" else "PENDING"
                val finalDisplayName = if (isPreApprovedSeller && preApproved != null && preApproved.sellerName.isNotBlank()) {
                    preApproved.sellerName.take(90)
                } else {
                    safeName
                }

                val newProfileMap = mapOf(
                    "userId" to uid,
                    "workspaceAdminUid" to adminUid,
                    "email" to safeEmail,
                    "emailKey" to effectiveEmailKey,
                    "displayName" to finalDisplayName,
                    "role" to initialRole,
                    "status" to initialStatus,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(newProfileMap).await()

                val createdSnap = userRef.get().await()
                val createdProfile = createdSnap.toUserProfileOrNull()
                    ?: UserProfile(
                        id = uid,
                        userId = uid,
                        workspaceAdminUid = adminUid,
                        email = safeEmail,
                        emailKey = effectiveEmailKey,
                        displayName = finalDisplayName,
                        role = initialRole,
                        status = initialStatus
                    )
                Result.success(workspace to createdProfile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "config/workspace")
            Result.failure(e)
        }
    }

    private suspend fun checkEmailPreApproved(
        primaryKey: String,
        legacyKey: String,
        expectedEmail: String
    ): ApprovedEmail? {
        val primary = try {
            val snap = db.collection("approved_emails").document(primaryKey).get().await()
            snap.toApprovedEmailOrNull()
        } catch (_: Exception) {
            null
        }
        if (primary != null && TomanFormatter.canonicalizeEmail(primary.email) == expectedEmail) {
            return primary
        }
        if (legacyKey != primaryKey) {
            val legacy = try {
                val snap = db.collection("approved_emails").document(legacyKey).get().await()
                snap.toApprovedEmailOrNull()
            } catch (_: Exception) {
                null
            }
            if (legacy != null && TomanFormatter.canonicalizeEmail(legacy.email) == expectedEmail) {
                return legacy
            }
        }
        return null
    }

    fun observeWorkspaceConfig(): Flow<WorkspaceConfig?> {
        val path = "config/workspace"
        return db.collection("config").document("workspace")
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.toWorkspaceConfigOrNull()
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
                throw error
            }
    }

    suspend fun updateWorkspaceRates(
        costPerGbToman: Long,
        adminProfitPercent: Int
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("config").document("workspace")
        return try {
            docRef.update(
                mapOf(
                    "costPerGbToman" to costPerGbToman.coerceIn(0L, 1_000_000_000L),
                    "adminProfitPercent" to adminProfitPercent.coerceIn(0, 100),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> {
        val path = "users/$uid"
        return db.collection("users").document(uid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.toUserProfileOrNull()
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
                throw error
            }
    }

    // --- 2. Admin Seller Management (Approve / Add Seller Google Emails) ---

    fun observeWorkspaceUsers(workspaceAdminUid: String): Flow<List<UserProfile>> {
        val path = "users"
        return db.collection("users")
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toUserProfileOrNull()
                }.sortedWith(compareBy({ it.role != "ADMIN" }, { it.displayName.lowercase() }))
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    fun observeApprovedSellerEmails(workspaceAdminUid: String): Flow<List<ApprovedEmail>> {
        val path = "approved_emails"
        return db.collection("approved_emails")
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toApprovedEmailOrNull()
                }.sortedBy { it.sellerName.lowercase() }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun addOrApproveSellerEmail(
        workspaceAdminUid: String,
        email: String,
        sellerName: String
    ): Result<String> {
        requireUserId()
        val cleanEmail = TomanFormatter.canonicalizeEmail(email)
        val emailKey = TomanFormatter.emailToSafeKey(cleanEmail)
        val cleanName = sellerName.trim().ifBlank { cleanEmail.substringBefore("@") }.take(90)
        val docRef = db.collection("approved_emails").document(emailKey)

        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "sellerName" to cleanName,
                        "status" to "APPROVED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val payload = mapOf(
                    "emailKey" to emailKey,
                    "email" to cleanEmail,
                    "sellerName" to cleanName,
                    "workspaceAdminUid" to workspaceAdminUid,
                    "status" to "APPROVED",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }

            // Also atomically update any matching seller profile in /users if they already signed in
            val matchingUsers = db.collection("users")
                .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
                .get()
                .await()
            val batch = db.batch()
            var hasUserUpdates = false
            for (userDoc in matchingUsers.documents) {
                val docEmail = TomanFormatter.canonicalizeEmail(userDoc.getString("email") ?: "")
                val docEmailKey = userDoc.getString("emailKey") ?: ""
                if ((docEmail == cleanEmail || docEmailKey == emailKey) && userDoc.getString("role") == "SELLER") {
                    batch.update(
                        userDoc.reference,
                        mapOf(
                            "status" to "APPROVED",
                            "displayName" to cleanName,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    hasUserUpdates = true
                }
            }
            if (hasUserUpdates) {
                batch.commit().await()
            }
            Result.success(emailKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateSellerStatus(
        sellerUid: String,
        emailKey: String,
        email: String,
        sellerName: String,
        workspaceAdminUid: String,
        approve: Boolean
    ): Result<Unit> {
        requireUserId()
        val newStatus = if (approve) "APPROVED" else "REVOKED"
        val userRef = db.collection("users").document(sellerUid)
        return try {
            val cleanEmail = TomanFormatter.canonicalizeEmail(email)
            val resolvedEmailKey = emailKey.ifBlank { TomanFormatter.emailToSafeKey(cleanEmail) }
            val batch = db.batch()

            batch.update(
                userRef,
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            if (resolvedEmailKey.isNotBlank() && cleanEmail.isNotBlank()) {
                val emailRef = db.collection("approved_emails").document(resolvedEmailKey)
                val emailSnap = emailRef.get().await()
                if (emailSnap.exists()) {
                    batch.update(
                        emailRef,
                        mapOf(
                            "status" to newStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.set(
                        emailRef,
                        mapOf(
                            "emailKey" to resolvedEmailKey,
                            "email" to cleanEmail,
                            "sellerName" to sellerName.ifBlank { cleanEmail.substringBefore("@") }.take(90),
                            "workspaceAdminUid" to workspaceAdminUid,
                            "status" to newStatus,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeApprovedEmail(emailKey: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection("approved_emails").document(emailKey)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    // --- 3. Active Ledger Entries (Role-Scoped to Seller or Private Admin Ledger) ---

    fun observeLedgerEntries(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<SaleEntry>> {
        val path = "ledgers/$ledgerOwnerUid/entries"
        val baseQuery = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)

        return baseQuery
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toSaleEntryOrNull()
                }.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun getLedgerEntries(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Result<List<SaleEntry>> {
        val path = "ledgers/$ledgerOwnerUid/entries"
        return try {
            val snap = db.collection("ledgers")
                .document(ledgerOwnerUid)
                .collection("entries")
                .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
                .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
                .get()
                .await()
            val list = snap.documents.mapNotNull { doc ->
                doc.toSaleEntryOrNull()
            }.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun addSaleEntry(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        customerName: String,
        dataGb: Double,
        priceToman: Long,
        originalPresetPriceToman: Long,
        presetName: String,
        isPaid: Boolean,
        note: String,
        isNewUser: Boolean = false,
        bonusGb: Double = 0.0,
        deterministicEntryId: String? = null
    ): Result<String> {
        requireUserId()
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Data volume must be between 0.1 and 100,000 GB."))
        }
        if (priceToman < 0L || priceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Sale price is out of valid Toman range."))
        }
        val safeBonus = if (isNewUser && bonusGb.isFinite()) bonusGb.coerceIn(0.0, dataGb) else 0.0
        val entryId = deterministicEntryId?.takeIf { it.isNotBlank() }
            ?: "sale_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")
            .document(entryId)

        val payload = mapOf(
            "ledgerOwnerUid" to ledgerOwnerUid,
            "workspaceAdminUid" to workspaceAdminUid,
            "customerName" to customerName.trim().take(100),
            "dataGb" to dataGb,
            "priceToman" to priceToman,
            "originalPresetPriceToman" to originalPresetPriceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
            "presetName" to presetName.trim().take(80),
            "isPaid" to isPaid,
            "isNewUser" to isNewUser,
            "bonusGb" to safeBonus,
            "note" to note.trim().take(300),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            awaitServerOrQueueOffline(docRef.set(payload), OperationType.CREATE, docRef.path)
            Result.success(entryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleSalePaidStatus(
        ledgerOwnerUid: String,
        entryId: String,
        isPaid: Boolean
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")
            .document(entryId)

        return try {
            awaitServerOrQueueOffline(
                docRef.update(
                    mapOf(
                        "isPaid" to isPaid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ),
                OperationType.UPDATE,
                docRef.path
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateSaleEntry(
        ledgerOwnerUid: String,
        entryId: String,
        customerName: String,
        dataGb: Double,
        priceToman: Long,
        originalPresetPriceToman: Long,
        presetName: String,
        isPaid: Boolean,
        note: String,
        isNewUser: Boolean = false,
        bonusGb: Double = 0.0
    ): Result<Unit> {
        requireUserId()
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Data volume must be between 0.1 and 100,000 GB."))
        }
        if (priceToman < 0L || priceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Sale price is out of valid Toman range."))
        }
        val safeBonus = if (isNewUser && bonusGb.isFinite()) bonusGb.coerceIn(0.0, dataGb) else 0.0
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")
            .document(entryId)

        return try {
            awaitServerOrQueueOffline(
                docRef.update(
                    mapOf(
                        "customerName" to customerName.trim().take(100),
                        "dataGb" to dataGb,
                        "priceToman" to priceToman,
                        "originalPresetPriceToman" to originalPresetPriceToman.coerceIn(0L, SaleEntry.MAX_ALLOWED_TOMAN),
                        "presetName" to presetName.trim().take(80),
                        "isPaid" to isPaid,
                        "isNewUser" to isNewUser,
                        "bonusGb" to safeBonus,
                        "note" to note.trim().take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ),
                OperationType.UPDATE,
                docRef.path
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteSaleEntry(
        ledgerOwnerUid: String,
        entryId: String
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")
            .document(entryId)

        return try {
            awaitServerOrQueueOffline(docRef.delete(), OperationType.DELETE, docRef.path)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    // --- 4. Reset Ledger, Archive Cycle & Seller Sent List (DATA-01, DATA-02, DATA-03) ---

    suspend fun resetAndArchiveLedger(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        ownerName: String,
        cycleTitle: String,
        activeEntries: List<SaleEntry>,
        keepArchive: Boolean,
        costPerGbToman: Long = 1_000L,
        adminProfitPercent: Int = 50,
        deterministicArchiveId: String? = null
    ): Result<String?> {
        requireUserId()
        // DATA-02: Never truncate entries above 500 while deleting the rest! Guard before any write.
        if (activeEntries.size > MAX_ARCHIVE_ITEMS) {
            return Result.failure(
                IllegalArgumentException(
                    "Active cycle has ${activeEntries.size} sales, exceeding the $MAX_ARCHIVE_ITEMS-record snapshot limit. Export a PDF first before splitting the cycle."
                )
            )
        }

        val entriesCollection = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("entries")

        val archivesCollection = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")

        return try {
            val batch = db.batch()
            var createdArchiveId: String? = null

            if (keepArchive && activeEntries.isNotEmpty()) {
                val archiveId = deterministicArchiveId?.takeIf { it.isNotBlank() }
                    ?: "arch_${UUID.randomUUID().toString().replace("-", "").take(20)}"
                createdArchiveId = archiveId
                val archiveRef = archivesCollection.document(archiveId)

                val totalSales = activeEntries.sumOf { it.safePriceToman }
                val paidSales = activeEntries.filter { it.isPaid }.sumOf { it.safePriceToman }
                val unpaidSales = (totalSales - paidSales).coerceAtLeast(0L)
                val totalGb = activeEntries.sumOf { it.safeDataGb }
                val itemsList = activeEntries.mapIndexed { idx, entry -> entry.toArchiveItemMap(idx) }
                val paidMap = itemsList.associate { item ->
                    (item["itemId"] as String) to (item["isPaid"] as Boolean)
                }
                val cleanOwnerName = TomanFormatter.sanitizeOwnerDisplayName(ownerName).take(100)

                val archivePayload = mapOf(
                    "ledgerOwnerUid" to ledgerOwnerUid,
                    "workspaceAdminUid" to workspaceAdminUid,
                    "ownerName" to cleanOwnerName.ifBlank { "Seller" },
                    "cycleTitle" to cycleTitle.trim().ifBlank { "Settlement Archive" }.take(120),
                    "totalSalesToman" to totalSales,
                    "paidToman" to paidSales,
                    "unpaidToman" to unpaidSales,
                    "totalGb" to totalGb,
                    "costPerGbToman" to costPerGbToman.coerceAtLeast(0L),
                    "adminProfitPercent" to adminProfitPercent.coerceIn(0, 100),
                    "entryCount" to itemsList.size,
                    "items" to itemsList,
                    "paidMap" to paidMap,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                batch.set(archiveRef, archivePayload)
            }

            // DATA-01: Delete the exact active entries captured in this reset batch
            for (entry in activeEntries) {
                if (entry.id.isNotBlank()) {
                    batch.delete(entriesCollection.document(entry.id))
                }
            }

            awaitServerOrQueueOffline(
                batch.commit(),
                OperationType.WRITE,
                "ledgers/$ledgerOwnerUid/archives"
            )
            Result.success(createdArchiveId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "ledgers/$ledgerOwnerUid/archives")
            Result.failure(e)
        }
    }

    fun observeLedgerArchives(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<LedgerArchive>> {
        val path = "ledgers/$ledgerOwnerUid/archives"
        return db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents
                    .filter { it.id != SUBMITTED_CURRENT_DOC_ID }
                    .mapNotNull { doc ->
                        doc.toLedgerArchiveOrNull()
                    }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    fun observeSubmittedSellerList(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<LedgerArchive?> {
        val path = "ledgers/$ledgerOwnerUid/archives/$SUBMITTED_CURRENT_DOC_ID"
        return db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")
            .document(SUBMITTED_CURRENT_DOC_ID)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.toLedgerArchiveOrNull()
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
                throw error
            }
    }

    suspend fun submitCurrentListToAdmin(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        ownerName: String,
        activeEntries: List<SaleEntry>,
        costPerGbToman: Long = 1_000L,
        adminProfitPercent: Int = 50
    ): Result<WriteAckStatus> {
        requireUserId()
        if (activeEntries.size > MAX_ARCHIVE_ITEMS) {
            return Result.failure(
                IllegalArgumentException(
                    "Current list has ${activeEntries.size} sales, exceeding the $MAX_ARCHIVE_ITEMS-record snapshot limit."
                )
            )
        }
        val archivesCol = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")
        val currentPointerRef = archivesCol.document(SUBMITTED_CURRENT_DOC_ID)

        val totalSales = activeEntries.sumOf { it.safePriceToman }
        val paidSales = activeEntries.filter { it.isPaid }.sumOf { it.safePriceToman }
        val unpaidSales = (totalSales - paidSales).coerceAtLeast(0L)
        val totalGb = activeEntries.sumOf { it.safeDataGb }
        val itemsList = activeEntries.mapIndexed { idx, entry -> entry.toArchiveItemMap(idx) }
        val paidMap = itemsList.associate { item ->
            (item["itemId"] as String) to (item["isPaid"] as Boolean)
        }
        val cleanOwnerName = TomanFormatter.sanitizeOwnerDisplayName(ownerName).take(100)

        val payload = mapOf(
            "ledgerOwnerUid" to ledgerOwnerUid,
            "workspaceAdminUid" to workspaceAdminUid,
            "ownerName" to cleanOwnerName.ifBlank { "Seller" },
            "cycleTitle" to "Current Sent List",
            "totalSalesToman" to totalSales,
            "paidToman" to paidSales,
            "unpaidToman" to unpaidSales,
            "totalGb" to totalGb,
            "costPerGbToman" to costPerGbToman.coerceAtLeast(0L),
            "adminProfitPercent" to adminProfitPercent.coerceIn(0, 100),
            "entryCount" to itemsList.size,
            "items" to itemsList,
            "paidMap" to paidMap,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            val status = awaitServerOrQueueOffline(
                currentPointerRef.set(payload),
                OperationType.WRITE,
                currentPointerRef.path
            )
            Result.success(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, currentPointerRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateArchiveItemsPaidStatus(
        ledgerOwnerUid: String,
        archiveId: String,
        updatedItems: List<ArchivedSaleItem>
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")
            .document(archiveId)

        val paidSales = updatedItems.filter { it.isPaid }.sumOf { it.priceToman.coerceAtLeast(0L) }
        val totalSales = updatedItems.sumOf { it.priceToman.coerceAtLeast(0L) }
        val unpaidSales = (totalSales - paidSales).coerceAtLeast(0L)
        val serializedItems = updatedItems.mapIndexed { idx, item -> item.toMap(idx) }
        val paidMap = serializedItems.associate { item ->
            (item["itemId"] as String) to (item["isPaid"] as Boolean)
        }

        return try {
            awaitServerOrQueueOffline(
                docRef.update(
                    mapOf(
                        "paidToman" to paidSales,
                        "unpaidToman" to unpaidSales,
                        "items" to serializedItems,
                        "paidMap" to paidMap
                    )
                ),
                OperationType.UPDATE,
                docRef.path
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteArchive(
        ledgerOwnerUid: String,
        archiveId: String
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("archives")
            .document(archiveId)

        return try {
            awaitServerOrQueueOffline(docRef.delete(), OperationType.DELETE, docRef.path)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    companion object {
        const val SUBMITTED_CURRENT_DOC_ID = "submitted_current"
        const val MAX_ARCHIVE_ITEMS = 500
        private const val SERVER_ACK_WAIT_MS = 2_500L
        private const val BOOTSTRAP_NET_TIMEOUT_MS = 4_000L
    }

    // --- 5. Custom Data Volume (GB), New User Bonus GB & Toman Presets ---

    fun observeCustomPresets(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<DataPreset>> {
        val path = "ledgers/$ledgerOwnerUid/presets"
        return db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("presets")
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toDataPresetOrNull()
                }.sortedBy { it.dataGb }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun saveOrUpdatePreset(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        existingPresetId: String?,
        label: String,
        dataGb: Double,
        defaultPriceToman: Long,
        newUserBonusGb: Double = 0.0
    ): Result<String> {
        requireUserId()
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Preset GB must be between 0.1 and 100,000 GB."))
        }
        if (defaultPriceToman < 0L || defaultPriceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Preset price is out of valid Toman range."))
        }
        val presetId = existingPresetId?.takeIf { it.isNotBlank() }
            ?: "preset_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("presets")
            .document(presetId)

        val cleanLabel = label.trim().take(80)
        val cleanBonusGb = if (newUserBonusGb.isFinite()) newUserBonusGb.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB) else 0.0

        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "label" to cleanLabel,
                        "dataGb" to dataGb,
                        "defaultPriceToman" to defaultPriceToman,
                        "newUserBonusGb" to cleanBonusGb,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val payload = mapOf(
                    "ledgerOwnerUid" to ledgerOwnerUid,
                    "workspaceAdminUid" to workspaceAdminUid,
                    "label" to cleanLabel,
                    "dataGb" to dataGb,
                    "defaultPriceToman" to defaultPriceToman,
                    "newUserBonusGb" to cleanBonusGb,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun addCustomPreset(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        label: String,
        dataGb: Double,
        defaultPriceToman: Long,
        newUserBonusGb: Double = 0.0
    ): Result<String> {
        return saveOrUpdatePreset(
            ledgerOwnerUid = ledgerOwnerUid,
            workspaceAdminUid = workspaceAdminUid,
            existingPresetId = null,
            label = label,
            dataGb = dataGb,
            defaultPriceToman = defaultPriceToman,
            newUserBonusGb = newUserBonusGb
        )
    }

    suspend fun deleteCustomPreset(
        ledgerOwnerUid: String,
        presetId: String
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("ledgers")
            .document(ledgerOwnerUid)
            .collection("presets")
            .document(presetId)

        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
