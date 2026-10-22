package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ApprovedEmail
import com.example.data.model.DataPreset
import com.example.data.model.InputValidator
import com.example.data.model.LedgerArchive
import com.example.data.model.LedgerSummaryMetrics
import com.example.data.model.LedgerTargetBook
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import com.example.data.model.UserProfile
import com.example.data.model.WorkspaceConfig
import com.example.ui.components.ArchiveDetailBottomSheet
import com.example.ui.components.FinancialBreakdownSection
import com.example.ui.components.GrossSalesHeader
import com.example.ui.components.InlineWarning
import com.example.ui.components.MetadataLine
import com.example.ui.components.MetricBlock
import com.example.ui.components.PdfExportReadyDialog
import com.example.ui.components.ResetLedgerDialog
import com.example.ui.components.SaleEntrySheet
import com.example.ui.components.SectionHeading
import com.example.ui.components.ratesLabel
import com.example.ui.components.salesCountLabel
import com.example.ui.viewmodel.LedgerViewModel
import com.example.util.GraphicalPdfExporter
import java.text.SimpleDateFormat
import java.util.Locale

private enum class NodeSplitTab(val label: String) {
    LEDGER("Sales"),
    ARCHIVES("Archives"),
    ADMIN_SETTINGS("Settings")
}

