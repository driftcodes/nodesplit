package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Discount
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.ArchivedSaleItem
import com.example.data.model.DataPreset
import com.example.data.model.FinancialCalculator
import com.example.data.model.InputValidator
import com.example.data.model.LedgerArchive
import com.example.data.model.LedgerSummaryMetrics
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import com.example.data.repository.LedgerRepository
import com.example.ui.viewmodel.ActivePdfExportReady
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatGbPlain(gb: Double): String {
    return if (gb % 1.0 == 0.0) gb.toLong().toString() else gb.toString()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SaleEntrySheet(
    editingEntry: SaleEntry?,
    presets: List<DataPreset>,
    isAdmin: Boolean = false,
    costPerGbToman: Long = 1_000L,
    adminProfitPercent: Int = 50,
    isPrivateAdminBook: Boolean = false,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSaveSale: (
        editingId: String?,
        customerName: String,
        dataGb: Double,
        priceToman: Long,
        originalPresetPriceToman: Long,
        presetName: String,
        isPaid: Boolean,
        isNewUser: Boolean,
        bonusGb: Double,
        note: String
    ) -> Unit,
    onSaveOrUpdatePreset: (
        existingPresetId: String?,
        label: String,
        dataGb: Double,
        defaultPriceToman: Long,
        newUserBonusGb: Double
    ) -> Unit,
    onDeleteCustomPreset: (DataPreset) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultPreset = remember(presets) {
        presets.firstOrNull { it.id == "builtin_50" }
            ?: presets.firstOrNull()
            ?: DataPreset(
                id = "builtin_50",
                label = "50 GB Pro",
                dataGb = 50.0,
                defaultPriceToman = 240_000L,
                newUserBonusGb = 10.0
            )
    }

    val draftKey = editingEntry?.id ?: "new_sale_draft"

    var customerName by rememberSaveable(draftKey) {
        mutableStateOf(editingEntry?.customerName ?: "")
    }
    var baseDataGbText by rememberSaveable(draftKey) {
        mutableStateOf(
            if (editingEntry != null) {
                formatGbPlain(editingEntry.baseDataGb)
            } else {
                formatGbPlain(defaultPreset.dataGb)
            }
        )
    }
    var isNewUser by rememberSaveable(draftKey) {
        mutableStateOf(editingEntry?.isNewUser ?: false)
    }
    var bonusGbText by rememberSaveable(draftKey) {
        mutableStateOf(
            if (editingEntry != null) {
                val existingBonus = if (editingEntry.bonusGb > 0.0) {
                    editingEntry.bonusGb
                } else {
                    presets.firstOrNull { it.label == editingEntry.presetName }?.newUserBonusGb
                        ?: defaultPreset.newUserBonusGb
                }
                formatGbPlain(existingBonus)
            } else {
                formatGbPlain(defaultPreset.newUserBonusGb)
            }
        )
    }
    var priceTomanText by rememberSaveable(draftKey) {
        mutableStateOf((editingEntry?.priceToman ?: defaultPreset.defaultPriceToman).toString())
    }
    var originalPresetPrice by rememberSaveable(draftKey) {
        mutableLongStateOf(editingEntry?.originalPresetPriceToman ?: defaultPreset.defaultPriceToman)
    }
    var selectedPresetName by rememberSaveable(draftKey) {
        mutableStateOf(editingEntry?.presetName ?: defaultPreset.label)
    }
    var isPaid by rememberSaveable(draftKey) {
        mutableStateOf(editingEntry?.isPaid ?: false)
    }
    var note by rememberSaveable(draftKey) {
        mutableStateOf(editingEntry?.note ?: "")
    }
    var validationError by rememberSaveable(draftKey) {
        mutableStateOf<String?>(null)
    }

    var showPresetEditor by rememberSaveable(draftKey) { mutableStateOf(false) }
    var editingPresetId by rememberSaveable(draftKey) { mutableStateOf<String?>(null) }
    var newPresetLabel by rememberSaveable(draftKey) { mutableStateOf("") }
    var newPresetGb by rememberSaveable(draftKey) { mutableStateOf("") }
    var newPresetBonusGb by rememberSaveable(draftKey) { mutableStateOf("") }
    var newPresetPrice by rememberSaveable(draftKey) { mutableStateOf("") }
    var presetEditorError by rememberSaveable(draftKey) { mutableStateOf<String?>(null) }

    val baseGbValidation = InputValidator.validateDataGb(baseDataGbText, "Data GB")
    val bonusGbValidation = InputValidator.validateBonusGb(bonusGbText)
    val priceValidation = InputValidator.validatePriceToman(priceTomanText, "Final Price")

    val parsedBaseGb = baseGbValidation.getOrDefault(0.0)
    val parsedBonusGb = bonusGbValidation.getOrDefault(0.0)
    val effectiveBonusGb = if (isNewUser) parsedBonusGb else 0.0
    val totalAllocatedGb = parsedBaseGb + effectiveBonusGb

    val parsedPrice = priceValidation.getOrDefault(0L)
    val activeDiscount = if (originalPresetPrice > parsedPrice && parsedPrice > 0L) {
        originalPresetPrice - parsedPrice
    } else {
        0L
    }

    val liveSaleBreakdown = remember(parsedPrice, totalAllocatedGb, costPerGbToman, adminProfitPercent) {
        FinancialCalculator.calculate(
            grossSalesToman = parsedPrice,
            totalAllocatedGb = totalAllocatedGb,
            costPerGbToman = costPerGbToman,
            adminSharePercent = adminProfitPercent
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editingEntry == null) "New sale" else "Edit sale",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Customer Name Input
            OutlinedTextField(
                value = customerName,
                onValueChange = {
                    customerName = it
                    validationError = null
                },
                label = { Text("Customer name or Telegram ID") },
                placeholder = { Text("e.g., Ali Rezaei (@ali_vpn)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("customer_name_input"),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Preset Plans Section (with Admin preset price & New User bonus GB editing)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Plans",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isAdmin) {
                    TextButton(
                        onClick = {
                            presetEditorError = null
                            if (showPresetEditor) {
                                showPresetEditor = false
                                editingPresetId = null
                            } else {
                                val currentPreset = presets.firstOrNull { it.label == selectedPresetName }
                                if (currentPreset != null) {
                                    editingPresetId = currentPreset.id
                                    newPresetLabel = currentPreset.label
                                    newPresetGb = formatGbPlain(currentPreset.dataGb)
                                    newPresetBonusGb = formatGbPlain(currentPreset.newUserBonusGb)
                                    newPresetPrice = currentPreset.defaultPriceToman.toString()
                                } else {
                                    editingPresetId = null
                                    newPresetLabel = ""
                                    newPresetGb = ""
                                    newPresetBonusGb = "5"
                                    newPresetPrice = ""
                                }
                                showPresetEditor = true
                            }
                        },
                        modifier = Modifier.testTag("toggle_new_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Edit or Add Preset",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (showPresetEditor) "Close" else "Edit plans")
                    }
                }
            }

            if (isAdmin && showPresetEditor) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (editingPresetId != null) {
                                    "Edit plan"
                                } else {
                                    "New plan"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (editingPresetId != null) {
                                TextButton(
                                    onClick = {
                                        editingPresetId = null
                                        newPresetLabel = ""
                                        newPresetGb = ""
                                        newPresetBonusGb = "5"
                                        newPresetPrice = ""
                                        presetEditorError = null
                                    }
                                ) {
                                    Text("New plan instead")
                                }
                            }
                        }
                        Text(
                            text = "Tap a plan below to load it. Changes apply to every seller's new sales.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPresetLabel,
                            onValueChange = {
                                newPresetLabel = it
                                presetEditorError = null
                            },
                            label = { Text("Plan name (e.g., 20 GB Standard)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newPresetGb,
                                onValueChange = {
                                    newPresetGb = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() || ch == '.' }
                                    presetEditorError = null
                                },
                                label = { Text("Plan GB") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newPresetBonusGb,
                                onValueChange = {
                                    newPresetBonusGb = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() || ch == '.' }
                                    presetEditorError = null
                                },
                                label = { Text("New-user bonus GB") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPresetPrice,
                            onValueChange = {
                                newPresetPrice = InputValidator.normalizeDigits(it).filter { ch -> ch.isDigit() }
                                presetEditorError = null
                            },
                            label = { Text("Plan price") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (presetEditorError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = presetEditorError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val gbRes = InputValidator.validateDataGb(newPresetGb, "Plan GB")
                                    val bonusRes = InputValidator.validateBonusGb(newPresetBonusGb)
                                    val priceRes = InputValidator.validatePriceToman(newPresetPrice, "Plan price")
                                    when {
                                        newPresetLabel.isBlank() -> presetEditorError = "Please enter a plan name."
                                        gbRes.isFailure -> presetEditorError = gbRes.exceptionOrNull()?.message
                                        bonusRes.isFailure -> presetEditorError = bonusRes.exceptionOrNull()?.message
                                        priceRes.isFailure -> presetEditorError = priceRes.exceptionOrNull()?.message
                                        else -> {
                                            val gb = gbRes.getOrThrow()
                                            val bonus = bonusRes.getOrThrow()
                                            val pr = priceRes.getOrThrow()
                                            onSaveOrUpdatePreset(editingPresetId, newPresetLabel.trim(), gb, pr, bonus)
                                            baseDataGbText = formatGbPlain(gb)
                                            bonusGbText = formatGbPlain(bonus)
                                            priceTomanText = pr.toString()
                                            originalPresetPrice = pr
                                            selectedPresetName = newPresetLabel.trim()
                                            editingPresetId = null
                                            newPresetLabel = ""
                                            newPresetGb = ""
                                            newPresetBonusGb = ""
                                            newPresetPrice = ""
                                            presetEditorError = null
                                            showPresetEditor = false
                                        }
                                    }
                                }
                            ) {
                                Text(if (editingPresetId != null) "Update plan" else "Add plan")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = selectedPresetName == preset.label &&
                        (parsedBaseGb == preset.dataGb)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            baseDataGbText = formatGbPlain(preset.dataGb)
                            bonusGbText = formatGbPlain(preset.newUserBonusGb)
                            priceTomanText = preset.defaultPriceToman.toString()
                            originalPresetPrice = preset.defaultPriceToman
                            selectedPresetName = preset.label
                            validationError = null
                            if (showPresetEditor && isAdmin) {
                                editingPresetId = preset.id
                                newPresetLabel = preset.label
                                newPresetGb = formatGbPlain(preset.dataGb)
                                newPresetBonusGb = formatGbPlain(preset.newUserBonusGb)
                                newPresetPrice = preset.defaultPriceToman.toString()
                            }
                        },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                val bonusHint = if (preset.newUserBonusGb > 0.0) {
                                    " (+${TomanFormatter.formatGb(preset.newUserBonusGb)} new)"
                                } else ""
                                Text(
                                    text = "${TomanFormatter.formatGb(preset.dataGb)}$bonusHint • ${TomanFormatter.format(preset.defaultPriceToman)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        trailingIcon = if (isAdmin && !preset.id.startsWith("builtin_")) {
                            {
                                IconButton(
                                    onClick = { onDeleteCustomPreset(preset) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete custom preset",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("preset_chip_${preset.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2B. "New User" Option Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isNewUser = !isNewUser }
                    .testTag("sheet_new_user_toggle_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isNewUser) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isNewUser) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isNewUser,
                            onCheckedChange = { isNewUser = it },
                            modifier = Modifier.testTag("sheet_new_user_checkbox")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isNewUser) {
                                        "New user: +${TomanFormatter.formatGb(parsedBonusGb)} bonus"
                                    } else {
                                        "New user (first purchase bonus)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (isNewUser) {
                                    "${TomanFormatter.formatAllocationSummary(totalAllocatedGb, parsedBonusGb, true)}. The price stays the same; the bonus GB counts toward infrastructure cost."
                                } else {
                                    "Adds ${TomanFormatter.formatGb(parsedBonusGb)} bonus data at the same price."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Manual Override Inputs for Data Volume (GB) and Price (Toman)
            Text(
                text = "Data and price",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = baseDataGbText,
                    onValueChange = { input ->
                        baseDataGbText = InputValidator.normalizeDigits(input).filter { it.isDigit() || it == '.' }
                        validationError = null
                    },
                    label = { Text(if (isNewUser) "Plan data (before bonus)" else "Data") },
                    suffix = { Text("GB") },
                    isError = baseDataGbText.isNotBlank() && baseGbValidation.isFailure,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("data_gb_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                OutlinedTextField(
                    value = priceTomanText,
                    onValueChange = { input ->
                        priceTomanText = InputValidator.normalizeDigits(input).filter { it.isDigit() }
                        validationError = null
                    },
                    label = { Text("Final price") },
                    suffix = { Text("Toman") },
                    isError = priceTomanText.isNotBlank() && priceValidation.isFailure,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1.45f)
                        .testTag("price_toman_input"),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            if (validationError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = validationError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("sale_validation_error_text")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Discount Override Helpers
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = {
                        val base = if (originalPresetPrice > 0L) originalPresetPrice else parsedPrice
                        if (originalPresetPrice <= 0L) originalPresetPrice = base
                        priceTomanText = ((base * 90L) / 100L).toString()
                        validationError = null
                    },
                    label = { Text("−10%") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Discount,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = {
                        val base = if (originalPresetPrice > 0L) originalPresetPrice else parsedPrice
                        if (originalPresetPrice <= 0L) originalPresetPrice = base
                        priceTomanText = ((base * 80L) / 100L).toString()
                        validationError = null
                    },
                    label = { Text("−20%") }
                )
                AssistChip(
                    onClick = {
                        val next = (parsedPrice - 20_000L).coerceAtLeast(0L)
                        if (originalPresetPrice <= 0L) originalPresetPrice = parsedPrice
                        priceTomanText = next.toString()
                        validationError = null
                    },
                    label = { Text("−20,000 Toman") }
                )
                AssistChip(
                    onClick = {
                        val next = (parsedPrice - 50_000L).coerceAtLeast(0L)
                        if (originalPresetPrice <= 0L) originalPresetPrice = parsedPrice
                        priceTomanText = next.toString()
                        validationError = null
                    },
                    label = { Text("−50,000 Toman") }
                )
                if (activeDiscount > 0L) {
                    AssistChip(
                        onClick = {
                            priceTomanText = originalPresetPrice.toString()
                            validationError = null
                        },
                        label = { Text("Plan price") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live preview of this one sale (UX-01), using the same calculation as every summary.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sale_sheet_live_breakdown_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SectionHeading("This sale")
                    PreviewLine("Data allocated", TomanFormatter.formatAllocationSummary(totalAllocatedGb, effectiveBonusGb, isNewUser))
                    PreviewLine("Infrastructure cost", TomanFormatter.format(liveSaleBreakdown.infrastructureCostToman))
                    if (isPrivateAdminBook) {
                        PreviewLine("Business profit", TomanFormatter.format(liveSaleBreakdown.netProfitToman))
                    } else {
                        PreviewLine("Seller profit (${liveSaleBreakdown.sellerSharePercent}%)", TomanFormatter.format(liveSaleBreakdown.sellerProfitToman))
                        PreviewLine("Admin profit (${liveSaleBreakdown.adminSharePercent}%)", TomanFormatter.format(liveSaleBreakdown.adminProfitToman))
                        PreviewLine("Amount due to Admin", TomanFormatter.format(liveSaleBreakdown.amountSellerPaysAdminToman))
                    }
                    if (priceValidation.isSuccess && liveSaleBreakdown.netProfitToman < 0L) {
                        InlineWarning(
                            "This price is below the infrastructure cost for this sale (a loss of ${TomanFormatter.format(-liveSaleBreakdown.netProfitToman)}). You can still save it."
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Paid Status Checkbox Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isPaid = !isPaid }
                    .testTag("sheet_paid_toggle_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPaid) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    }
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isPaid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPaid,
                        onCheckedChange = { isPaid = it },
                        modifier = Modifier.testTag("sheet_paid_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPaid) "Paid" else "Not paid yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Whether the customer has paid you. It doesn't change any totals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Optional Discount / Customer Note
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sale_note_input"),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = {
                    val baseGbCheck = InputValidator.validateDataGb(baseDataGbText, "Base Data GB")
                    val bonusGbCheck = if (isNewUser) InputValidator.validateBonusGb(bonusGbText) else Result.success(0.0)
                    val priceCheck = InputValidator.validatePriceToman(priceTomanText, "Final Price")

                    when {
                        customerName.trim().isEmpty() -> {
                            validationError = "Please enter a customer name or identifier."
                        }
                        baseGbCheck.isFailure -> {
                            validationError = baseGbCheck.exceptionOrNull()?.message
                        }
                        bonusGbCheck.isFailure -> {
                            validationError = bonusGbCheck.exceptionOrNull()?.message
                        }
                        priceCheck.isFailure -> {
                            validationError = priceCheck.exceptionOrNull()?.message
                        }
                        else -> {
                            val validBaseGb = baseGbCheck.getOrThrow()
                            val validBonusGb = bonusGbCheck.getOrThrow()
                            val validTotalGb = validBaseGb + validBonusGb
                            val validPrice = priceCheck.getOrThrow()
                            val effectiveOriginal = if (originalPresetPrice > validPrice) {
                                originalPresetPrice
                            } else {
                                validPrice
                            }
                            onSaveSale(
                                editingEntry?.id,
                                customerName.trim(),
                                validTotalGb,
                                validPrice,
                                effectiveOriginal,
                                selectedPresetName,
                                isPaid,
                                isNewUser,
                                validBonusGb,
                                note.trim()
                            )
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_sale_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (editingEntry == null) "Save sale" else "Update sale",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ResetLedgerDialog(
    bookTitle: String,
    metrics: LedgerSummaryMetrics,
    isSellerBook: Boolean,
    onDismiss: () -> Unit,
    onConfirmReset: (cycleTitle: String, keepArchive: Boolean) -> Unit
) {
    val defaultCycleName = remember {
        val fmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        "Cycle ending ${fmt.format(Date())}"
    }
    var cycleTitle by rememberSaveable { mutableStateOf(defaultCycleName) }
    var keepArchive by rememberSaveable { mutableStateOf(true) }
    // DATA-02: The limit is stated before anything is changed; nothing is ever truncated.
    val exceedsArchiveCap = metrics.totalCustomers > LedgerRepository.MAX_ARCHIVE_ITEMS

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Start a new cycle?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "This closes the current cycle of $bookTitle (${salesCountLabel(metrics.totalCustomers)}, ${TomanFormatter.format(metrics.totalSalesToman)}) and starts again from zero.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (exceedsArchiveCap) {
                    InlineWarning(
                        "This cycle has ${metrics.totalCustomers} sales, over the ${LedgerRepository.MAX_ARCHIVE_ITEMS}-sale limit. Nothing will be changed. Export a PDF first and contact the developer."
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keepArchive = !keepArchive }
                        ) {
                            Checkbox(
                                checked = keepArchive,
                                onCheckedChange = { keepArchive = it },
                                modifier = Modifier.testTag("keep_archive_checkbox")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Save this cycle to Archives",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Recommended. Keeps every sale for viewing and PDF export.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (keepArchive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = cycleTitle,
                                onValueChange = { cycleTitle = it.take(120) },
                                label = { Text("Archive name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("archive_cycle_title_input")
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isSellerBook) {
                                    "Without an archive these sales disappear from your app. Reports you already sent to the Admin are kept."
                                } else {
                                    "Without an archive these sales disappear from the app."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmReset(cycleTitle, keepArchive) },
                enabled = !exceedsArchiveCap,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("confirm_reset_ledger_button")
            ) {
                Text("Start new cycle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfExportReadyDialog(
    exportReady: ActivePdfExportReady,
    onDismiss: () -> Unit,
    onSaveToDevice: () -> Unit,
    onOpenPdf: () -> Unit,
    onSharePdf: () -> Unit
) {
    val payload = exportReady.payload
    val metrics = remember(payload) {
        LedgerSummaryMetrics(
            totalSalesToman = payload.totalSalesToman,
            totalGb = payload.totalGb,
            totalCustomers = payload.rows.size,
            costPerGbToman = payload.costPerGbToman,
            adminProfitPercent = payload.adminProfitPercent
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "PDF ready",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GrossSalesHeader(metrics)
                FinancialBreakdownSection(metrics = metrics, isPrivateAdminBook = payload.isPrivateAdminBook)
                Text(
                    text = "Save a copy, open it in a PDF viewer, or share it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSaveToDevice,
                        modifier = Modifier.testTag("save_pdf_device_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save PDF")
                    }
                    OutlinedButton(
                        onClick = onOpenPdf,
                        modifier = Modifier.testTag("open_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                    OutlinedButton(
                        onClick = onSharePdf,
                        modifier = Modifier.testTag("share_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * UI-03: Detail sheet for a closed cycle or a sent report. Header lines are separate, the close
 * button keeps its own touch target, money is stacked, and actions wrap instead of squeezing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveDetailBottomSheet(
    archive: LedgerArchive,
    fallbackCostPerGbToman: Long = 1_000L,
    fallbackAdminProfitPercent: Int = 50,
    isPrivateAdminBook: Boolean = false,
    isAdmin: Boolean = false,
    onDismiss: () -> Unit,
    onToggleItemPaid: (itemId: String, newPaid: Boolean) -> Unit,
    onExportPdf: (LedgerArchive) -> Unit,
    onVoidArchive: (LedgerArchive, String) -> Unit,
    onSetSettled: (LedgerArchive, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items: List<ArchivedSaleItem> = remember(archive) { archive.parsedItems() }
    val archiveMetrics = remember(archive, fallbackCostPerGbToman, fallbackAdminProfitPercent) {
        archive.computeMetrics(fallbackCostPerGbToman, fallbackAdminProfitPercent)
    }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.US) }
    val dateText = archive.createdAt?.toDate()?.let { dateFormat.format(it) }
    val cleanOwner = TomanFormatter.sanitizeOwnerDisplayName(archive.ownerName)
    val isReport = archive.isSubmission || archive.isLegacyReport
    val paymentEditable = !archive.isVoided && !archive.isLegacyReport
    var showVoidDialog by rememberSaveable(archive.id) { mutableStateOf(false) }
    var voidReason by rememberSaveable(archive.id) { mutableStateOf("") }

    if (showVoidDialog) {
        AlertDialog(
            onDismissRequest = { showVoidDialog = false },
            title = { Text("Void this archive?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("It stays in Archives, marked as voided, with its sales and totals unchanged. This can't be undone.")
                    OutlinedTextField(
                        value = voidReason,
                        onValueChange = { voidReason = it.take(300) },
                        label = { Text("Reason") },
                        supportingText = { Text("At least 3 characters") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVoidDialog = false
                        onVoidArchive(archive, voidReason.trim())
                    },
                    enabled = voidReason.trim().length >= 3,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Void")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoidDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // UI-03: One LazyColumn for the whole sheet so the summary, actions and rows scroll together.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("archive_detail_sheet_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = when {
                                archive.isLegacyReport -> "Older report"
                                archive.isSubmission -> "Report"
                                else -> archive.cycleTitle
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = cleanOwner,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                archive.hasPendingSync -> "Saved on this phone, waiting to upload"
                                dateText == null -> "Date not available"
                                isReport -> "Uploaded $dateText"
                                else -> "Closed $dateText"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (archive.isSettled) {
                            Text(
                                text = "Settled by the Admin",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            if (archive.isVoided) {
                item {
                    InlineWarning(
                        "Voided${archive.voidReason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}. Kept for the record; it can't be edited."
                    )
                }
            }
            if (archive.isLegacyReport) {
                item {
                    InlineWarning("Sent with an earlier app version. It is read-only and its history was not kept.")
                }
            }
            if (archive.malformedItemCount > 0) {
                item {
                    InlineWarning("${archive.malformedItemCount} damaged sale record(s) in this archive could not be read and are not counted.")
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GrossSalesHeader(archiveMetrics)
                        FinancialBreakdownSection(
                            metrics = archiveMetrics,
                            isPrivateAdminBook = isPrivateAdminBook,
                            ratesNote = "Rates saved with this record: ${ratesLabel(archiveMetrics)}"
                        )
                    }
                }
            }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExportPdf(archive) },
                        modifier = Modifier.testTag("archive_sheet_export_pdf_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export PDF")
                    }
                    if (archive.isSubmission && isAdmin) {
                        OutlinedButton(
                            onClick = { onSetSettled(archive, !archive.isSettled) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (archive.isSettled) "Mark not settled" else "Mark settled")
                        }
                    }
                    if (!isReport && !archive.isVoided) {
                        OutlinedButton(
                            onClick = { showVoidDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("archive_sheet_void_button")
                        ) {
                            Text("Void…")
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeading("Sales (${items.size})")
                if (paymentEditable) {
                    Text(
                        text = "Tick a sale when the customer has paid. Totals don't change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(items, key = { idx, item -> "${idx}_${item.id}" }) { idx, item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = paymentEditable) { onToggleItemPaid(item.itemId, !item.isPaid) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.isPaid) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                        }
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (item.isPaid) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
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
                                checked = item.isPaid,
                                onCheckedChange = { checked -> onToggleItemPaid(item.itemId, checked) },
                                enabled = paymentEditable,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = MaterialTheme.colorScheme.tertiary
                                ),
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Customer paid: ${item.customerName}"
                                        stateDescription = if (item.isPaid) "Paid" else "Not paid yet"
                                    }
                                    .testTag("archive_paid_checkbox_$idx")
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${idx + 1}. ${item.customerName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (item.isNewUser) {
                                Text(
                                    text = "New user",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Text(
                                text = TomanFormatter.formatAllocationSummary(item.safeDataGb, item.safeBonusGb, item.isNewUser),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.presetName.ifBlank { "Custom plan" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.note.isNotBlank()) {
                                Text(
                                    text = item.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = TomanFormatter.format(item.priceToman),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = if (item.isPaid) "Paid" else "Not paid yet",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (item.isPaid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
