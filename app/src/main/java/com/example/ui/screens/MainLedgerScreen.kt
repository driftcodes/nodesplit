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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.components.PdfExportReadyDialog
import com.example.ui.components.ResetLedgerDialog
import com.example.ui.components.SaleEntrySheet
import com.example.ui.viewmodel.LedgerViewModel
import com.example.util.GraphicalPdfExporter
import java.text.SimpleDateFormat
import java.util.Locale

private enum class NodeSplitTab(val label: String) {
    LEDGER("Active Ledger"),
    ARCHIVES("Archives"),
    ADMIN_SETTINGS("Admin & Split")
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
    val presets by viewModel.allPresets.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val readyPdfExport by viewModel.readyPdfExport.collectAsStateWithLifecycle()
    val lastSentToAdminFormatted by viewModel.lastSentToAdminFormatted.collectAsStateWithLifecycle()
    val hasPendingSyncWrites by viewModel.hasPendingSyncWrites.collectAsStateWithLifecycle()
    val workspaceUsers by viewModel.workspaceUsers.collectAsStateWithLifecycle()
    val approvedEmails by viewModel.approvedEmails.collectAsStateWithLifecycle()

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
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
    var pendingDeleteArchive by remember { mutableStateOf<LedgerArchive?>(null) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var inspectedArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookMenuExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    val inspectedArchive = remember(archives, inspectedArchiveId) {
        archives.firstOrNull { it.id == inspectedArchiveId }
    }

    val isAdminInspectingSeller = profile.isAdmin && currentBook.ownerUid != profile.userId

    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    pendingDeleteSale?.let { entryToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSale = null },
            title = { Text("Delete Sale Record?") },
            text = {
                Text(
                    "Are you sure you want to delete the sale for \"${entryToDelete.customerName}\" (${TomanFormatter.formatGb(entryToDelete.dataGb)} • ${TomanFormatter.format(entryToDelete.priceToman)})?"
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
                    Text("Delete Sale")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSale = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingDeleteArchive?.let { archiveToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteArchive = null },
            title = { Text("Delete Archived Snapshot?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete \"${archiveToDelete.cycleTitle}\" (${TomanFormatter.format(archiveToDelete.totalSalesToman)})?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteArchiveSnapshot(archiveToDelete)
                        if (inspectedArchiveId == archiveToDelete.id) {
                            inspectedArchiveId = null
                        }
                        pendingDeleteArchive = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_archive_button")
                ) {
                    Text("Delete Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteArchive = null }) {
                    Text("Cancel")
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
                        Column {
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
                                        text = if (profile.isAdmin) "ADMIN" else "SELLER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (profile.isAdmin) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = currentBook.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = if (hasPendingSyncWrites) Icons.Default.CloudQueue else Icons.Default.CloudDone,
                                    contentDescription = if (hasPendingSyncWrites) "Pending local changes" else "Synced",
                                    tint = if (hasPendingSyncWrites) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.exportActiveLedgerToPdf(context) },
                            modifier = Modifier.testTag("top_bar_export_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Export Graphical PDF"
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { overflowMenuExpanded = true },
                                modifier = Modifier.testTag("top_bar_overflow_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options"
                                )
                            }
                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Export Graphical PDF") },
                                    leadingIcon = {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        viewModel.exportActiveLedgerToPdf(context)
                                    }
                                )
                                if (!isAdminInspectingSeller && rawEntries.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Reset & Archive Ledger") },
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
                                    text = { Text("Sign Out") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onSignOut()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
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
                                contentDescription = tab.label
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
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add VPN Sale") },
                    text = { Text("Add VPN Sale") },
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
                    hasPendingSyncWrites = hasPendingSyncWrites,
                    metrics = metrics,
                    rawEntriesCount = rawEntries.size,
                    filteredEntries = filteredEntries,
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    lastSentToAdminFormatted = lastSentToAdminFormatted,
                    onSendListToAdmin = viewModel::sendCurrentListToAdmin,
                    onExportPdf = { viewModel.exportActiveLedgerToPdf(context) },
                    onOpenResetDialog = { showResetDialog = true },
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
                    workspace = workspace,
                    currentBook = currentBook,
                    onInspectArchive = { archive -> inspectedArchiveId = archive.id },
                    onExportArchivePdf = { archive -> viewModel.exportArchiveToPdf(context, archive) },
                    onDeleteArchive = { archive -> pendingDeleteArchive = archive }
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

    if (inspectedArchive != null) {
        ArchiveDetailBottomSheet(
            archive = inspectedArchive,
            fallbackCostPerGbToman = workspace.costPerGbToman,
            fallbackAdminProfitPercent = workspace.adminProfitPercent,
            isPrivateAdminBook = currentBook.isPrivateAdminBook,
            onDismiss = { inspectedArchiveId = null },
            onToggleItemPaid = { itemIndex, newPaid ->
                viewModel.toggleArchiveItemPaidStatus(inspectedArchive, itemIndex, newPaid)
            },
            onExportPdf = { archive ->
                viewModel.exportArchiveToPdf(context, archive)
            },
            onDeleteArchive = { archive ->
                viewModel.deleteArchiveSnapshot(archive)
                inspectedArchiveId = null
            }
        )
    }

    readyPdfExport?.let { exportReady ->
        PdfExportReadyDialog(
            exportReady = exportReady,
            onDismiss = viewModel::clearReadyPdfExport,
            onSaveToDevice = {
                savePdfLauncher.launch(exportReady.file.name)
            },
            onOpenPdf = {
                val res = GraphicalPdfExporter.openPdfFile(context, exportReady.file)
                if (res.isFailure) {
                    viewModel.reportPdfViewerMissing()
                }
            },
            onSharePdf = {
                GraphicalPdfExporter.sharePdfFile(context, exportReady.file, exportReady.title)
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
    hasPendingSyncWrites: Boolean,
    metrics: LedgerSummaryMetrics,
    rawEntriesCount: Int,
    filteredEntries: List<SaleEntry>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    lastSentToAdminFormatted: String?,
    onSendListToAdmin: () -> Unit,
    onExportPdf: () -> Unit,
    onOpenResetDialog: () -> Unit,
    onTogglePaid: (SaleEntry) -> Unit,
    onEditEntry: (SaleEntry) -> Unit,
    onDeleteEntry: (SaleEntry) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Pending Offline Sync Banner (OFF-06)
        if (hasPendingSyncWrites) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
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
                            contentDescription = "Pending cloud sync",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Saved locally — waiting to sync with cloud when online",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 1. Admin Book Switcher OR Seller "Send Current List to Admin" Action Bar
        item {
            if (profile.isAdmin && availableBooks.size > 1) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VIEWING LEDGER BOOK",
                                style = MaterialTheme.typography.labelSmall,
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
                                    text = lastSentToAdminFormatted?.let { "Last sent by Seller: $it" }
                                        ?: "Waiting for Seller to press 'Send List'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Box {
                            OutlinedButton(
                                onClick = { onBookMenuExpandChange(true) },
                                modifier = Modifier.testTag("switch_book_button")
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Switch Book")
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
                                                    text = if (book.isPrivateAdminBook) "Admin Private Book" else "Seller Sent Snapshot",
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
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Send Current List to Admin",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = lastSentToAdminFormatted?.let { "Last sent to Admin: $it" }
                                    ?: "Your active list is only sent to Admin when you press Send Current List",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onSendListToAdmin,
                            enabled = rawEntriesCount > 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_list_to_admin_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send to Admin",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Current List to Admin")
                        }
                    }
                }
            }
        }

        // 2. Vertical Stacked 3-Part Financial Split & Settlement Card (UI-01)
        item {
            ThreePartFinancialSummaryCard(
                metrics = metrics,
                isPrivateAdminBook = currentBook.isPrivateAdminBook,
                onExportPdf = onExportPdf,
                onOpenResetDialog = onOpenResetDialog,
                canReset = !isAdminInspectingSeller && rawEntriesCount > 0
            )
        }

        // 3. Search Bar (UI-04: No paid/unpaid filter chips or aggregate paid/unpaid counts)
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search customer name, package, or note...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
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
        }

        // 4. Customer Sales List
        if (filteredEntries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                            text = if (isAdminInspectingSeller) {
                                "No List Sent by Seller Yet"
                            } else if (rawEntriesCount == 0) {
                                "No Active VPN Sales Recorded"
                            } else {
                                "No Matching Sales Found"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isAdminInspectingSeller) {
                                "When this seller presses 'Send Current List to Admin', their sales and settlement breakdown will appear here."
                            } else {
                                "Tap 'Add VPN Sale' below to select a GB preset, toggle New User bonus GB, and record a sale."
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

@Composable
private fun ThreePartFinancialSummaryCard(
    metrics: LedgerSummaryMetrics,
    isPrivateAdminBook: Boolean,
    onExportPdf: () -> Unit,
    onOpenResetDialog: () -> Unit,
    canReset: Boolean
) {
    val settlementTitle = if (isPrivateAdminBook) {
        "ADMIN INFRA + SHARE (PRIVATE BOOK)"
    } else {
        "AMOUNT SELLER PAYS ADMIN"
    }

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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Gross Sales (plus sales count)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "GROSS SALES (${metrics.totalCustomers} SALES)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = TomanFormatter.format(metrics.totalSalesToman),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // 2. Total Allocated Data
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "TOTAL ALLOCATED DATA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = TomanFormatter.formatGb(metrics.totalGb),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    val bonusLabel = if (metrics.totalBonusGb > 0.0) {
                        "Includes +${TomanFormatter.formatGb(metrics.totalBonusGb)} New User bonus data"
                    } else {
                        "Total customer data allocated in this cycle"
                    }
                    Text(
                        text = bonusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            // 3. Rate & Split Rule (Read-only display here; edited in Admin Settings only per UI-04)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "RATE & PROFIT SPLIT RULE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Cost: ${TomanFormatter.format(metrics.costPerGbToman)}/GB  •  Split: Seller ${metrics.sellerProfitPercent}% / Admin ${metrics.adminProfitPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // 4. 1. Infrastructure Cost
            StackedSummaryMetricCard(
                title = "1. Infrastructure Cost",
                amount = TomanFormatter.format(metrics.infraCostToman),
                explanation = "${TomanFormatter.formatGb(metrics.totalGb)} × ${TomanFormatter.format(metrics.costPerGbToman)} per GB",
                amountColor = MaterialTheme.colorScheme.onSurface
            )

            // 5. Net Profit
            StackedSummaryMetricCard(
                title = "Net Profit (Remaining After Infra Cost)",
                amount = TomanFormatter.format(metrics.netProfitToman),
                explanation = "Gross Sales (${TomanFormatter.format(metrics.totalSalesToman)}) minus Infrastructure Cost (${TomanFormatter.format(metrics.infraCostToman)})",
                amountColor = MaterialTheme.colorScheme.onSurface
            )

            // 6. 2. Seller Profit (50%)
            StackedSummaryMetricCard(
                title = "2. Seller Profit (${metrics.sellerProfitPercent}%)",
                amount = TomanFormatter.format(metrics.sellerProfitToman),
                explanation = if (isPrivateAdminBook) "Your Seller Share of Net Profit" else "Seller Net Profit Share",
                amountColor = MaterialTheme.colorScheme.secondary
            )

            // 7. 3. Admin Profit (50%)
            StackedSummaryMetricCard(
                title = "3. Admin Profit (${metrics.adminProfitPercent}%)",
                amount = TomanFormatter.format(metrics.adminProfitToman),
                explanation = "Admin Net Profit Share",
                amountColor = MaterialTheme.colorScheme.primary
            )

            // 8. Prominent Settlement Banner: Amount Seller Pays Admin (Admin Profit + Infra Cost)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("seller_payable_to_admin_banner")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = settlementTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = TomanFormatter.format(metrics.sellerPayableToAdminToman),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Admin Profit (${TomanFormatter.format(metrics.adminProfitToman)}) + Infrastructure Cost (${TomanFormatter.format(metrics.infraCostToman)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                    )
                }
            }

            // Action Buttons: Export PDF & Reset Ledger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onExportPdf,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_export_pdf_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export PDF Report")
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
                        Text("Reset")
                    }
                }
            }
        }
    }
}

@Composable
private fun StackedSummaryMetricCard(
    title: String,
    amount: String,
    explanation: String,
    amountColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.titleMedium,
                color = amountColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaleEntryRowCard(
    entry: SaleEntry,
    readOnly: Boolean,
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
                    onCheckedChange = { if (!readOnly) onTogglePaid() },
                    enabled = !readOnly,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.tertiary
                    ),
                    modifier = Modifier.testTag("sale_paid_checkbox_${entry.id}")
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
                            val bonusText = if (entry.bonusGb > 0.0) {
                                "NEW USER (+${TomanFormatter.formatGb(entry.bonusGb)})"
                            } else {
                                "NEW USER"
                            }
                            Text(
                                text = bonusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (entry.hasPendingSync) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = "Pending sync",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                val dataDetails = if (entry.isNewUser && entry.bonusGb > 0.0) {
                    "${TomanFormatter.formatGbNumberOnly(entry.dataGb)} GB total (${TomanFormatter.formatGbNumberOnly(entry.baseDataGb)} + ${TomanFormatter.formatGbNumberOnly(entry.bonusGb)} bonus)"
                } else {
                    TomanFormatter.formatGb(entry.dataGb)
                }

                Text(
                    text = "$dataDetails • ${entry.presetName.ifBlank { "Custom Plan" }}",
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
                    color = if (entry.isPaid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            if (!readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("edit_sale_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Sale",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_sale_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Sale",
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
    workspace: WorkspaceConfig,
    currentBook: LedgerTargetBook,
    onInspectArchive: (LedgerArchive) -> Unit,
    onExportArchivePdf: (LedgerArchive) -> Unit,
    onDeleteArchive: (LedgerArchive) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US) }
    val settlementLabel = if (currentBook.isPrivateAdminBook) {
        "Admin Infra + Share"
    } else {
        "Amount Seller Pays Admin"
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // UI-02: Clean Header + Owner Subtitle
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Archives",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Book: ${currentBook.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Tap any archive to inspect sales, toggle Paid/Unpaid checkboxes, or export a PDF report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (archives.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                            text = "No Archived Cycles Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "When you reset the active ledger and choose 'Save snapshot to Archives', past cycles are stored here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(archives, key = { it.id }) { archive ->
                val archiveMetrics = archive.computeMetrics(
                    fallbackCostPerGb = workspace.costPerGbToman,
                    fallbackAdminPercent = workspace.adminProfitPercent
                )
                val dateLabel = archive.createdAt?.toDate()?.let { dateFormat.format(it) } ?: "Archived"
                val cleanOwner = TomanFormatter.sanitizeOwnerDisplayName(archive.ownerName)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInspectArchive(archive) }
                        .testTag("archive_card_${archive.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = archive.cycleTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$cleanOwner • $dateLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = { onExportArchivePdf(archive) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "Export Archive PDF",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteArchive(archive) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Archive",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        // UI-02: Vertical stacked metric summary inside each Archive Card (no side-by-side Toman squeezing)
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Gross Sales: ${TomanFormatter.format(archive.totalSalesToman)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${archiveMetrics.totalCustomers} sales • ${TomanFormatter.formatGb(archive.totalGb)} allocated",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Seller Profit (${archiveMetrics.sellerProfitPercent}%): ${TomanFormatter.format(archiveMetrics.sellerProfitToman)}  •  Admin Profit (${archiveMetrics.adminProfitPercent}%): ${TomanFormatter.format(archiveMetrics.adminProfitToman)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "$settlementLabel: ${TomanFormatter.format(archiveMetrics.sellerPayableToAdminToman)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Infra Cost: ${TomanFormatter.format(archiveMetrics.infraCostToman)} (${TomanFormatter.format(archiveMetrics.costPerGbToman)}/GB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
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
                        text = "Global GB Cost & Profit Split",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure the VPS/infrastructure cost per GB and the default 50/50 profit split between Admin and Sellers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = costPerGbInput,
                        onValueChange = {
                            costPerGbInput = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() }
                            rateValidationError = null
                        },
                        label = { Text("Infrastructure Cost per 1 GB (Toman)") },
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
                            label = { Text("Admin Profit %") },
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
                                    text = "Seller Profit Share",
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
                        Text("Save Global Cost & Split")
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
                            text = "Package Presets & New User Bonus GB",
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
                                Text("+ New Preset")
                            }
                        }
                    }
                    Text(
                        text = "Tap any preset below to edit its standard GB, Toman price, or the extra bonus GB given to a New User on their first purchase.",
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
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Delete preset",
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
                        label = { Text("Preset Label (e.g., 20 GB Standard)") },
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
                            label = { Text("Standard GB") },
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
                            label = { Text("New User +GB") },
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
                        label = { Text("Preset Price (Toman)") },
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
                            val gbRes = InputValidator.validateDataGb(presetGbInput, "Standard GB")
                            val bonusRes = InputValidator.validateBonusGb(presetBonusGbInput)
                            val priceRes = InputValidator.validatePriceToman(presetPriceInput, "Preset Price")
                            when {
                                presetLabelInput.isBlank() -> presetValidationError = "Please enter a preset label."
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
                        Text(if (editingPresetId != null) "Update Selected Preset" else "Save New Preset")
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
                        text = "Approve Seller Google Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = newSellerName,
                        onValueChange = { newSellerName = it },
                        label = { Text("Seller Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSellerEmail,
                        onValueChange = { newSellerEmail = it },
                        label = { Text("Seller Google Email Address") },
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
                        Text("Approve Seller Email")
                    }
                }
            }
        }

        // 4. Registered Workspace Sellers
        if (workspaceUsers.isNotEmpty()) {
            item {
                Text(
                    text = "Registered Workspace Accounts (${workspaceUsers.size})",
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
                                text = "${userItem.role} • ${userItem.status}",
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
                    text = "Pre-Approved Seller Emails (${approvedEmails.size})",
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
                        }
                        IconButton(onClick = { onRemovePreApprovedEmail(item) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Remove Approved Email",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