private fun formatGbPlainText(gb: Double): String {
    return if (gb % 1.0 == 0.0) gb.toLong().toString() else gb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLedgerScreen(
    viewModel: LedgerViewModel,
    workspace: WorkspaceConfig,
    profile: UserProfile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val availableBooks by viewModel.availableBooks.collectAsStateWithLifecycle()
    val currentBook by viewModel.currentTargetBook.collectAsStateWithLifecycle()
    val filteredEntries by viewModel.filteredEntries.collectAsStateWithLifecycle()
    val rawEntries by viewModel.rawEntries.collectAsStateWithLifecycle()
    val metrics by viewModel.summaryMetrics.collectAsStateWithLifecycle()
    val archives by viewModel.archives.collectAsStateWithLifecycle()
    val submissionHistory by viewModel.submissionHistory.collectAsStateWithLifecycle()
    val latestReport by viewModel.submittedListSnapshot.collectAsStateWithLifecycle()
    val presets by viewModel.allPresets.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val readyPdfExport by viewModel.readyPdfExport.collectAsStateWithLifecycle()
    val lastSentToAdminFormatted by viewModel.lastSentToAdminFormatted.collectAsStateWithLifecycle()
    val hasPendingSyncWrites by viewModel.hasPendingSyncWrites.collectAsStateWithLifecycle()
    val workspaceUsers by viewModel.workspaceUsers.collectAsStateWithLifecycle()
    val approvedEmails by viewModel.approvedEmails.collectAsStateWithLifecycle()
    val ledgerLoadError by viewModel.ledgerLoadError.collectAsStateWithLifecycle()
    val archiveLoadError by viewModel.archiveLoadError.collectAsStateWithLifecycle()
    val isBookLoaded by viewModel.isSelectedBookLoaded.collectAsStateWithLifecycle()

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        // A null uri means the user cancelled the picker: no error message.
        if (uri != null) {
            viewModel.saveExportedPdfToUri(context, uri)
        }
    }

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember(profile.isAdmin) {
        if (profile.isAdmin) {
            listOf(NodeSplitTab.LEDGER, NodeSplitTab.ARCHIVES, NodeSplitTab.ADMIN_SETTINGS)
        } else {
            listOf(NodeSplitTab.LEDGER, NodeSplitTab.ARCHIVES)
        }
    }
    val currentTab = tabs.getOrElse(selectedTabIndex) { NodeSplitTab.LEDGER }

    BackHandler(enabled = currentTab != NodeSplitTab.LEDGER) {
        selectedTabIndex = 0
    }

    var showSaleSheet by rememberSaveable { mutableStateOf(false) }
    var editingSaleEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingSaleEntry = remember(rawEntries, editingSaleEntryId) {
        rawEntries.firstOrNull { it.id == editingSaleEntryId }
    }

    var pendingDeleteSale by remember { mutableStateOf<SaleEntry?>(null) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutWarning by rememberSaveable { mutableStateOf(false) }
    var inspectedArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookMenuExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    val inspectedArchive = remember(archives, submissionHistory, latestReport, inspectedArchiveId) {
        archives.firstOrNull { it.id == inspectedArchiveId }
            ?: submissionHistory.firstOrNull { it.id == inspectedArchiveId }
            ?: latestReport?.takeIf { it.id == inspectedArchiveId }
    }

    val isAdminInspectingSeller = profile.isAdmin && currentBook.ownerUid != profile.userId
    val canStartNewCycle = !isAdminInspectingSeller && rawEntries.isNotEmpty() && isBookLoaded && !isBusy

    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    pendingDeleteSale?.let { entryToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSale = null },
            title = { Text("Delete this sale?") },
            text = {
                Text(
                    "\"${entryToDelete.customerName}\" — ${TomanFormatter.formatGb(entryToDelete.dataGb)}, ${TomanFormatter.format(entryToDelete.priceToman)}. Reports you already sent to the Admin keep their copy."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSale(entryToDelete)
                        pendingDeleteSale = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_sale_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSale = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // OFF-06: Unsynced changes stay on this phone for this account; say so before signing out.
    if (showSignOutWarning) {
        AlertDialog(
            onDismissRequest = { showSignOutWarning = false },
            title = { Text("Some changes haven't uploaded yet") },
            text = {
                Text("They stay on this phone and upload the next time you sign in here with this account and are online. If you sign out and uninstall or clear the app's data, they are lost.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutWarning = false
                        onSignOut()
                    }
                ) {
                    Text("Sign out anyway")
                }
            },
            dismissButton = {
                Button(onClick = { showSignOutWarning = false }) {
                    Text("Stay signed in")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = if (profile.isAdmin) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (profile.isAdmin) "Admin" else "Seller",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (profile.isAdmin) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = if (hasPendingSyncWrites) Icons.Default.CloudQueue else Icons.Default.CloudDone,
                                contentDescription = if (hasPendingSyncWrites) "Waiting to sync" else "Synced",
                                tint = if (hasPendingSyncWrites) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { overflowMenuExpanded = true },
                                modifier = Modifier.testTag("top_bar_overflow_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Export PDF") },
                                    leadingIcon = {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                                    },
                                    enabled = isBookLoaded && !isBusy,
                                    onClick = {
                                        overflowMenuExpanded = false
                                        viewModel.exportActiveLedgerToPdf(context)
                                    }
                                )
                                if (canStartNewCycle) {
                                    DropdownMenuItem(
                                        text = { Text("Start new cycle") },
                                        leadingIcon = {
                                            Icon(Icons.Default.RestartAlt, contentDescription = null)
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            showResetDialog = true
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        if (hasPendingSyncWrites) showSignOutWarning = true else onSignOut()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                // UI-02: The selected book gets its own full-width line instead of a truncated subtitle.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (currentBook.isPrivateAdminBook || !profile.isAdmin) {
                            currentBook.title
                        } else {
                            "Seller: ${currentBook.title}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                    )
                }
                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == idx,
                        onClick = { selectedTabIndex = idx },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    NodeSplitTab.LEDGER -> Icons.AutoMirrored.Filled.ReceiptLong
                                    NodeSplitTab.ARCHIVES -> Icons.Default.Archive
                                    NodeSplitTab.ADMIN_SETTINGS -> Icons.Default.AdminPanelSettings
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == NodeSplitTab.LEDGER && !isAdminInspectingSeller) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingSaleEntryId = null
                        showSaleSheet = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add sale") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_sale_fab")
                )
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            NodeSplitTab.LEDGER -> {
                ActiveLedgerTabContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    profile = profile,
                    availableBooks = availableBooks,
                    currentBook = currentBook,
                    bookMenuExpanded = bookMenuExpanded,
                    onBookMenuExpandChange = { bookMenuExpanded = it },
                    onSelectBook = { uid ->
                        bookMenuExpanded = false
                        viewModel.selectLedgerBook(uid)
                    },
                    isAdminInspectingSeller = isAdminInspectingSeller,
                    isBookLoaded = isBookLoaded,
                    isBusy = isBusy,
                    ledgerLoadError = ledgerLoadError,
                    onRetryLoad = viewModel::refreshListeners,
                    hasPendingSyncWrites = hasPendingSyncWrites,
                    metrics = metrics,
                    rawEntriesCount = rawEntries.size,
                    filteredEntries = filteredEntries,
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    lastSentToAdminFormatted = lastSentToAdminFormatted,
                    isLegacyReport = latestReport?.isLegacyReport == true,
                    onSendListToAdmin = viewModel::sendCurrentListToAdmin,
                    onExportPdf = { viewModel.exportActiveLedgerToPdf(context) },
                    onOpenResetDialog = { showResetDialog = true },
                    canStartNewCycle = canStartNewCycle,
                    onTogglePaid = viewModel::togglePaidStatus,
                    onEditEntry = { entry ->
                        if (!isAdminInspectingSeller) {
                            editingSaleEntryId = entry.id
                            showSaleSheet = true
                        }
                    },
                    onDeleteEntry = { entry ->
                        if (!isAdminInspectingSeller) {
                            pendingDeleteSale = entry
                        }
                    }
                )
            }

            NodeSplitTab.ARCHIVES -> {
                ArchivesTabContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    archives = archives,
                    reports = submissionHistory,
                    archiveLoadError = archiveLoadError,
                    workspace = workspace,
                    currentBook = currentBook,
                    isAdmin = profile.isAdmin,
                    isBusy = isBusy,
                    onInspectArchive = { archive -> inspectedArchiveId = archive.id },
                    onExportArchivePdf = { archive -> viewModel.exportArchiveToPdf(context, archive) }
                )
            }

            NodeSplitTab.ADMIN_SETTINGS -> {
                AdminSettingsAndSplitTabContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    workspace = workspace,
                    presets = presets,
                    workspaceUsers = workspaceUsers,
                    approvedEmails = approvedEmails,
                    onUpdateRates = viewModel::updateWorkspaceFinancialRates,
                    onSaveOrUpdatePreset = viewModel::saveOrUpdatePresetPackage,
                    onDeleteCustomPreset = viewModel::removeCustomPresetPackage,
                    onAddSellerEmail = viewModel::addOrApproveSellerByEmail,
                    onToggleSellerApproval = viewModel::setSellerApproval,
                    onRemovePreApprovedEmail = viewModel::removePreApprovedEmail
                )
            }
        }
    }

    if (showSaleSheet) {
        SaleEntrySheet(
            editingEntry = editingSaleEntry,
            presets = presets,
            isAdmin = profile.isAdmin,
            costPerGbToman = metrics.costPerGbToman,
            adminProfitPercent = metrics.adminProfitPercent,
            isPrivateAdminBook = currentBook.isPrivateAdminBook,
            isSaving = isBusy,
            onDismiss = {
                showSaleSheet = false
                editingSaleEntryId = null
            },
            onSaveSale = { editingId, customerName, dataGb, priceToman, originalPresetPriceToman, presetName, isPaid, isNewUser, bonusGb, note ->
                viewModel.addOrUpdateSale(
                    editingEntryId = editingId,
                    customerName = customerName,
                    dataGb = dataGb,
                    priceToman = priceToman,
                    originalPresetPriceToman = originalPresetPriceToman,
                    presetName = presetName,
                    isPaid = isPaid,
                    isNewUser = isNewUser,
                    bonusGb = bonusGb,
                    note = note,
                    onComplete = {
                        showSaleSheet = false
                        editingSaleEntryId = null
                    }
                )
            },
            onSaveOrUpdatePreset = viewModel::saveOrUpdatePresetPackage,
            onDeleteCustomPreset = viewModel::removeCustomPresetPackage
        )
    }

    if (showResetDialog) {
        ResetLedgerDialog(
            bookTitle = currentBook.title,
            metrics = metrics,
            isSellerBook = !currentBook.isPrivateAdminBook,
            onDismiss = { showResetDialog = false },
            onConfirmReset = { cycleTitle, keepArchive ->
                viewModel.resetCurrentLedger(
                    cycleTitle = cycleTitle,
                    keepArchive = keepArchive,
                    onResetSuccess = { showResetDialog = false }
                )
            }
        )
    }

    inspectedArchive?.let { archive ->
        ArchiveDetailBottomSheet(
            archive = archive,
            fallbackCostPerGbToman = workspace.costPerGbToman,
            fallbackAdminProfitPercent = workspace.adminProfitPercent,
            isPrivateAdminBook = archive.ledgerOwnerUid == workspace.adminUid,
            isAdmin = profile.isAdmin,
            onDismiss = { inspectedArchiveId = null },
            onToggleItemPaid = { itemId, newPaid ->
                viewModel.toggleArchiveItemPaidStatus(archive, itemId, newPaid)
            },
            onExportPdf = { viewModel.exportArchiveToPdf(context, it) },
            onVoidArchive = { target, reason -> viewModel.voidArchiveSnapshot(target, reason) },
            onSetSettled = { target, settled -> viewModel.setReportSettled(target, settled) }
        )
    }

    readyPdfExport?.let { exportReady ->
        PdfExportReadyDialog(
            exportReady = exportReady,
            onDismiss = viewModel::clearReadyPdfExport,
            onSaveToDevice = {
                try {
                    savePdfLauncher.launch(exportReady.file.name)
                } catch (_: android.content.ActivityNotFoundException) {
                    viewModel.reportActionError("No app on this device can choose a save location. Use Share PDF instead.")
                }
            },
            onOpenPdf = {
                val res = GraphicalPdfExporter.openPdfFile(context, exportReady.file)
                if (res.isFailure) {
                    viewModel.reportPdfViewerMissing()
                }
            },
            onSharePdf = {
                val res = GraphicalPdfExporter.sharePdfFile(context, exportReady.file, exportReady.title)
                if (res.isFailure) {
                    viewModel.reportActionError("Could not share the PDF. Create it again and retry.")
                }
            }
        )
    }
}

