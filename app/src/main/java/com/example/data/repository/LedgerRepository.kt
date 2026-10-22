package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ApprovedEmail
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
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class WriteAckStatus {
    SYNCED_SERVER,
    SAVED_OFFLINE_PENDING_SYNC
}

/** SEC-04: The workspace is created once by the owner in the Firebase console, never by the app. */
class WorkspaceNotConfiguredException(val signedInUid: String) :
    IllegalStateException("This NodeSplit workspace has not been set up yet.")

/** DATA-02: Another device changed or removed a sale between the screen snapshot and the reset. */
class ConcurrentLedgerEditException :
    IllegalStateException("Sales changed on another device while starting the new cycle. Nothing was archived or deleted. Check the list and try again.")

@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun ledgerCollection(ledgerOwnerUid: String, collection: String) =
        db.collection("ledgers").document(ledgerOwnerUid).collection(collection)

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

    // --- 1. Workspace, Real-Time Config & User Profile Resolution ---

    suspend fun getOrBootstrapWorkspaceAndProfile(
        uid: String,
        email: String,
        displayName: String
    ): Result<Pair<WorkspaceConfig, UserProfile>> {
        val safeEmail = TomanFormatter.canonicalizeEmail(email)
        val newEmailKey = TomanFormatter.invitationKeyFor(safeEmail)
        val safeName = displayName.trim().ifBlank { safeEmail.substringBefore("@").ifBlank { "VPN Seller" } }.take(90)

        return try {
            val workspaceSnap = try {
                getDocumentWithOfflineFallback("config", "workspace")
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw e
                db.collection("config").document("workspace").get().await()
            }

            if (!workspaceSnap.exists()) {
                // A cache miss while offline is not proof that the workspace is missing.
                if (workspaceSnap.metadata.isFromCache) {
                    return Result.failure(
                        IllegalStateException("Connect to the internet to finish the first sign-in on this device.")
                    )
                }
                return Result.failure(WorkspaceNotConfiguredException(uid))
            }

            val workspace = workspaceSnap.toWorkspaceConfigOrNull()
                ?: return Result.failure(
                    IllegalStateException("Workspace configuration could not be loaded. Connect to the internet to finish initial setup.")
                )
            if (workspace.adminUid.isBlank()) {
                return Result.failure(WorkspaceNotConfiguredException(uid))
            }

            val adminUid = workspace.adminUid
            // SEC-04: Admin authority is bound strictly to workspace.adminUid (no hardcoded email backdoors)
            val isUserWorkspaceAdmin = (uid == adminUid)

            val userRef = db.collection("users").document(uid)
            val userSnap = try {
                getDocumentWithOfflineFallback("users", uid)
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw e
                userRef.get().await()
            }

            if (userSnap.exists()) {
                val existingProfile = userSnap.toUserProfileOrNull()
                    ?: return Result.failure(IllegalStateException("Account profile data is invalid."))

                // SEC-03: Only a PENDING seller can be upgraded via an invitation; REVOKED never self-reverses
                if (existingProfile.role == "SELLER" && existingProfile.status == "PENDING" && safeEmail.isNotBlank()) {
                    val invitation = findApprovedInvitation(safeEmail)
                    if (invitation != null) {
                        userRef.update(
                            mapOf(
                                "status" to "APPROVED",
                                "emailKey" to invitation.emailKey,
                                "displayName" to invitation.sellerName.ifBlank { existingProfile.displayName }.take(90),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                        val updatedProfile = userRef.get().await().toUserProfileOrNull()
                            ?: existingProfile.copy(status = "APPROVED", emailKey = invitation.emailKey)
                        return Result.success(workspace to updatedProfile)
                    }
                }
                Result.success(workspace to existingProfile)
            } else {
                if (userSnap.metadata.isFromCache) {
                    return Result.failure(
                        IllegalStateException("Connect to the internet to finish the first sign-in on this device.")
                    )
                }
                if (safeEmail.isBlank() || newEmailKey == null) {
                    return Result.failure(
                        IllegalStateException("This Google account has no usable email address. Sign in with a different Google account.")
                    )
                }
                // Check whether the Admin already invited this Google email before creating the profile
                val invitation = if (!isUserWorkspaceAdmin) findApprovedInvitation(safeEmail) else null

                val initialRole = if (isUserWorkspaceAdmin) "ADMIN" else "SELLER"
                val initialStatus = if (isUserWorkspaceAdmin || invitation != null) "APPROVED" else "PENDING"
                val effectiveEmailKey = invitation?.emailKey ?: newEmailKey
                val finalDisplayName = invitation?.sellerName?.takeIf { it.isNotBlank() }?.take(90) ?: safeName

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

                val createdProfile = userRef.get().await().toUserProfileOrNull()
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

    /**
     * Looks up an APPROVED invitation for the signed-in email under the current key scheme and the
     * two older ones. The rules only let a seller read their own invitation, so a lookup for any
     * other key is denied (and ignored here).
     */
    private suspend fun findApprovedInvitation(canonicalEmail: String): ApprovedEmail? {
        for (key in TomanFormatter.candidateInvitationKeys(canonicalEmail)) {
            val invitation = try {
                db.collection("approved_emails").document(key).get().await().toApprovedEmailOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (invitation != null &&
                TomanFormatter.canonicalizeEmail(invitation.email) == canonicalEmail &&
                invitation.status == "APPROVED"
            ) {
                return invitation.copy(emailKey = key)
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
        val docRef = db.collection("config").document("workspace")
        return try {
            requireUserId()
            docRef.update(
                mapOf(
                    "costPerGbToman" to costPerGbToman.coerceIn(0L, 1_000_000_000L),
                    "adminProfitPercent" to adminProfitPercent.coerceIn(0, 100),
                    "settingsRevision" to FieldValue.increment(1),
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

    /**
     * Admin approves a Google email. The invitation (under the canonical email key) and any matching
     * seller profile are written in one batch, and any copy under an old key scheme is removed in
     * that same batch (SEC-02 / SEC-03).
     */
    suspend fun addOrApproveSellerEmail(
        workspaceAdminUid: String,
        email: String,
        sellerName: String
    ): Result<String> {
        val cleanEmail = TomanFormatter.canonicalizeEmail(email)
        val emailKey = TomanFormatter.invitationKeyFor(cleanEmail)
            ?: return Result.failure(IllegalArgumentException("Enter a valid Google email address."))
        val cleanName = sellerName.trim().ifBlank { cleanEmail.substringBefore("@") }.take(90)
        val docRef = db.collection("approved_emails").document(emailKey)

        return try {
            requireUserId()
            val batch = db.batch()
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                batch.update(
                    docRef,
                    mapOf(
                        "sellerName" to cleanName,
                        "status" to "APPROVED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                batch.set(
                    docRef,
                    mapOf(
                        "emailKey" to emailKey,
                        "email" to cleanEmail,
                        "sellerName" to cleanName,
                        "workspaceAdminUid" to workspaceAdminUid,
                        "status" to "APPROVED",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            deleteLegacyInvitationCopies(batch, cleanEmail, keepKey = emailKey)

            // Approve a seller who already signed in with exactly this email.
            val matchingUsers = db.collection("users")
                .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
                .get()
                .await()
            for (userDoc in matchingUsers.documents) {
                val docEmail = TomanFormatter.canonicalizeEmail(userDoc.getString("email") ?: "")
                if (docEmail == cleanEmail && userDoc.getString("role") == "SELLER") {
                    batch.update(
                        userDoc.reference,
                        mapOf(
                            "status" to "APPROVED",
                            "displayName" to cleanName,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(emailKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    private suspend fun deleteLegacyInvitationCopies(
        batch: WriteBatch,
        canonicalEmail: String,
        keepKey: String
    ) {
        for (oldKey in TomanFormatter.candidateInvitationKeys(canonicalEmail)) {
            if (oldKey == keepKey) continue
            val oldRef = db.collection("approved_emails").document(oldKey)
            val oldSnap = oldRef.get().await()
            // Only remove an old document that really belongs to this address (old keys could collide).
            if (oldSnap.exists() && TomanFormatter.canonicalizeEmail(oldSnap.getString("email") ?: "") == canonicalEmail) {
                batch.delete(oldRef)
            }
        }
    }

    /**
     * One-time, idempotent move of invitations stored under the old collision-prone keys to the
     * canonical email key. Only the stored email decides the new key, so two addresses that shared an
     * old key are never both approved by copying one document.
     */
    suspend fun migrateLegacyInvitationKeys(workspaceAdminUid: String): Result<Int> {
        return try {
            requireUserId()
            val snap = db.collection("approved_emails")
                .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
                .get(Source.SERVER)
                .await()
            var moved = 0
            val batch = db.batch()
            for (doc in snap.documents) {
                val invitation = doc.toApprovedEmailOrNull() ?: continue
                val newKey = TomanFormatter.invitationKeyFor(invitation.email) ?: continue
                if (doc.id == newKey) continue
                val newRef = db.collection("approved_emails").document(newKey)
                val alreadyMoved = snap.documents.any { it.id == newKey }
                if (!alreadyMoved) {
                    batch.set(
                        newRef,
                        mapOf(
                            "emailKey" to newKey,
                            "email" to TomanFormatter.canonicalizeEmail(invitation.email),
                            "sellerName" to invitation.sellerName.ifBlank { invitation.email.substringBefore("@") }.take(90),
                            "workspaceAdminUid" to workspaceAdminUid,
                            "status" to invitation.status,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.delete(doc.reference)
                moved++
            }
            if (moved > 0) batch.commit().await()
            Result.success(moved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "approved_emails")
            Result.failure(e)
        }
    }

    /** Admin approves or revokes a registered seller. Profile and invitation change atomically. */
    suspend fun updateSellerStatus(
        sellerUid: String,
        emailKey: String,
        email: String,
        sellerName: String,
        workspaceAdminUid: String,
        approve: Boolean
    ): Result<Unit> {
        val newStatus = if (approve) "APPROVED" else "REVOKED"
        val userRef = db.collection("users").document(sellerUid)
        return try {
            requireUserId()
            val cleanEmail = TomanFormatter.canonicalizeEmail(email)
            val batch = db.batch()

            batch.update(
                userRef,
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            val invitationKey = TomanFormatter.invitationKeyFor(cleanEmail)
            if (invitationKey != null) {
                val emailRef = db.collection("approved_emails").document(invitationKey)
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
                            "emailKey" to invitationKey,
                            "email" to cleanEmail,
                            "sellerName" to sellerName.ifBlank { cleanEmail.substringBefore("@") }.take(90),
                            "workspaceAdminUid" to workspaceAdminUid,
                            "status" to newStatus,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                // An old-scheme invitation must not be left APPROVED next to a revoked profile.
                deleteLegacyInvitationCopies(batch, cleanEmail, keepKey = invitationKey)
            } else if (emailKey.isNotBlank()) {
                val legacyRef = db.collection("approved_emails").document(emailKey)
                if (legacyRef.get().await().exists()) {
                    batch.update(legacyRef, mapOf("status" to newStatus, "updatedAt" to FieldValue.serverTimestamp()))
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

    /** Cancels an invitation. It does not revoke a seller who already signed in (use Revoke for that). */
    suspend fun removeApprovedEmail(emailKey: String): Result<Unit> {
        val docRef = db.collection("approved_emails").document(emailKey)
        return try {
            requireUserId()
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
        return ledgerCollection(ledgerOwnerUid, "entries")
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
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
            val snap = ledgerCollection(ledgerOwnerUid, "entries")
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
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Data volume must be between 0.1 and 100,000 GB."))
        }
        if (priceToman < 0L || priceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Sale price is out of valid Toman range."))
        }
        val safeBonus = if (isNewUser && bonusGb.isFinite()) bonusGb.coerceIn(0.0, dataGb) else 0.0
        // OFF-03: The id is fixed before the first attempt so a retry rewrites the same sale.
        val entryId = deterministicEntryId?.takeIf { it.isNotBlank() } ?: newSaleId()
        val docRef = ledgerCollection(ledgerOwnerUid, "entries").document(entryId)

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
            requireUserId()
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
        val docRef = ledgerCollection(ledgerOwnerUid, "entries").document(entryId)

        return try {
            requireUserId()
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
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Data volume must be between 0.1 and 100,000 GB."))
        }
        if (priceToman < 0L || priceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Sale price is out of valid Toman range."))
        }
        val safeBonus = if (isNewUser && bonusGb.isFinite()) bonusGb.coerceIn(0.0, dataGb) else 0.0
        val docRef = ledgerCollection(ledgerOwnerUid, "entries").document(entryId)

        return try {
            requireUserId()
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
        val docRef = ledgerCollection(ledgerOwnerUid, "entries").document(entryId)

        return try {
            requireUserId()
            awaitServerOrQueueOffline(docRef.delete(), OperationType.DELETE, docRef.path)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    // --- 4. Close Cycle, Archives & Sent Reports (DATA-01, DATA-02, DATA-03) ---

    private fun buildSnapshotPayload(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        ownerName: String,
        cycleTitle: String,
        entries: List<SaleEntry>,
        costPerGbToman: Long,
        adminProfitPercent: Int,
        settingsRevision: Long
    ): Map<String, Any> {
        val totalSales = entries.sumOf { it.safePriceToman }
        val paidSales = entries.filter { it.isPaid }.sumOf { it.safePriceToman }
        val itemsList = entries.mapIndexed { idx, entry -> entry.toArchiveItemMap(idx) }
        val paidMap = itemsList.associate { item ->
            (item["itemId"] as String) to (item["isPaid"] as Boolean)
        }
        val cleanOwnerName = TomanFormatter.sanitizeOwnerDisplayName(ownerName).take(100)
        return mapOf(
            "ledgerOwnerUid" to ledgerOwnerUid,
            "workspaceAdminUid" to workspaceAdminUid,
            "ownerName" to cleanOwnerName.ifBlank { "Seller" },
            "cycleTitle" to cycleTitle.trim().ifBlank { "Settlement Archive" }.take(120),
            "totalSalesToman" to totalSales,
            "paidToman" to paidSales,
            "unpaidToman" to (totalSales - paidSales).coerceAtLeast(0L),
            "totalGb" to entries.sumOf { it.safeDataGb },
            "costPerGbToman" to costPerGbToman.coerceIn(0L, 1_000_000_000L),
            "adminProfitPercent" to adminProfitPercent.coerceIn(0, 100),
            "settingsRevision" to settingsRevision.coerceAtLeast(1L),
            "entryCount" to itemsList.size,
            "items" to itemsList,
            "paidMap" to paidMap,
            "schemaVersion" to SNAPSHOT_SCHEMA_VERSION,
            "clientCreatedAtMillis" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp()
        )
    }

    private fun tooManyEntries(count: Int): Exception =
        IllegalArgumentException(
            "This cycle has $count sales, over the $MAX_ARCHIVE_ITEMS-sale snapshot limit. Nothing was changed. Export a PDF first, then contact the developer to raise the limit."
        )

    /**
     * Starts a new cycle. When online the archive is written in a transaction that re-reads every
     * sale and aborts if another device changed or removed one (DATA-02). Offline, a single atomic
     * batch is queued instead (Firestore cannot run transactions offline). Only the captured sale
     * ids are deleted, so a sale added concurrently stays in the new cycle.
     */
    suspend fun resetAndArchiveLedger(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        ownerName: String,
        cycleTitle: String,
        activeEntries: List<SaleEntry>,
        keepArchive: Boolean,
        costPerGbToman: Long = 1_000L,
        adminProfitPercent: Int = 50,
        settingsRevision: Long = 1L,
        deterministicArchiveId: String? = null
    ): Result<String?> {
        // DATA-02: Never truncate. Reject before any write.
        if (activeEntries.size > MAX_ARCHIVE_ITEMS) {
            return Result.failure(tooManyEntries(activeEntries.size))
        }
        val path = "ledgers/$ledgerOwnerUid/archives"
        return try {
            val currentUid = requireUserId()
            val entriesCollection = ledgerCollection(ledgerOwnerUid, "entries")
            val archiveId = if (keepArchive && activeEntries.isNotEmpty()) {
                deterministicArchiveId?.takeIf { it.isNotBlank() } ?: newArchiveId()
            } else {
                null
            }
            val archiveRef = archiveId?.let { ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_ARCHIVES).document(it) }
            val archivePayload = archiveId?.let {
                buildSnapshotPayload(
                    ledgerOwnerUid, workspaceAdminUid, ownerName, cycleTitle, activeEntries,
                    costPerGbToman, adminProfitPercent, settingsRevision
                )
            }
            // A seller's new cycle clears the "current report" pointer so the admin no longer sees
            // the previous report as this seller's active book (the report itself stays in history).
            val pointerRef = if (currentUid == ledgerOwnerUid && ledgerOwnerUid != workspaceAdminUid) {
                ledgerCollection(ledgerOwnerUid, "pointers").document(LATEST_SUBMISSION_POINTER_ID)
            } else {
                null
            }
            val pointerPayload = mapOf(
                "ledgerOwnerUid" to ledgerOwnerUid,
                "workspaceAdminUid" to workspaceAdminUid,
                "submissionId" to "",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val validEntries = activeEntries.filter { it.id.isNotBlank() }
            val canVerifyOnServer = validEntries.none { it.hasPendingSync }

            val ranTransaction = canVerifyOnServer && try {
                withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                    db.runTransaction { tx ->
                        for (entry in validEntries) {
                            val serverCopy = tx.get(entriesCollection.document(entry.id)).toSaleEntryOrNull()
                            if (serverCopy == null || serverCopy.copy(hasPendingSync = false) != entry.copy(hasPendingSync = false)) {
                                throw ConcurrentLedgerEditException()
                            }
                        }
                        if (archiveRef != null && archivePayload != null) tx.set(archiveRef, archivePayload)
                        for (entry in validEntries) tx.delete(entriesCollection.document(entry.id))
                        if (pointerRef != null) tx.set(pointerRef, pointerPayload)
                        null
                    }.await()
                    true
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ConcurrentLedgerEditException || e.cause is ConcurrentLedgerEditException) {
                    throw ConcurrentLedgerEditException()
                }
                // Offline: fall back to a queued batch. Any other failure is real and is reported.
                if ((e as? FirebaseFirestoreException)?.code != FirebaseFirestoreException.Code.UNAVAILABLE) throw e
                false
            }

            if (!ranTransaction) {
                val batch = db.batch()
                if (archiveRef != null && archivePayload != null) batch.set(archiveRef, archivePayload)
                for (entry in validEntries) batch.delete(entriesCollection.document(entry.id))
                if (pointerRef != null) batch.set(pointerRef, pointerPayload)
                awaitServerOrQueueOffline(batch.commit(), OperationType.WRITE, path)
            }
            Result.success(archiveId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConcurrentLedgerEditException) {
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeLedgerArchives(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<LedgerArchive>> {
        val path = "ledgers/$ledgerOwnerUid/archives"
        return ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_ARCHIVES)
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents
                    .filter { it.id != LedgerArchive.LEGACY_SUBMITTED_DOC_ID }
                    .mapNotNull { doc -> doc.toLedgerArchiveOrNull(LedgerArchive.COLLECTION_ARCHIVES) }
                    .sortedWith(compareBy<LedgerArchive> { it.isVoided }.thenByDescending { it.createdAt ?: Timestamp(0, 0) })
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    /**
     * The report the seller most recently sent. Follows the pointer document; before the first
     * report under this version it falls back to the legacy `submitted_current` document, and after
     * a new cycle starts (empty pointer) there is no current report.
     */
    fun observeLatestSubmission(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<LedgerArchive?> {
        val pointerPath = "ledgers/$ledgerOwnerUid/pointers/$LATEST_SUBMISSION_POINTER_ID"
        return ledgerCollection(ledgerOwnerUid, "pointers")
            .document(LATEST_SUBMISSION_POINTER_ID)
            .snapshots(MetadataChanges.INCLUDE)
            .flatMapLatest { pointer ->
                val submissionId = pointer.getString("submissionId")
                when {
                    !pointer.exists() -> ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_ARCHIVES)
                        .document(LedgerArchive.LEGACY_SUBMITTED_DOC_ID)
                        .snapshots(MetadataChanges.INCLUDE)
                        .map { it.toLedgerArchiveOrNull(LedgerArchive.COLLECTION_ARCHIVES) }
                    submissionId.isNullOrBlank() -> flowOf(null)
                    else -> ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_SUBMISSIONS)
                        .document(submissionId)
                        .snapshots(MetadataChanges.INCLUDE)
                        .map { it.toLedgerArchiveOrNull(LedgerArchive.COLLECTION_SUBMISSIONS) }
                }
            }
            .map { report -> report?.takeIf { it.workspaceAdminUid.isBlank() || it.workspaceAdminUid == workspaceAdminUid } }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, pointerPath)
                throw error
            }
    }

    /** Every report this seller has sent, newest first. */
    fun observeSubmissionHistory(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<LedgerArchive>> {
        val path = "ledgers/$ledgerOwnerUid/submissions"
        return ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_SUBMISSIONS)
            .whereEqualTo("ledgerOwnerUid", ledgerOwnerUid)
            .whereEqualTo("workspaceAdminUid", workspaceAdminUid)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { it.toLedgerArchiveOrNull(LedgerArchive.COLLECTION_SUBMISSIONS) }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    /**
     * OFF-04 / DATA-01: Freezes the working list as a new, uniquely identified report and points the
     * Admin view at it, in one atomic batch. Earlier reports are never overwritten. Pass the same
     * [submissionId] when retrying so a retry cannot create a duplicate.
     */
    suspend fun submitCurrentListToAdmin(
        ledgerOwnerUid: String,
        workspaceAdminUid: String,
        ownerName: String,
        activeEntries: List<SaleEntry>,
        costPerGbToman: Long = 1_000L,
        adminProfitPercent: Int = 50,
        settingsRevision: Long = 1L,
        submissionId: String = newSubmissionId()
    ): Result<WriteAckStatus> {
        if (activeEntries.size > MAX_ARCHIVE_ITEMS) {
            return Result.failure(tooManyEntries(activeEntries.size))
        }
        val submissionRef = ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_SUBMISSIONS).document(submissionId)
        return try {
            requireUserId()
            val payload = buildSnapshotPayload(
                ledgerOwnerUid, workspaceAdminUid, ownerName, "Sales report", activeEntries,
                costPerGbToman, adminProfitPercent, settingsRevision
            )
            val batch = db.batch()
            batch.set(submissionRef, payload)
            batch.set(
                ledgerCollection(ledgerOwnerUid, "pointers").document(LATEST_SUBMISSION_POINTER_ID),
                mapOf(
                    "ledgerOwnerUid" to ledgerOwnerUid,
                    "workspaceAdminUid" to workspaceAdminUid,
                    "submissionId" to submissionId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val status = awaitServerOrQueueOffline(batch.commit(), OperationType.WRITE, submissionRef.path)
            Result.success(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, submissionRef.path)
            Result.failure(e)
        }
    }

    /** DATA-03: Changes exactly one item's customer-payment checkbox in a frozen snapshot. */
    suspend fun updateSnapshotItemPaidStatus(
        ledgerOwnerUid: String,
        collection: String,
        snapshotId: String,
        itemId: String,
        isPaid: Boolean
    ): Result<Unit> {
        val docRef = ledgerCollection(ledgerOwnerUid, collection).document(snapshotId)
        return try {
            requireUserId()
            require(collection == LedgerArchive.COLLECTION_ARCHIVES || collection == LedgerArchive.COLLECTION_SUBMISSIONS)
            require(snapshotId != LedgerArchive.LEGACY_SUBMITTED_DOC_ID) { "This older report is read-only." }
            require(itemId.isNotBlank()) { "This sale has no id and cannot be updated." }
            awaitServerOrQueueOffline(
                docRef.update(
                    FieldPath.of("paidMap", itemId), isPaid,
                    "updatedAt", FieldValue.serverTimestamp()
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

    /** DATA-03: Archives are voided with a reason instead of deleted; the record is kept. */
    suspend fun voidArchive(
        ledgerOwnerUid: String,
        archiveId: String,
        reason: String
    ): Result<Unit> {
        val docRef = ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_ARCHIVES).document(archiveId)
        val cleanReason = reason.trim().take(300)
        return try {
            val uid = requireUserId()
            require(cleanReason.length >= 3) { "Enter a reason of at least 3 characters." }
            awaitServerOrQueueOffline(
                docRef.update(
                    mapOf(
                        "voided" to true,
                        "voidReason" to cleanReason,
                        "voidedBy" to uid,
                        "voidedAt" to FieldValue.serverTimestamp(),
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

    /** Admin marks a received report as settled (or reopens it). */
    suspend fun setSubmissionSettled(
        ledgerOwnerUid: String,
        submissionId: String,
        settled: Boolean
    ): Result<Unit> {
        val docRef = ledgerCollection(ledgerOwnerUid, LedgerArchive.COLLECTION_SUBMISSIONS).document(submissionId)
        return try {
            requireUserId()
            awaitServerOrQueueOffline(
                docRef.update(
                    mapOf(
                        "adminStatus" to if (settled) LedgerArchive.ADMIN_STATUS_SETTLED else LedgerArchive.ADMIN_STATUS_RECEIVED,
                        "adminStatusUpdatedAt" to FieldValue.serverTimestamp(),
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

    companion object {
        const val MAX_ARCHIVE_ITEMS = 500
        const val LATEST_SUBMISSION_POINTER_ID = "latest_submission"
        private const val SNAPSHOT_SCHEMA_VERSION = 2
        private const val SERVER_ACK_WAIT_MS = 2_500L
        private const val BOOTSTRAP_NET_TIMEOUT_MS = 4_000L
        private const val TRANSACTION_TIMEOUT_MS = 10_000L

        private fun randomSuffix(length: Int) = UUID.randomUUID().toString().replace("-", "").take(length)

        fun newSaleId(): String = "sale_${randomSuffix(20)}"
        fun newArchiveId(): String = "arch_${randomSuffix(20)}"
        fun newSubmissionId(): String = "sub_${System.currentTimeMillis()}_${randomSuffix(8)}"
    }

    // --- 5. Custom Data Volume (GB), New User Bonus GB & Toman Presets ---

    fun observeCustomPresets(
        ledgerOwnerUid: String,
        workspaceAdminUid: String
    ): Flow<List<DataPreset>> {
        val path = "ledgers/$ledgerOwnerUid/presets"
        return ledgerCollection(ledgerOwnerUid, "presets")
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
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            return Result.failure(IllegalArgumentException("Preset GB must be between 0.1 and 100,000 GB."))
        }
        if (defaultPriceToman < 0L || defaultPriceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            return Result.failure(IllegalArgumentException("Preset price is out of valid Toman range."))
        }
        val presetId = existingPresetId?.takeIf { it.isNotBlank() }
            ?: "preset_${randomSuffix(16)}"
        val docRef = ledgerCollection(ledgerOwnerUid, "presets").document(presetId)

        val cleanLabel = label.trim().take(80)
        val cleanBonusGb = if (newUserBonusGb.isFinite()) newUserBonusGb.coerceIn(0.0, SaleEntry.MAX_ALLOWED_GB) else 0.0

        return try {
            requireUserId()
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
        val docRef = ledgerCollection(ledgerOwnerUid, "presets").document(presetId)

        return try {
            requireUserId()
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
