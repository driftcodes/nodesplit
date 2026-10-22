package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ApprovedEmail
import com.example.data.model.DataPreset
import com.example.data.model.FinancialCalculator
import com.example.data.model.LedgerArchive
import com.example.data.model.LedgerSummaryMetrics
import com.example.data.model.LedgerTargetBook
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import com.example.data.model.UserProfile
import com.example.data.model.WorkspaceConfig
import com.example.data.repository.ConcurrentLedgerEditException
import com.example.data.repository.LedgerRepository
import com.example.data.repository.WorkspaceNotConfiguredException
import com.example.data.repository.WriteAckStatus
import com.example.util.GraphicalPdfExporter
import com.example.util.PdfReportPayload
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

sealed interface WorkspaceSessionState {
    data object Loading : WorkspaceSessionState
    data class Ready(
        val workspace: WorkspaceConfig,
        val profile: UserProfile
    ) : WorkspaceSessionState

    /** SEC-04: config/workspace does not exist yet; the owner must create it once in the console. */
    data class NotConfigured(val signedInUid: String) : WorkspaceSessionState
    data class Error(val message: String) : WorkspaceSessionState
}

data class ActivePdfExportReady(
    val file: File,
    val title: String,
    val payload: PdfReportPayload
)

/** REL-02: Entries are always tagged with the book they were loaded for. */
private data class BookEntries(
    val ownerUid: String,
    val entries: List<SaleEntry>
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(
    private val repository: LedgerRepository,
    private val currentUserId: String,
    private val currentUserEmail: String,
    private val currentUserDisplayName: String
) : ViewModel() {

    private val _sessionState = MutableStateFlow<WorkspaceSessionState>(WorkspaceSessionState.Loading)
    val sessionState: StateFlow<WorkspaceSessionState> = _sessionState.asStateFlow()

    // REL-01: Single tracked coroutine job for live workspace/profile listeners to prevent duplicate collectors
    private var liveSessionJob: Job? = null
    private var invitationMigrationStarted = false

    // Forces all Firestore snapshot flows to re-attach cleanly when session re-initializes
    private val _refreshNonce = MutableStateFlow(0)

    // Which ledger book is currently selected (for Admin: either Private Admin Book or a Seller's Book; for Seller: always their own book)
    private val _selectedLedgerOwnerUid = MutableStateFlow(currentUserId)
    val selectedLedgerOwnerUid: StateFlow<String> = _selectedLedgerOwnerUid.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _readyPdfExport = MutableStateFlow<ActivePdfExportReady?>(null)
    val readyPdfExport: StateFlow<ActivePdfExportReady?> = _readyPdfExport.asStateFlow()

    // REL-03: A failed query is shown as an error, never as an empty (zero) ledger.
    private val _ledgerLoadError = MutableStateFlow<String?>(null)
    val ledgerLoadError: StateFlow<String?> = _ledgerLoadError.asStateFlow()

    private val _archiveLoadError = MutableStateFlow<String?>(null)
    val archiveLoadError: StateFlow<String?> = _archiveLoadError.asStateFlow()

    init {
        initializeWorkspaceAndProfile()
    }

    fun initializeWorkspaceAndProfile() {
        liveSessionJob?.cancel()
        _sessionState.value = WorkspaceSessionState.Loading
        _refreshNonce.value += 1
        liveSessionJob = viewModelScope.launch {
            val result = repository.getOrBootstrapWorkspaceAndProfile(
                uid = currentUserId,
                email = currentUserEmail,
                displayName = currentUserDisplayName
            )
            result.fold(
                onSuccess = { (workspace, initialProfile) ->
                    _sessionState.value = WorkspaceSessionState.Ready(workspace, initialProfile)
                    observeLiveWorkspaceAndProfile(workspace, initialProfile)
                },
                onFailure = { error ->
                    _sessionState.value = when {
                        error is WorkspaceNotConfiguredException -> WorkspaceSessionState.NotConfigured(error.signedInUid)
                        else -> WorkspaceSessionState.Error(describeError(error, "Failed to load workspace configuration."))
                    }
                }
            )
        }
    }

    private suspend fun observeLiveWorkspaceAndProfile(
        initialWorkspace: WorkspaceConfig,
        initialProfile: UserProfile
    ) {
        var lastKnownWorkspace = initialWorkspace
        var lastKnownProfile = initialProfile
        var profileAccessDenied = false
        combine(
            repository.observeWorkspaceConfig().catch { emit(lastKnownWorkspace) },
            repository.observeUserProfile(currentUserId).catch { error ->
                // REL-03 / SEC-03: A denial is never answered with the last approved profile.
                if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    profileAccessDenied = true
                    emit(null)
                } else {
                    emit(lastKnownProfile)
                }
            }
        ) { liveWorkspace, liveProfile ->
            val resolvedWorkspace = liveWorkspace ?: lastKnownWorkspace
            lastKnownWorkspace = resolvedWorkspace

            // SEC-03 / REL-03: If the user profile document was deleted on the server after bootstrap,
            // transition to REVOKED instead of resurrecting an old APPROVED profile.
            val resolvedProfile = liveProfile ?: lastKnownProfile.copy(status = "REVOKED")
            lastKnownProfile = resolvedProfile
            resolvedWorkspace to resolvedProfile
        }.collect { (ws, prof) ->
            _sessionState.value = if (profileAccessDenied) {
                WorkspaceSessionState.Error("The server denied access to your account. Sign out and sign in again, or contact the Admin.")
            } else {
                WorkspaceSessionState.Ready(ws, prof)
            }
            if (prof.isAdmin) startInvitationKeyMigration(ws.adminUid)
        }
    }

    private fun startInvitationKeyMigration(adminUid: String) {
        if (invitationMigrationStarted) return
        invitationMigrationStarted = true
        viewModelScope.launch {
            repository.migrateLegacyInvitationKeys(adminUid).onFailure { e ->
                // Retried on the next app start; old keys keep working meanwhile.
                Log.w(TAG, "Invitation key migration deferred: ${e.javaClass.simpleName}")
            }
        }
    }

    private val readySession: Flow<WorkspaceSessionState.Ready?> = _sessionState
        .map { it as? WorkspaceSessionState.Ready }

    // Observe all users in workspace (only active when current user is Admin)
    val workspaceUsers: StateFlow<List<UserProfile>> = combine(
        _sessionState,
        _refreshNonce
    ) { state, _ -> (state as? WorkspaceSessionState.Ready)?.takeIf { it.profile.isAdmin }?.workspace?.adminUid }
        .distinctUntilChanged()
        .flatMapLatest { adminUid ->
            if (adminUid != null) {
                repository.observeWorkspaceUsers(adminUid)
                    .catch { e ->
                        Log.w(TAG, "Failed to observe workspace users", e)
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Observe Admin-approved seller emails roster (only active when current user is Admin)
    val approvedEmails: StateFlow<List<ApprovedEmail>> = combine(
        _sessionState,
        _refreshNonce
    ) { state, _ -> (state as? WorkspaceSessionState.Ready)?.takeIf { it.profile.isAdmin }?.workspace?.adminUid }
        .distinctUntilChanged()
        .flatMapLatest { adminUid ->
            if (adminUid != null) {
                repository.observeApprovedSellerEmails(adminUid)
                    .catch { e ->
                        Log.w(TAG, "Failed to observe approved emails", e)
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Available books for the Admin Switcher (or single book for Seller)
    val availableBooks: StateFlow<List<LedgerTargetBook>> = combine(
        _sessionState,
        workspaceUsers
    ) { state, users ->
        if (state !is WorkspaceSessionState.Ready) {
            emptyList()
        } else if (state.profile.isAdmin) {
            val adminCleanName = state.profile.displayName
                .takeIf { it.isNotBlank() && !it.contains("@") }
                ?: "Admin"
            val adminBook = LedgerTargetBook(
                ownerUid = state.profile.userId,
                title = "My private book",
                subtitle = "Only you can see this book",
                cleanOwnerName = adminCleanName,
                isPrivateAdminBook = true
            )
            val sellerBooks = users
                .filter { it.userId != state.profile.userId && it.role == "SELLER" }
                .map { seller ->
                    val sellerCleanName = seller.displayName
                        .takeIf { it.isNotBlank() && !it.contains("@") }
                        ?: seller.email.substringBefore("@").ifBlank { "Seller" }
                    LedgerTargetBook(
                        ownerUid = seller.userId,
                        title = sellerCleanName,
                        subtitle = when (seller.status) {
                            "APPROVED" -> "Seller"
                            "REVOKED" -> "Seller (access revoked)"
                            else -> "Seller (waiting for approval)"
                        },
                        cleanOwnerName = sellerCleanName,
                        isPrivateAdminBook = false
                    )
                }
            listOf(adminBook) + sellerBooks
        } else {
            val sellerCleanName = state.profile.displayName
                .takeIf { it.isNotBlank() && !it.contains("@") }
                ?: state.profile.email.substringBefore("@").ifBlank { "Seller" }
            listOf(
                LedgerTargetBook(
                    ownerUid = state.profile.userId,
                    title = "My sales",
                    subtitle = sellerCleanName,
                    cleanOwnerName = sellerCleanName,
                    isPrivateAdminBook = false
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val currentTargetBook: StateFlow<LedgerTargetBook> = combine(
        availableBooks,
        _selectedLedgerOwnerUid,
        _sessionState
    ) { books, selectedUid, state ->
        // REL-02: If the selected seller disappears, fall back to the viewer's own book.
        books.firstOrNull { it.ownerUid == selectedUid }
            ?: books.firstOrNull { it.ownerUid == currentUserId }
            ?: books.firstOrNull()
            ?: LedgerTargetBook(
                ownerUid = currentUserId,
                title = if (state is WorkspaceSessionState.Ready && state.profile.isAdmin) "My private book" else "My sales",
                subtitle = "",
                cleanOwnerName = if (state is WorkspaceSessionState.Ready && state.profile.isAdmin) "Admin" else "Seller",
                isPrivateAdminBook = state is WorkspaceSessionState.Ready && state.profile.isAdmin
            )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        LedgerTargetBook(
            ownerUid = currentUserId,
            title = "My sales",
            subtitle = "",
            cleanOwnerName = "Seller",
            isPrivateAdminBook = false
        )
    )

    private val approvedBookKey: Flow<Pair<WorkspaceSessionState.Ready, String>?> = combine(
        readySession,
        currentTargetBook,
        _refreshNonce
    ) { ready, targetBook, nonce ->
        ready?.takeIf { it.profile.isApproved }?.let { Triple(it, targetBook.ownerUid, nonce) }
    }
        .distinctUntilChanged { old, new ->
            old?.second == new?.second && old?.third == new?.third &&
                old?.first?.workspace?.adminUid == new?.first?.workspace?.adminUid &&
                old?.first?.profile?.isAdmin == new?.first?.profile?.isAdmin
        }
        .map { triple -> triple?.let { it.first to it.second } }

    // The report the selected book's seller most recently sent (OFF-04 / DATA-01).
    val submittedListSnapshot: StateFlow<LedgerArchive?> = approvedBookKey
        .flatMapLatest { key ->
            if (key != null && key.second != key.first.workspace.adminUid) {
                repository.observeLatestSubmission(
                    ledgerOwnerUid = key.second,
                    workspaceAdminUid = key.first.workspace.adminUid
                ).catch { e ->
                    Log.w(TAG, "Error observing latest report", e)
                    emit(null)
                }
            } else {
                flowOf(null)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    // Every report sent from the selected seller book, newest first.
    val submissionHistory: StateFlow<List<LedgerArchive>> = approvedBookKey
        .flatMapLatest { key ->
            if (key != null && key.second != key.first.workspace.adminUid) {
                repository.observeSubmissionHistory(
                    ledgerOwnerUid = key.second,
                    workspaceAdminUid = key.first.workspace.adminUid
                ).catch { e ->
                    Log.w(TAG, "Error observing report history", e)
                    emit(emptyList())
                }
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // OFF-04: Honest delivery status (distinguishes local queued snapshot from confirmed server timestamp)
    val lastSentToAdminFormatted: StateFlow<String?> = submittedListSnapshot
        .map { snapshot ->
            if (snapshot == null) return@map null
            if (snapshot.hasPendingSync) {
                return@map "Queued to send (waiting for connection)"
            }
            val date = snapshot.createdAt?.toDate() ?: return@map null
            val settled = if (snapshot.isSettled) " • Settled" else ""
            "Uploaded ${SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(date)}$settled"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    // Active sale entries for the currently selected ledger book:
    // - For Sellers (or Admin's Private Book): their own working entries
    // - For Admin viewing a Seller's Book: ONLY the latest report that the Seller sent via "Send to Admin"
    private val bookEntries: StateFlow<BookEntries?> = approvedBookKey
        .flatMapLatest { key ->
            if (key == null) return@flatMapLatest flowOf(null)
            val (state, ownerUid) = key
            val isAdminInspectingSeller = state.profile.isAdmin && ownerUid != state.profile.userId
            val source: Flow<List<SaleEntry>> = if (isAdminInspectingSeller) {
                repository.observeLatestSubmission(
                    ledgerOwnerUid = ownerUid,
                    workspaceAdminUid = state.workspace.adminUid
                ).map { report ->
                    report?.parsedItems()?.mapIndexed { idx, item ->
                        item.toSaleEntry(ownerUid = ownerUid, workspaceAdminUid = state.workspace.adminUid, index = idx)
                    } ?: emptyList()
                }
            } else {
                repository.observeLedgerEntries(
                    ledgerOwnerUid = ownerUid,
                    workspaceAdminUid = state.workspace.adminUid
                )
            }
            source
                .onEach { _ledgerLoadError.value = null }
                .map<List<SaleEntry>, BookEntries?> { BookEntries(ownerUid, it) }
                .catch { e ->
                    Log.w(TAG, "Error observing entries", e)
                    _ledgerLoadError.value = describeError(e, "Could not load sales.")
                    emit(null)
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val rawEntries: StateFlow<List<SaleEntry>> = combine(bookEntries, currentTargetBook) { loaded, book ->
        loaded?.takeIf { it.ownerUid == book.ownerUid }?.entries ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    /** False while a book switch is still loading; dangerous actions are disabled meanwhile. */
    val isSelectedBookLoaded: StateFlow<Boolean> = combine(bookEntries, currentTargetBook) { loaded, book ->
        loaded?.ownerUid == book.ownerUid
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    // OFF-03: Indicates whether any active sale or sent snapshot is waiting in the local outbox
    val hasPendingSyncWrites: StateFlow<Boolean> = combine(
        rawEntries,
        submittedListSnapshot
    ) { entries, submitted ->
        entries.any { it.hasPendingSync } || (submitted?.hasPendingSync == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    // Filtered entries based on search query (Product Req: paid/unpaid filter UI removed)
    val filteredEntries: StateFlow<List<SaleEntry>> = combine(
        rawEntries,
        _searchQuery
    ) { entries, query ->
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.customerName.lowercase().contains(q) ||
                    entry.presetName.lowercase().contains(q) ||
                    entry.note.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Real-time financial & GB summary metrics for the selected book:
    // - Active working ledger uses current downloaded global settings (workspace.costPerGbToman / adminProfitPercent)
    // - Submitted seller snapshot viewed by Admin uses the snapshot's captured settings (MONEY-01 / DATA-01)
    val summaryMetrics: StateFlow<LedgerSummaryMetrics> = combine(
        rawEntries,
        _sessionState,
        currentTargetBook,
        submittedListSnapshot
    ) { entries, state, targetBook, submittedSnapshot ->
        val workspace = (state as? WorkspaceSessionState.Ready)?.workspace
        val profile = (state as? WorkspaceSessionState.Ready)?.profile
        val isAdminViewingSeller = profile?.isAdmin == true && targetBook.ownerUid != profile.userId

        if (isAdminViewingSeller && submittedSnapshot != null && submittedSnapshot.ledgerOwnerUid == targetBook.ownerUid) {
            submittedSnapshot.computeMetrics(
                fallbackCostPerGb = workspace?.costPerGbToman ?: 1_000L,
                fallbackAdminPercent = workspace?.adminProfitPercent ?: 50
            )
        } else {
            val costPerGb = workspace?.costPerGbToman ?: 1_000L
            val adminPct = workspace?.adminProfitPercent ?: 50
            FinancialCalculator.computeActiveMetrics(
                entries = if (isAdminViewingSeller) emptyList() else entries,
                costPerGbToman = costPerGb,
                adminProfitPercent = adminPct
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LedgerSummaryMetrics())

    // Archived cycles for the currently selected ledger book (voided ones last)
    val archives: StateFlow<List<LedgerArchive>> = approvedBookKey
        .flatMapLatest { key ->
            if (key != null) {
                repository.observeLedgerArchives(
                    ledgerOwnerUid = key.second,
                    workspaceAdminUid = key.first.workspace.adminUid
                )
                    .onEach { _archiveLoadError.value = null }
                    .catch { e ->
                        Log.w(TAG, "Error observing archives", e)
                        _archiveLoadError.value = describeError(e, "Could not load archives.")
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Admin-managed workspace GB & Toman presets (synced from workspaceAdminUid so Admin changes apply everywhere)
    val customPresets: StateFlow<List<DataPreset>> = combine(
        _sessionState,
        _refreshNonce
    ) { state, _ ->
        (state as? WorkspaceSessionState.Ready)?.takeIf { it.profile.isApproved }?.workspace?.adminUid
    }
        .distinctUntilChanged()
        .flatMapLatest { adminUid ->
            if (adminUid != null) {
                repository.observeCustomPresets(
                    ledgerOwnerUid = adminUid,
                    workspaceAdminUid = adminUid
                ).catch { e ->
                    Log.w(TAG, "Error observing presets", e)
                    emit(emptyList())
                }
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Merges default presets with Admin's saved preset overrides/additions by ID
    val allPresets: StateFlow<List<DataPreset>> = customPresets
        .map { savedPresets ->
            val savedById = savedPresets.associateBy { it.id }
            val mergedDefaults = TomanFormatter.defaultBuiltInPresets.map { defaultPreset ->
                savedById[defaultPreset.id] ?: defaultPreset
            }
            val additionalCustom = savedPresets.filter { !it.id.startsWith("builtin_") }
            (mergedDefaults + additionalCustom).sortedBy { it.dataGb }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            TomanFormatter.defaultBuiltInPresets
        )

    // --- UI Actions & Event Handlers ---

    fun selectLedgerBook(ownerUid: String) {
        val state = _sessionState.value
        if (state is WorkspaceSessionState.Ready && state.profile.isAdmin) {
            _selectedLedgerOwnerUid.value = ownerUid
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearBannerMessage() {
        _statusBannerMessage.value = null
    }

    fun clearReadyPdfExport() {
        _readyPdfExport.value = null
    }

    fun refreshListeners() {
        _refreshNonce.value += 1
    }

    fun updateWorkspaceFinancialRates(
        costPerGbToman: Long,
        adminProfitPercent: Int
    ) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) {
            _statusBannerMessage.value = "Only the Admin can change the cost per GB and the profit split."
            return
        }
        if (_isBusy.value) return
        val cleanCost = costPerGbToman.coerceIn(0L, 1_000_000_000L)
        val cleanAdminPct = adminProfitPercent.coerceIn(0, 100)
        val sellerPct = 100 - cleanAdminPct

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = repository.updateWorkspaceRates(
                    costPerGbToman = cleanCost,
                    adminProfitPercent = cleanAdminPct
                )
                result.fold(
                    onSuccess = {
                        _statusBannerMessage.value = "Saved: ${TomanFormatter.format(cleanCost)} per GB • Seller $sellerPct% / Admin $cleanAdminPct%. Sent reports and archives keep their own rates."
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not save the rates.")
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun addOrUpdateSale(
        editingEntryId: String?,
        customerName: String,
        dataGb: Double,
        priceToman: Long,
        originalPresetPriceToman: Long,
        presetName: String,
        isPaid: Boolean,
        isNewUser: Boolean,
        bonusGb: Double,
        note: String,
        deterministicEntryId: String? = null,
        onComplete: () -> Unit = {}
    ) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (_isBusy.value) return
        val targetBook = currentTargetBook.value
        val targetOwnerUid = targetBook.ownerUid
        if (state.profile.isAdmin && targetOwnerUid != state.profile.userId) {
            _statusBannerMessage.value = "A seller's sales can only be changed by that seller."
            return
        }

        val trimmedName = customerName.trim()
        if (trimmedName.isBlank()) {
            _statusBannerMessage.value = "Please enter the customer's name."
            return
        }
        if (trimmedName.length > 100) {
            _statusBannerMessage.value = "Customer name cannot exceed 100 characters."
            return
        }
        if (!dataGb.isFinite() || dataGb <= 0.0 || dataGb > SaleEntry.MAX_ALLOWED_GB) {
            _statusBannerMessage.value = "Please enter a valid GB data volume between 0.1 and 100,000 GB."
            return
        }
        if (priceToman < 0L || priceToman > SaleEntry.MAX_ALLOWED_TOMAN) {
            _statusBannerMessage.value = "Price in Toman must be between 0 and 10,000,000,000."
            return
        }

        val effectiveBonusGb = if (isNewUser && bonusGb.isFinite()) bonusGb.coerceIn(0.0, dataGb) else 0.0

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = if (editingEntryId.isNullOrBlank()) {
                    repository.addSaleEntry(
                        ledgerOwnerUid = targetOwnerUid,
                        workspaceAdminUid = state.workspace.adminUid,
                        customerName = trimmedName,
                        dataGb = dataGb,
                        priceToman = priceToman,
                        originalPresetPriceToman = if (originalPresetPriceToman > 0L) originalPresetPriceToman else priceToman,
                        presetName = presetName.ifBlank { "${TomanFormatter.formatGb(dataGb)} Plan" },
                        isPaid = isPaid,
                        note = note,
                        isNewUser = isNewUser,
                        bonusGb = effectiveBonusGb,
                        deterministicEntryId = deterministicEntryId
                    ).map { }
                } else {
                    repository.updateSaleEntry(
                        ledgerOwnerUid = targetOwnerUid,
                        entryId = editingEntryId,
                        customerName = trimmedName,
                        dataGb = dataGb,
                        priceToman = priceToman,
                        originalPresetPriceToman = if (originalPresetPriceToman > 0L) originalPresetPriceToman else priceToman,
                        presetName = presetName.ifBlank { "${TomanFormatter.formatGb(dataGb)} Plan" },
                        isPaid = isPaid,
                        note = note,
                        isNewUser = isNewUser,
                        bonusGb = effectiveBonusGb
                    )
                }
                result.fold(
                    onSuccess = {
                        val bonusSuffix = if (isNewUser && effectiveBonusGb > 0.0) {
                            " (includes ${TomanFormatter.formatGb(effectiveBonusGb)} bonus)"
                        } else ""
                        _statusBannerMessage.value = if (editingEntryId.isNullOrBlank()) {
                            "Saved ${TomanFormatter.formatGb(dataGb)}$bonusSuffix for $trimmedName (${TomanFormatter.format(priceToman)})."
                        } else {
                            "Updated the sale for $trimmedName."
                        }
                        onComplete()
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not save the sale.")
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun togglePaidStatus(entry: SaleEntry) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        val targetOwnerUid = entry.ledgerOwnerUid.ifBlank { currentTargetBook.value.ownerUid }
        val isAdminViewingSeller = state.profile.isAdmin && targetOwnerUid != state.profile.userId
        if (isAdminViewingSeller) {
            val submitted = submittedListSnapshot.value ?: return
            if (submitted.ledgerOwnerUid != targetOwnerUid) return
            toggleArchiveItemPaidStatus(submitted, entry.id, !entry.isPaid)
            return
        }
        val nextPaid = !entry.isPaid
        viewModelScope.launch {
            val result = repository.toggleSalePaidStatus(
                ledgerOwnerUid = targetOwnerUid,
                entryId = entry.id,
                isPaid = nextPaid
            )
            if (result.isFailure) {
                _statusBannerMessage.value = describeError(result.exceptionOrNull(), "Could not update the payment checkbox.")
            }
        }
    }

    /** DATA-03: One item's checkbox, by stable item id; the snapshot's money fields never change. */
    fun toggleArchiveItemPaidStatus(
        archive: LedgerArchive,
        itemId: String,
        newPaid: Boolean
    ) {
        if (archive.isLegacyReport) {
            _statusBannerMessage.value = "This older report is read-only. Payment checkboxes work on reports sent with this version."
            return
        }
        if (archive.isVoided) {
            _statusBannerMessage.value = "This archive was voided and is read-only."
            return
        }
        val targetOwnerUid = archive.ledgerOwnerUid.ifBlank { currentTargetBook.value.ownerUid }
        viewModelScope.launch {
            val result = repository.updateSnapshotItemPaidStatus(
                ledgerOwnerUid = targetOwnerUid,
                collection = archive.sourceCollection,
                snapshotId = archive.id,
                itemId = itemId,
                isPaid = newPaid
            )
            if (result.isFailure) {
                _statusBannerMessage.value = describeError(result.exceptionOrNull(), "Could not update the payment checkbox.")
            }
        }
    }

    fun deleteSale(entry: SaleEntry) {
        val targetOwnerUid = entry.ledgerOwnerUid.ifBlank { currentTargetBook.value.ownerUid }
        viewModelScope.launch {
            val result = repository.deleteSaleEntry(
                ledgerOwnerUid = targetOwnerUid,
                entryId = entry.id
            )
            result.fold(
                onSuccess = {
                    _statusBannerMessage.value = "Deleted the sale for ${entry.customerName}."
                },
                onFailure = { e ->
                    _statusBannerMessage.value = describeError(e, "Could not delete the sale.")
                }
            )
        }
    }

    fun sendCurrentListToAdmin() {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (_isBusy.value) return
        if (state.profile.isAdmin || currentTargetBook.value.ownerUid != state.profile.userId) return
        if (!isSelectedBookLoaded.value) {
            _statusBannerMessage.value = "Your sales are still loading. Try again in a moment."
            return
        }
        val currentEntries = rawEntries.value
        if (currentEntries.isEmpty()) {
            _statusBannerMessage.value = "There are no sales to send."
            return
        }
        val metrics = summaryMetrics.value
        val ownerName = state.profile.displayName
            .takeIf { it.isNotBlank() && !it.contains("@") }
            ?: state.profile.email.substringBefore("@").ifBlank { "Seller" }
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = repository.submitCurrentListToAdmin(
                    ledgerOwnerUid = state.profile.userId,
                    workspaceAdminUid = state.workspace.adminUid,
                    ownerName = ownerName,
                    activeEntries = currentEntries,
                    costPerGbToman = state.workspace.costPerGbToman,
                    adminProfitPercent = state.workspace.adminProfitPercent,
                    settingsRevision = state.workspace.settingsRevision
                )
                result.fold(
                    onSuccess = { ackStatus ->
                        _statusBannerMessage.value = if (ackStatus == WriteAckStatus.SAVED_OFFLINE_PENDING_SYNC) {
                            "Report with ${currentEntries.size} sales is queued and will upload when you're online."
                        } else {
                            "Report uploaded: ${currentEntries.size} sales • amount due to Admin ${TomanFormatter.format(metrics.sellerPayableToAdminToman)}."
                        }
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not send the report.")
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun resetCurrentLedger(
        cycleTitle: String,
        keepArchive: Boolean,
        onResetSuccess: () -> Unit = {}
    ) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (_isBusy.value) return
        // REL-02: Bind reset strictly to the currently displayed book's ownerUid and snapshot
        val targetBook = currentTargetBook.value
        val targetOwnerUid = targetBook.ownerUid
        if (state.profile.isAdmin && targetOwnerUid != state.profile.userId) {
            _statusBannerMessage.value = "Only the seller can start a new cycle in their own book."
            return
        }
        if (!isSelectedBookLoaded.value) {
            _statusBannerMessage.value = "This book is still loading. Try again in a moment."
            return
        }
        val currentEntries = rawEntries.value
        if (currentEntries.isEmpty()) {
            _statusBannerMessage.value = "There are no active sales in this cycle."
            return
        }

        val bookOwnerName = targetBook.cleanOwnerName.ifBlank { targetBook.title }
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = repository.resetAndArchiveLedger(
                    ledgerOwnerUid = targetOwnerUid,
                    workspaceAdminUid = state.workspace.adminUid,
                    ownerName = bookOwnerName,
                    cycleTitle = cycleTitle,
                    activeEntries = currentEntries,
                    keepArchive = keepArchive,
                    costPerGbToman = state.workspace.costPerGbToman,
                    adminProfitPercent = state.workspace.adminProfitPercent,
                    settingsRevision = state.workspace.settingsRevision
                )
                result.fold(
                    onSuccess = { archiveId ->
                        _statusBannerMessage.value = if (archiveId != null) {
                            "New cycle started. ${currentEntries.size} sales were saved to Archives."
                        } else {
                            "New cycle started. Active sales were cleared without an archive."
                        }
                        onResetSuccess()
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = if (e is ConcurrentLedgerEditException) {
                            e.message
                        } else {
                            describeError(e, "Could not start a new cycle.")
                        }
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** DATA-03: Archives are voided with a reason (kept, marked and read-only), never deleted. */
    fun voidArchiveSnapshot(archive: LedgerArchive, reason: String) {
        if (archive.isSubmission || archive.isLegacyReport) {
            _statusBannerMessage.value = "Sent reports are kept as a permanent record and cannot be voided."
            return
        }
        // REL-02: Always use the archive's own ledgerOwnerUid, not a separately changing selection flow
        val targetOwnerUid = archive.ledgerOwnerUid.ifBlank { currentTargetBook.value.ownerUid }
        viewModelScope.launch {
            val result = repository.voidArchive(targetOwnerUid, archive.id, reason)
            result.fold(
                onSuccess = { _statusBannerMessage.value = "Voided '${archive.cycleTitle}'. It stays in Archives, marked as voided." },
                onFailure = { e -> _statusBannerMessage.value = describeError(e, "Could not void the archive.") }
            )
        }
    }

    fun setReportSettled(report: LedgerArchive, settled: Boolean) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin || !report.isSubmission) return
        viewModelScope.launch {
            val result = repository.setSubmissionSettled(report.ledgerOwnerUid, report.id, settled)
            result.fold(
                onSuccess = {
                    _statusBannerMessage.value = if (settled) "Marked the report as settled." else "Marked the report as not settled."
                },
                onFailure = { e -> _statusBannerMessage.value = describeError(e, "Could not update the report.") }
            )
        }
    }

    fun saveOrUpdatePresetPackage(
        existingPresetId: String?,
        label: String,
        dataGb: Double,
        defaultPriceToman: Long,
        newUserBonusGb: Double = 0.0
    ) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) {
            _statusBannerMessage.value = "Only the Admin can change plans."
            return
        }
        if (label.isBlank() || !dataGb.isFinite() || dataGb <= 0.0 || defaultPriceToman < 0L) {
            _statusBannerMessage.value = "Provide a valid plan name, GB amount and Toman price."
            return
        }
        viewModelScope.launch {
            val result = repository.saveOrUpdatePreset(
                ledgerOwnerUid = state.workspace.adminUid,
                workspaceAdminUid = state.workspace.adminUid,
                existingPresetId = existingPresetId,
                label = label,
                dataGb = dataGb,
                defaultPriceToman = defaultPriceToman,
                newUserBonusGb = newUserBonusGb.coerceAtLeast(0.0)
            )
            result.fold(
                onSuccess = {
                    val action = if (existingPresetId.isNullOrBlank()) "Added" else "Updated"
                    val bonusLabel = if (newUserBonusGb > 0.0) " (+${TomanFormatter.formatGb(newUserBonusGb)} new-user bonus)" else ""
                    _statusBannerMessage.value = "$action plan '$label' (${TomanFormatter.format(defaultPriceToman)}$bonusLabel)."
                },
                onFailure = { e ->
                    _statusBannerMessage.value = describeError(e, "Could not save the plan.")
                }
            )
        }
    }

    // REL-03: Check returned Result before announcing success
    fun removeCustomPresetPackage(preset: DataPreset) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) return
        viewModelScope.launch {
            val result = repository.deleteCustomPreset(state.workspace.adminUid, preset.id)
            result.fold(
                onSuccess = {
                    _statusBannerMessage.value = if (preset.id.startsWith("builtin_")) {
                        "Restored '${preset.label}' to its default price and bonus. The built-in plan is still available."
                    } else {
                        "Removed the plan '${preset.label}'."
                    }
                },
                onFailure = { e ->
                    _statusBannerMessage.value = describeError(e, "Could not remove the plan.")
                }
            )
        }
    }

    // --- Admin Seller Roster Actions ---

    fun addOrApproveSellerByEmail(email: String, sellerName: String) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) return
        if (_isBusy.value) return
        val cleanEmail = TomanFormatter.canonicalizeEmail(email)
        if (TomanFormatter.invitationKeyFor(cleanEmail) == null || !cleanEmail.contains("@") || cleanEmail.length < 5) {
            _statusBannerMessage.value = "Please enter a valid Google email address."
            return
        }

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = repository.addOrApproveSellerEmail(
                    workspaceAdminUid = state.workspace.adminUid,
                    email = cleanEmail,
                    sellerName = sellerName
                )
                result.fold(
                    onSuccess = {
                        _statusBannerMessage.value = "Approved $cleanEmail. They get access when they sign in with that Google account."
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not approve that email.")
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setSellerApproval(sellerProfile: UserProfile, approve: Boolean) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) return
        if (_isBusy.value) return

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = repository.updateSellerStatus(
                    sellerUid = sellerProfile.userId,
                    emailKey = sellerProfile.emailKey,
                    email = sellerProfile.email,
                    sellerName = sellerProfile.displayName,
                    workspaceAdminUid = state.workspace.adminUid,
                    approve = approve
                )
                result.fold(
                    onSuccess = {
                        _statusBannerMessage.value = if (approve) {
                            "Approved ${sellerProfile.displayName}."
                        } else {
                            "Revoked ${sellerProfile.displayName}. The server blocks their book as soon as their phone reconnects."
                        }
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not update the seller.")
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    // REL-03: Check returned Result on invitation removal
    fun removePreApprovedEmail(item: ApprovedEmail) {
        val state = _sessionState.value as? WorkspaceSessionState.Ready ?: return
        if (!state.profile.isAdmin) return
        viewModelScope.launch {
            val result = repository.removeApprovedEmail(item.emailKey)
            result.fold(
                onSuccess = {
                    _statusBannerMessage.value = "Cancelled the invitation for ${item.email}. A seller who already signed in keeps access until you revoke them."
                },
                onFailure = { e ->
                    _statusBannerMessage.value = describeError(e, "Could not cancel the invitation.")
                }
            )
        }
    }

    // --- Graphical PDF Export & Storage Access Framework Save (PDF-01, PDF-02, REL-04) ---

    fun exportActiveLedgerToPdf(context: Context) {
        if (_isBusy.value) return
        if (!isSelectedBookLoaded.value) {
            _statusBannerMessage.value = "This book is still loading. Try again in a moment."
            return
        }
        val entries = rawEntries.value
        val book = currentTargetBook.value
        val metrics = summaryMetrics.value
        val cleanOwner = book.cleanOwnerName.ifBlank { TomanFormatter.sanitizeOwnerDisplayName(book.title) }
        val appContext = context.applicationContext

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val payload: PdfReportPayload = GraphicalPdfExporter.buildPayloadFromActiveLedger(
                    bookTitle = book.title,
                    ownerName = cleanOwner,
                    entries = entries,
                    costPerGbToman = metrics.costPerGbToman,
                    adminProfitPercent = metrics.adminProfitPercent,
                    isPrivateAdminBook = book.isPrivateAdminBook
                )
                val result = GraphicalPdfExporter.generatePdfFile(appContext, payload)
                result.fold(
                    onSuccess = { pdfFile ->
                        _readyPdfExport.value = ActivePdfExportReady(
                            file = pdfFile,
                            title = book.title,
                            payload = payload
                        )
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not create the PDF.")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBannerMessage.value = describeError(e, "Could not create the PDF.")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exportArchiveToPdf(context: Context, archive: LedgerArchive) {
        if (_isBusy.value) return
        val state = _sessionState.value as? WorkspaceSessionState.Ready
        val fallbackCostPerGb = state?.workspace?.costPerGbToman ?: 1_000L
        val fallbackAdminPct = state?.workspace?.adminProfitPercent ?: 50
        val isPrivateBook = state?.profile?.isAdmin == true && archive.ledgerOwnerUid == state.profile.userId
        val appContext = context.applicationContext

        _isBusy.value = true
        viewModelScope.launch {
            try {
                val payload: PdfReportPayload = GraphicalPdfExporter.buildPayloadFromArchive(
                    archive = archive,
                    fallbackCostPerGbToman = fallbackCostPerGb,
                    fallbackAdminProfitPercent = fallbackAdminPct,
                    isPrivateAdminBook = isPrivateBook
                )
                val result = GraphicalPdfExporter.generatePdfFile(appContext, payload)
                result.fold(
                    onSuccess = { pdfFile ->
                        _readyPdfExport.value = ActivePdfExportReady(
                            file = pdfFile,
                            title = archive.cycleTitle.ifBlank { "Archived Ledger" },
                            payload = payload
                        )
                    },
                    onFailure = { e ->
                        _statusBannerMessage.value = describeError(e, "Could not create the PDF.")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBannerMessage.value = describeError(e, "Could not create the PDF.")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveExportedPdfToUri(context: Context, targetUri: Uri) {
        val exportReady = _readyPdfExport.value ?: return
        saveExportedPdfToUri(context, exportReady.file, targetUri)
    }

    fun saveExportedPdfToUri(context: Context, sourceFile: File, targetUri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val result = GraphicalPdfExporter.copyFileToUri(appContext, sourceFile, targetUri)
            result.fold(
                onSuccess = {
                    _statusBannerMessage.value = "PDF saved."
                },
                onFailure = { e ->
                    _statusBannerMessage.value = describeError(e, "Could not save the PDF to that location.")
                }
            )
        }
    }

    fun reportPdfViewerMissing() {
        _statusBannerMessage.value = "No PDF viewer app found on this device. Use 'Save PDF' or 'Share PDF' instead."
    }

    fun reportActionError(message: String) {
        _statusBannerMessage.value = message
    }

    private fun describeError(error: Throwable?, fallback: String): String {
        val firestoreCode = (error as? FirebaseFirestoreException)?.code
        return when (firestoreCode) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "The server refused this action. Your access may have changed; contact the Admin if it continues."
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "You appear to be offline. Connect to the internet and try again."
            FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                "The server is not ready for this request yet. Try again shortly."
            else -> error?.localizedMessage?.takeIf { it.isNotBlank() && firestoreCode == null } ?: fallback
        }
    }

    private companion object {
        const val TAG = "LedgerViewModel"
    }
}