@Composable
private fun ActiveLedgerTabContent(
    modifier: Modifier,
    profile: UserProfile,
    availableBooks: List<LedgerTargetBook>,
    currentBook: LedgerTargetBook,
    bookMenuExpanded: Boolean,
    onBookMenuExpandChange: (Boolean) -> Unit,
    onSelectBook: (String) -> Unit,
    isAdminInspectingSeller: Boolean,
    isBookLoaded: Boolean,
    isBusy: Boolean,
    ledgerLoadError: String?,
    onRetryLoad: () -> Unit,
    hasPendingSyncWrites: Boolean,
    metrics: LedgerSummaryMetrics,
    rawEntriesCount: Int,
    filteredEntries: List<SaleEntry>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    lastSentToAdminFormatted: String?,
    isLegacyReport: Boolean,
    onSendListToAdmin: () -> Unit,
    onExportPdf: () -> Unit,
    onOpenResetDialog: () -> Unit,
    canStartNewCycle: Boolean,
    onTogglePaid: (SaleEntry) -> Unit,
    onEditEntry: (SaleEntry) -> Unit,
    onDeleteEntry: (SaleEntry) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // REL-03: A failed load is an error, not an empty ledger.
        if (ledgerLoadError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Couldn't load these sales",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "$ledgerLoadError The totals below are not reliable until this loads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        OutlinedButton(onClick = onRetryLoad) { Text("Try again") }
                    }
                }
            }
        }

        // 0. Pending Offline Sync Banner (OFF-03)
        if (hasPendingSyncWrites) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Saved on this phone. Waiting to sync when you're online.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 1. Admin Book Switcher OR Seller "Send to Admin" card
        item {
            if (profile.isAdmin && availableBooks.size > 1) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Viewing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentBook.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isAdminInspectingSeller) {
                            Text(
                                text = when {
                                    lastSentToAdminFormatted == null -> "No current report. The seller hasn't sent one since their last new cycle."
                                    isLegacyReport -> "Older report (sent with a previous app version): $lastSentToAdminFormatted"
                                    else -> "Latest report: $lastSentToAdminFormatted"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            OutlinedButton(
                                onClick = { onBookMenuExpandChange(true) },
                                modifier = Modifier.testTag("switch_book_button")
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Switch book")
                            }
                            DropdownMenu(
                                expanded = bookMenuExpanded,
                                onDismissRequest = { onBookMenuExpandChange(false) }
                            ) {
                                availableBooks.forEach { book ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(book.title, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = book.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = { onSelectBook(book.ownerUid) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (!profile.isAdmin) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Report to Admin",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = lastSentToAdminFormatted?.let { "Last report: $it. Changes you make now are not included until you send again." }
                                ?: "The Admin only sees your sales when you send a report.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Button(
                            onClick = onSendListToAdmin,
                            enabled = rawEntriesCount > 0 && isBookLoaded && !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_list_to_admin_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send report")
                        }
                    }
                }
            }
        }

        // 2. Financial summary (UI-01)
        item {
            LedgerSummaryCard(
                metrics = metrics,
                isPrivateAdminBook = currentBook.isPrivateAdminBook,
                isSellerReport = isAdminInspectingSeller,
                canExport = isBookLoaded && !isBusy,
                onExportPdf = onExportPdf,
                onOpenResetDialog = onOpenResetDialog,
                canReset = canStartNewCycle
            )
        }

        // 3. Search (UI-04: no paid/unpaid filter chips or aggregate paid/unpaid counts)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search name, plan or note") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ledger_search_input"),
                    shape = RoundedCornerShape(14.dp)
                )
                if (searchQuery.isNotBlank()) {
                    Text(
                        text = "Showing ${filteredEntries.size} of $rawEntriesCount. Totals and PDF export include all $rawEntriesCount sales.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 4. Sales list
        if (filteredEntries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when {
                                !isBookLoaded && ledgerLoadError == null -> "Loading…"
                                isAdminInspectingSeller -> "No current report from this seller"
                                rawEntriesCount == 0 -> "No sales in this cycle yet"
                                else -> "No matching sales"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isAdminInspectingSeller) {
                                "When this seller sends a report, its sales and amounts appear here. Earlier reports are in Archives."
                            } else {
                                "Tap Add sale to record one."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredEntries, key = { it.id }) { entry ->
                SaleEntryRowCard(
                    entry = entry,
                    readOnly = isAdminInspectingSeller,
                    canTogglePaid = !isAdminInspectingSeller || !isLegacyReport,
                    onTogglePaid = { onTogglePaid(entry) },
                    onEdit = { onEditEntry(entry) },
                    onDelete = { onDeleteEntry(entry) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerSummaryCard(
    metrics: LedgerSummaryMetrics,
    isPrivateAdminBook: Boolean,
    isSellerReport: Boolean,
    canExport: Boolean,
    onExportPdf: () -> Unit,
    onOpenResetDialog: () -> Unit,
    canReset: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("financial_summary_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GrossSalesHeader(metrics)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FinancialBreakdownSection(
                metrics = metrics,
                isPrivateAdminBook = isPrivateAdminBook,
                ratesNote = if (isSellerReport) {
                    "Rates saved with this report: ${ratesLabel(metrics)}"
                } else {
                    "Current rates: ${ratesLabel(metrics)}. Change them in Settings."
                }
            )

            // Buttons wrap onto a second line when the width or font scale needs it.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportPdf,
                    enabled = canExport,
                    modifier = Modifier.testTag("summary_export_pdf_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export PDF")
                }
                if (canReset) {
                    OutlinedButton(
                        onClick = onOpenResetDialog,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("summary_reset_ledger_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start new cycle")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaleEntryRowCard(
    entry: SaleEntry,
    readOnly: Boolean,
    canTogglePaid: Boolean,
    onTogglePaid: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (entry.isPaid) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
    }
    val borderColor = if (entry.isPaid) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !readOnly) { onEdit() }
            .testTag("sale_entry_card_${entry.id}"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier.minimumInteractiveComponentSize(),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = entry.isPaid,
                    onCheckedChange = { if (canTogglePaid) onTogglePaid() },
                    enabled = canTogglePaid,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.tertiary
                    ),
                    modifier = Modifier
                        .semantics {
                            contentDescription = "Customer paid: ${entry.customerName}"
                            stateDescription = if (entry.isPaid) "Paid" else "Not paid yet"
                        }
                        .testTag("sale_paid_checkbox_${entry.id}")
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = entry.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (entry.isNewUser) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "New user",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (entry.hasPendingSync) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = "Waiting to sync",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = TomanFormatter.formatAllocationSummary(entry.safeDataGb, entry.safeBonusGb, entry.isNewUser),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.presetName.ifBlank { "Custom plan" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = TomanFormatter.format(entry.priceToman),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (entry.isPaid) "Paid" else "Not paid yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (entry.isPaid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            }

            if (!readOnly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_sale_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit sale for ${entry.customerName}",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_sale_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete sale for ${entry.customerName}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivesTabContent(
    modifier: Modifier,
    archives: List<LedgerArchive>,
    reports: List<LedgerArchive>,
    archiveLoadError: String?,
    workspace: WorkspaceConfig,
    currentBook: LedgerTargetBook,
    isAdmin: Boolean,
    isBusy: Boolean,
    onInspectArchive: (LedgerArchive) -> Unit,
    onExportArchivePdf: (LedgerArchive) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // UI-02: Short title, owner as a subtitle, one-line helper.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Archives",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentBook.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Open an archive to view sales or export a PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (archiveLoadError != null) {
            item { InlineWarning("Couldn't load archives. $archiveLoadError") }
        }

        if (reports.isNotEmpty()) {
            item {
                SectionHeading(if (isAdmin) "Reports sent by this seller" else "Reports you sent")
            }
            items(reports, key = { "report_${it.id}" }) { report ->
                SnapshotSummaryCard(
                    snapshot = report,
                    workspace = workspace,
                    isPrivateAdminBook = false,
                    isBusy = isBusy,
                    onOpen = { onInspectArchive(report) },
                    onExportPdf = { onExportArchivePdf(report) }
                )
            }
            item { SectionHeading("Closed cycles") }
        }

        if (archives.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No closed cycles yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "When you start a new cycle and keep an archive, the closed cycle appears here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(archives, key = { it.id }) { archive ->
                SnapshotSummaryCard(
                    snapshot = archive,
                    workspace = workspace,
                    isPrivateAdminBook = currentBook.isPrivateAdminBook,
                    isBusy = isBusy,
                    onOpen = { onInspectArchive(archive) },
                    onExportPdf = { onExportArchivePdf(archive) }
                )
            }
        }
    }
}

/** UI-02: One archive or sent report. Title, owner and date each get their own line; actions sit below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotSummaryCard(
    snapshot: LedgerArchive,
    workspace: WorkspaceConfig,
    isPrivateAdminBook: Boolean,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onExportPdf: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.US) }
    val metrics = remember(snapshot, workspace.costPerGbToman, workspace.adminProfitPercent) {
        snapshot.computeMetrics(
            fallbackCostPerGb = workspace.costPerGbToman,
            fallbackAdminPercent = workspace.adminProfitPercent
        )
    }
    val dateText = snapshot.createdAt?.toDate()?.let { dateFormat.format(it) }
    val title = when {
        snapshot.isLegacyReport -> "Older report"
        snapshot.isSubmission -> "Report"
        else -> snapshot.cycleTitle
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("archive_card_${snapshot.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.isVoided) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = TomanFormatter.sanitizeOwnerDisplayName(snapshot.ownerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        snapshot.hasPendingSync -> "Saved on this phone, waiting to upload"
                        dateText == null -> "Date not available"
                        snapshot.isSubmission || snapshot.isLegacyReport -> "Uploaded $dateText"
                        else -> "Closed $dateText"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusBadges(snapshot)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Gross sales",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = TomanFormatter.format(metrics.totalSalesToman),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                MetadataLine(listOf(salesCountLabel(metrics.salesCount), TomanFormatter.formatGb(metrics.totalGb)))
            }

            if (isPrivateAdminBook) {
                MetricBlock(
                    label = "Business profit",
                    amount = TomanFormatter.format(metrics.netProfitToman),
                    amountColor = MaterialTheme.colorScheme.primary
                )
            } else {
                MetricBlock(
                    label = "Amount due to Admin",
                    amount = TomanFormatter.format(metrics.sellerPayableToAdminToman),
                    amountColor = MaterialTheme.colorScheme.primary
                )
                MetricBlock(
                    label = "Seller profit (${metrics.sellerProfitPercent}%)",
                    amount = TomanFormatter.format(metrics.sellerProfitToman),
                    amountColor = MaterialTheme.colorScheme.secondary
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onExportPdf, enabled = !isBusy) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export PDF")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBadges(snapshot: LedgerArchive) {
    val badges = buildList {
        if (snapshot.isVoided) add("Voided")
        if (snapshot.isSettled) add("Settled")
        if (snapshot.isLegacyReport) add("Read-only")
    }
    if (badges.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        badges.forEach { label ->
            Surface(
                color = if (label == "Voided") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (label == "Voided") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminSettingsAndSplitTabContent(
    modifier: Modifier,
    workspace: WorkspaceConfig,
    presets: List<DataPreset>,
    workspaceUsers: List<UserProfile>,
    approvedEmails: List<ApprovedEmail>,
    onUpdateRates: (costPerGbToman: Long, adminProfitPercent: Int) -> Unit,
    onSaveOrUpdatePreset: (
        existingPresetId: String?,
        label: String,
        dataGb: Double,
        defaultPriceToman: Long,
        newUserBonusGb: Double
    ) -> Unit,
    onDeleteCustomPreset: (DataPreset) -> Unit,
    onAddSellerEmail: (email: String, sellerName: String) -> Unit,
    onToggleSellerApproval: (UserProfile, Boolean) -> Unit,
    onRemovePreApprovedEmail: (ApprovedEmail) -> Unit
) {
    var costPerGbInput by rememberSaveable(workspace.costPerGbToman) {
        mutableStateOf(workspace.costPerGbToman.toString())
    }
    var adminPercentInput by rememberSaveable(workspace.adminProfitPercent) {
        mutableStateOf(workspace.adminProfitPercent.toString())
    }
    var rateValidationError by rememberSaveable { mutableStateOf<String?>(null) }

    var editingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetLabelInput by rememberSaveable { mutableStateOf("") }
    var presetGbInput by rememberSaveable { mutableStateOf("") }
    var presetBonusGbInput by rememberSaveable { mutableStateOf("5") }
    var presetPriceInput by rememberSaveable { mutableStateOf("") }
    var presetValidationError by rememberSaveable { mutableStateOf<String?>(null) }

    var newSellerEmail by rememberSaveable { mutableStateOf("") }
    var newSellerName by rememberSaveable { mutableStateOf("") }

    val parsedAdminPct = InputValidator.validateAdminSharePercent(adminPercentInput).getOrDefault(workspace.adminProfitPercent)
    val sellerPct = 100 - parsedAdminPct

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Global Infrastructure Cost per GB & Profit Split Override Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Cost per GB and profit split",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Applies to active sales right away. Sent reports and archives keep the rates they were saved with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = costPerGbInput,
                        onValueChange = {
                            costPerGbInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() }
                            rateValidationError = null
                        },
                        label = { Text("Infrastructure cost per GB") },
                        suffix = { Text("Toman") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_cost_per_gb_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = adminPercentInput,
                            onValueChange = {
                                adminPercentInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() }.take(3)
                                rateValidationError = null
                            },
                            label = { Text("Admin share %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_profit_percent_input")
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Seller share",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "$sellerPct%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (rateValidationError != null) {
                        Text(
                            text = rateValidationError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {
                            val costCheck = InputValidator.validatePriceToman(costPerGbInput, "Cost per 1 GB")
                            val pctCheck = InputValidator.validateAdminSharePercent(adminPercentInput)
                            when {
                                costCheck.isFailure -> rateValidationError = costCheck.exceptionOrNull()?.message
                                pctCheck.isFailure -> rateValidationError = pctCheck.exceptionOrNull()?.message
                                else -> {
                                    rateValidationError = null
                                    onUpdateRates(costCheck.getOrThrow(), pctCheck.getOrThrow())
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_workspace_rates_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save rates")
                    }
                }
            }
        }

        // 2. Package Presets & New User Bonus GB Management Card (UX-01)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Plans",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (editingPresetId != null) {
                            TextButton(
                                onClick = {
                                    editingPresetId = null
                                    presetLabelInput = ""
                                    presetGbInput = ""
                                    presetBonusGbInput = "5"
                                    presetPriceInput = ""
                                    presetValidationError = null
                                }
                            ) {
                                Text("New plan")
                            }
                        }
                    }
                    Text(
                        text = "Tap a plan to edit its GB, price, or the bonus GB a new user gets. Removing a change to a built-in plan restores its default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = editingPresetId == preset.id,
                                onClick = {
                                    editingPresetId = preset.id
                                    presetLabelInput = preset.label
                                    presetGbInput = formatGbPlainText(preset.dataGb)
                                    presetBonusGbInput = formatGbPlainText(preset.newUserBonusGb)
                                    presetPriceInput = preset.defaultPriceToman.toString()
                                    presetValidationError = null
                                },
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(preset.label, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "${TomanFormatter.formatGb(preset.dataGb)} (+${TomanFormatter.formatGb(preset.newUserBonusGb)} new) • ${TomanFormatter.format(preset.defaultPriceToman)}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                },
                                trailingIcon = if (!preset.id.startsWith("builtin_")) {
                                    {
                                        IconButton(
                                            onClick = { onDeleteCustomPreset(preset) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = if (preset.id.startsWith("builtin_")) "Restore default for ${preset.label}" else "Remove plan ${preset.label}",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = presetLabelInput,
                        onValueChange = {
                            presetLabelInput = it
                            presetValidationError = null
                        },
                        label = { Text("Plan name (e.g., 20 GB Standard)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_preset_label_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = presetGbInput,
                            onValueChange = {
                                presetGbInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() || ch == '.' }
                                presetValidationError = null
                            },
                            label = { Text("Plan GB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_preset_gb_input")
                        )
                        OutlinedTextField(
                            value = presetBonusGbInput,
                            onValueChange = {
                                presetBonusGbInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() || ch == '.' }
                                presetValidationError = null
                            },
                            label = { Text("New-user bonus GB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_preset_bonus_gb_input")
                        )
                    }

                    OutlinedTextField(
                        value = presetPriceInput,
                        onValueChange = {
                            presetPriceInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() }
                            presetValidationError = null
                        },
                        label = { Text("Plan price") },
                        suffix = { Text("Toman") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_preset_price_input")
                    )

                    if (presetValidationError != null) {
                        Text(
                            text = presetValidationError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {
                            val gbRes = InputValidator.validateDataGb(presetGbInput, "Plan GB")
                            val bonusRes = InputValidator.validateBonusGb(presetBonusGbInput)
                            val priceRes = InputValidator.validatePriceToman(presetPriceInput, "Plan price")
                            when {
                                presetLabelInput.isBlank() -> presetValidationError = "Please enter a plan name."
                                gbRes.isFailure -> presetValidationError = gbRes.exceptionOrNull()?.message
                                bonusRes.isFailure -> presetValidationError = bonusRes.exceptionOrNull()?.message
                                priceRes.isFailure -> presetValidationError = priceRes.exceptionOrNull()?.message
                                else -> {
                                    onSaveOrUpdatePreset(
                                        editingPresetId,
                                        presetLabelInput.trim(),
                                        gbRes.getOrThrow(),
                                        priceRes.getOrThrow(),
                                        bonusRes.getOrThrow()
                                    )
                                    editingPresetId = null
                                    presetLabelInput = ""
                                    presetGbInput = ""
                                    presetBonusGbInput = "5"
                                    presetPriceInput = ""
                                    presetValidationError = null
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_save_preset_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (editingPresetId != null) "Update plan" else "Add plan")
                    }
                }
            }
        }

        // 3. Authorize New Seller Email Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Approve a seller",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = newSellerName,
                        onValueChange = { newSellerName = it },
                        label = { Text("Seller name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSellerEmail,
                        onValueChange = { newSellerEmail = it },
                        label = { Text("Seller's Google email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onAddSellerEmail(newSellerEmail, newSellerName)
                            newSellerEmail = ""
                            newSellerName = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Approve email")
                    }
                }
            }
        }

        // 4. Registered Workspace Sellers
        if (workspaceUsers.isNotEmpty()) {
            item {
                Text(
                    text = "Accounts (${workspaceUsers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(workspaceUsers, key = { it.userId }) { userItem ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(userItem.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = when {
                                    userItem.role == "ADMIN" -> "Admin"
                                    userItem.status == "APPROVED" -> "Seller • approved"
                                    userItem.status == "REVOKED" -> "Seller • access revoked"
                                    else -> "Seller • waiting for approval"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (userItem.role != "ADMIN") {
                            val isApproved = userItem.status == "APPROVED"
                            OutlinedButton(
                                onClick = { onToggleSellerApproval(userItem, !isApproved) }
                            ) {
                                Text(if (isApproved) "Revoke" else "Approve")
                            }
                        }
                    }
                }
            }
        }

        // 5. Pre-Approved Seller Emails
        if (approvedEmails.isNotEmpty()) {
            item {
                Text(
                    text = "Invitations (${approvedEmails.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(approvedEmails, key = { it.emailKey }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.sellerName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(item.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.status == "REVOKED") {
                                Text("Revoked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { onRemovePreApprovedEmail(item) }) {
                            Text(
                                text = "Cancel invitation",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
