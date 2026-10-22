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
                        text = if (editingEntry == null) "Record VPN Sale" else "Edit VPN Sale",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Select a preset package, apply New User bonus GB, or override price in Toman",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                label = { Text("Customer Name / Telegram ID") },
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
                    text = "Quick-Select GB & Toman Presets",
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
                        Text(if (showPresetEditor) "Close Preset Editor" else "Edit Presets / +Add")
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
                                    "Edit Preset Price & New User Bonus (Admin)"
                                } else {
                                    "Create New Package Preset (Admin)"
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
                                    Text("+ New Preset Instead")
                                }
                            }
                        }
                        Text(
                            text = "Tap any preset chip below to load it and configure its Toman price and New User bonus GB.",
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
                            label = { Text("Preset Label (e.g., 20 GB Standard)") },
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
                                label = { Text("New User +GB") },
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
                            label = { Text("Preset Price (Toman)") },
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
                                    val priceRes = InputValidator.validatePriceToman(newPresetPrice, "Preset Price")
                                    when {
                                        newPresetLabel.isBlank() -> presetEditorError = "Please enter a preset label."
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
                                Text(if (editingPresetId != null) "Update Preset" else "Save New Preset")
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
                                    contentDescription = "New User Bonus",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isNewUser) {
                                        "New User — +${TomanFormatter.formatGb(parsedBonusGb)} Extra Data Applied"
                                    } else {
                                        "New User (First Purchase Bonus GB)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (isNewUser) {
                                    "${TomanFormatter.formatGbNumberOnly(totalAllocatedGb)} GB total (${TomanFormatter.formatGbNumberOnly(parsedBaseGb)} + ${TomanFormatter.formatGbNumberOnly(parsedBonusGb)} bonus) • Price stays ${TomanFormatter.format(parsedPrice)}"
                                } else {
                                    "Check if this is a new customer to include +${TomanFormatter.formatGb(parsedBonusGb)} bonus GB in infrastructure cost at the same sale price"
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
                text = "Volume & Price (Manually Overridable for Discounts)",
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
                    label = { Text(if (isNewUser) "Base Plan (GB)" else "Data (GB)") },
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
                    label = { Text("Final Price (Toman)") },
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
                    label = { Text("-10% Discount") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Discount,
                            contentDescription = "10% discount",
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
                    label = { Text("-20% Discount") }
                )
                AssistChip(
                    onClick = {
                        val next = (parsedPrice - 20_000L).coerceAtLeast(0L)
                        if (originalPresetPrice <= 0L) originalPresetPrice = parsedPrice
                        priceTomanText = next.toString()
                        validationError = null
                    },
                    label = { Text("-20,000 Toman") }
                )
                AssistChip(
                    onClick = {
                        val next = (parsedPrice - 50_000L).coerceAtLeast(0L)
                        if (originalPresetPrice <= 0L) originalPresetPrice = parsedPrice
                        priceTomanText = next.toString()
                        validationError = null
                    },
                    label = { Text("-50,000 Toman") }
                )
                if (activeDiscount > 0L) {
                    AssistChip(
                        onClick = {
                            priceTomanText = originalPresetPrice.toString()
                            validationError = null
                        },
                        label = { Text("Reset to Preset") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Per-Sale Financial Breakdown Preview (UX-01)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sale_sheet_live_breakdown_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "THIS SALE BREAKDOWN PREVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Total Allocated Data: ${
                            if (isNewUser && effectiveBonusGb > 0.0) {
                                "${TomanFormatter.formatGbNumberOnly(totalAllocatedGb)} GB total (${TomanFormatter.formatGbNumberOnly(parsedBaseGb)} + ${TomanFormatter.formatGbNumberOnly(effectiveBonusGb)} bonus)"
                            } else {
                                TomanFormatter.formatGb(totalAllocatedGb)
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Infrastructure Cost: ${TomanFormatter.format(liveSaleBreakdown.infrastructureCostToman)} (${TomanFormatter.format(costPerGbToman)}/GB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Seller Profit (${liveSaleBreakdown.sellerSharePercent}%): ${TomanFormatter.format(liveSaleBreakdown.sellerProfitToman)}  •  Admin Profit (${liveSaleBreakdown.adminSharePercent}%): ${TomanFormatter.format(liveSaleBreakdown.adminProfitToman)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isPrivateAdminBook) {
                            "Admin Infra + Share: ${TomanFormatter.format(liveSaleBreakdown.amountSellerPaysAdminToman)}"
                        } else {
                            "Amount Seller Pays Admin: ${TomanFormatter.format(liveSaleBreakdown.amountSellerPaysAdminToman)}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                            text = if (isPaid) "Paid — Checked (Green Item)" else "Unpaid — Unchecked (Amber Item)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Visual row indicator only; does not change profit split calculations",
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
                label = { Text("Optional Note (e.g., Referral discount, Server #2)") },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_sale_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Save Sale")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (editingEntry == null) {
                        "Save Sale (${TomanFormatter.formatGb(totalAllocatedGb)} • ${TomanFormatter.format(parsedPrice)})"
                    } else {
                        "Update Sale (${TomanFormatter.formatGb(totalAllocatedGb)} • ${TomanFormatter.format(parsedPrice)})"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ResetLedgerDialog(
    bookTitle: String,
    metrics: LedgerSummaryMetrics,
    onDismiss: () -> Unit,
    onConfirmReset: (cycleTitle: String, keepArchive: Boolean) -> Unit
) {
    val defaultCycleName = remember {
        val fmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        "Cycle • ${fmt.format(Date())}"
    }
    var cycleTitle by rememberSaveable { mutableStateOf(defaultCycleName) }
    var keepArchive by rememberSaveable { mutableStateOf(true) }
    val exceedsArchiveCap = metrics.totalCustomers > LedgerRepository.MAX_ARCHIVE_ITEMS

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = "Reset Ledger",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Reset & Clear Active Ledger?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This will clear all ${metrics.totalCustomers} sales and reset the gross sales total (${TomanFormatter.format(metrics.totalSalesToman)}) to 0 Toman for $bookTitle.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (keepArchive && exceedsArchiveCap) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "This ledger has ${metrics.totalCustomers} sales, which exceeds the ${LedgerRepository.MAX_ARCHIVE_ITEMS}-sale archive snapshot limit. Export a PDF first or reduce entries before archiving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
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
                                    text = "Save snapshot to Archives",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Keep a full record for PDF export & inspection",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (keepArchive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = cycleTitle,
                                onValueChange = { cycleTitle = it },
                                label = { Text("Archive Cycle Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("archive_cycle_title_input")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmReset(cycleTitle, keepArchive) },
                enabled = !(keepArchive && exceedsArchiveCap),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("confirm_reset_ledger_button")
            ) {
                Text(if (keepArchive) "Archive & Reset to 0" else "Wipe Without Archive")
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
    val settlementLabel = if (payload.isPrivateAdminBook) {
        "ADMIN INFRA + SHARE"
    } else {
        "AMOUNT SELLER PAYS ADMIN"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "Graphical PDF Ready",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "NodeSplit PDF Report Ready",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top of Export Section: Stacked Settlement Summary (no horizontal clipping)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "EXPORT SETTLEMENT SUMMARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        StackedDialogMetricBlock(
                            label = "Gross Sales (${payload.rows.size} sales)",
                            value = TomanFormatter.format(payload.totalSalesToman)
                        )
                        StackedDialogMetricBlock(
                            label = "Total Allocated Data",
                            value = "${TomanFormatter.formatGb(payload.totalGb)} (Rate: ${TomanFormatter.format(payload.costPerGbToman)}/GB)"
                        )
                        StackedDialogMetricBlock(
                            label = "1. Infrastructure Cost",
                            value = TomanFormatter.format(payload.infraCostToman)
                        )
                        StackedDialogMetricBlock(
                            label = "Net Profit",
                            value = TomanFormatter.format(payload.netProfitToman)
                        )
                        StackedDialogMetricBlock(
                            label = "2. Seller Profit (${payload.sellerProfitPercent}%)",
                            value = TomanFormatter.format(payload.sellerProfitToman),
                            valueColor = MaterialTheme.colorScheme.secondary
                        )
                        StackedDialogMetricBlock(
                            label = "3. Admin Profit (${payload.adminProfitPercent}%)",
                            value = TomanFormatter.format(payload.adminProfitToman),
                            valueColor = MaterialTheme.colorScheme.primary
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

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
                                    text = settlementLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = TomanFormatter.format(payload.sellerPayableToAdminToman),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Admin Profit (${TomanFormatter.format(payload.adminProfitToman)}) + Infra Cost (${TomanFormatter.format(payload.infraCostToman)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Choose whether to save a copy to device storage, open in a PDF viewer, or share directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = exportReady.file.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp)
                    )
                }

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
                            contentDescription = "Save PDF",
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
                            contentDescription = "Open PDF",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open PDF")
                    }
                    OutlinedButton(
                        onClick = onSharePdf,
                        modifier = Modifier.testTag("share_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share PDF",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share PDF")
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

@Composable
private fun StackedDialogMetricBlock(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveDetailBottomSheet(
    archive: LedgerArchive,
    fallbackCostPerGbToman: Long = 1_000L,
    fallbackAdminProfitPercent: Int = 50,
    isPrivateAdminBook: Boolean = false,
    onDismiss: () -> Unit,
    onToggleItemPaid: (itemIndex: Int, newPaid: Boolean) -> Unit,
    onExportPdf: (LedgerArchive) -> Unit,
    onDeleteArchive: (LedgerArchive) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items: List<ArchivedSaleItem> = remember(archive) { archive.parsedItems() }
    val archiveMetrics = remember(archive, fallbackCostPerGbToman, fallbackAdminProfitPercent) {
        archive.computeMetrics(fallbackCostPerGbToman, fallbackAdminProfitPercent)
    }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US) }
    val closedDate = archive.createdAt?.toDate()?.let { dateFormat.format(it) } ?: "Archived"
    val cleanOwner = TomanFormatter.sanitizeOwnerDisplayName(archive.ownerName)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val settlementLabel = if (isPrivateAdminBook) {
        "ADMIN INFRA + SHARE"
    } else {
        "AMOUNT SELLER PAYS ADMIN"
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Archived Snapshot?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete \"${archive.cycleTitle}\" (${items.size} sales • ${TomanFormatter.format(archive.totalSalesToman)})?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteArchive(archive)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
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
        // UI-03: Single LazyColumn for the entire sheet so all summary metrics and rows scroll together cleanly
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("archive_detail_sheet_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Sheet Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Archive,
                                contentDescription = "Archive",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = archive.cycleTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "$cleanOwner • Closed $closedDate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (archive.hasPendingSync) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = "Pending cloud sync",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Saved locally — waiting to sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            // 1B. Warning if malformed items were detected (DATA-03)
            if (archive.malformedItemCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Data warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "${archive.malformedItemCount} corrupted sale record(s) in this archive were skipped.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 2. Vertical Stacked Summary Card (UI-01 / UI-03)
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
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "GROSS SALES (${items.size} SALES)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = TomanFormatter.format(archiveMetrics.totalSalesToman),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "TOTAL ALLOCATED DATA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${TomanFormatter.formatGb(archiveMetrics.totalGb)} • Rate: ${TomanFormatter.format(archiveMetrics.costPerGbToman)}/GB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        ArchiveMetricBlock(
                            title = "1. Infrastructure Cost",
                            amount = TomanFormatter.format(archiveMetrics.infraCostToman),
                            subtitle = "${TomanFormatter.formatGb(archiveMetrics.totalGb)} × ${TomanFormatter.format(archiveMetrics.costPerGbToman)}",
                            amountColor = MaterialTheme.colorScheme.onSurface
                        )

                        ArchiveMetricBlock(
                            title = "Net Profit",
                            amount = TomanFormatter.format(archiveMetrics.netProfitToman),
                            subtitle = "Gross Sales minus Infrastructure Cost",
                            amountColor = MaterialTheme.colorScheme.onSurface
                        )

                        ArchiveMetricBlock(
                            title = "2. Seller Profit (${archiveMetrics.sellerProfitPercent}%)",
                            amount = TomanFormatter.format(archiveMetrics.sellerProfitToman),
                            subtitle = "Seller Net Profit Share",
                            amountColor = MaterialTheme.colorScheme.secondary
                        )

                        ArchiveMetricBlock(
                            title = "3. Admin Profit (${archiveMetrics.adminProfitPercent}%)",
                            amount = TomanFormatter.format(archiveMetrics.adminProfitToman),
                            subtitle = "Admin Net Profit Share",
                            amountColor = MaterialTheme.colorScheme.primary
                        )

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = settlementLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = TomanFormatter.format(archiveMetrics.sellerPayableToAdminToman),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Admin Profit (${TomanFormatter.format(archiveMetrics.adminProfitToman)}) + Infra Cost (${TomanFormatter.format(archiveMetrics.infraCostToman)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onExportPdf(archive) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("archive_sheet_export_pdf_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = "Export PDF")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export PDF Report")
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("archive_sheet_delete_button")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete Archive")
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Archived Sales (${items.size}) — Tap Checkbox to Toggle Paid/Unpaid",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4. Archived Sale Rows
            itemsIndexed(items, key = { idx, item -> "${idx}_${item.id}" }) { idx, item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleItemPaid(idx, !item.isPaid) },
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.minimumInteractiveComponentSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Checkbox(
                                    checked = item.isPaid,
                                    onCheckedChange = { checked -> onToggleItemPaid(idx, checked) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        uncheckedColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    modifier = Modifier.testTag("archive_paid_checkbox_$idx")
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${item.customerName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (item.isNewUser) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            val bonusTxt = if (item.bonusGb > 0.0) "+${TomanFormatter.formatGb(item.bonusGb)}" else "NEW"
                                            Text(
                                                text = "NEW USER ($bonusTxt)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                val gbText = if (item.isNewUser && item.bonusGb > 0.0) {
                                    "${TomanFormatter.formatGbNumberOnly(item.dataGb)} GB total (${TomanFormatter.formatGbNumberOnly(item.baseDataGb)} + ${TomanFormatter.formatGbNumberOnly(item.bonusGb)} bonus)"
                                } else {
                                    TomanFormatter.formatGb(item.dataGb)
                                }
                                Text(
                                    text = "$gbText • ${item.presetName.ifBlank { "Custom Plan" }}",
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
                                    color = if (item.isPaid) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveMetricBlock(
    title: String,
    amount: String,
    subtitle: String,
    amountColor: androidx.compose.ui.graphics.Color
) {
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
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.titleSmall,
                color = amountColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
