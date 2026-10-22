package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.LedgerSummaryMetrics
import com.example.data.model.TomanFormatter

/*
 * UI-01..UI-04: Shared building blocks for every money summary (dashboard, archive card, archive
 * sheet, sent report, PDF dialog). Labels, amounts and explanations are always separate Text
 * children stacked in a Column, so long Toman amounts wrap onto their own line instead of being
 * squeezed, clipped or joined to the next label. Nothing here sets maxLines or ellipsis.
 */

/** Label above a full amount, with an optional explanation on its own line. */
@Composable
fun MetricBlock(
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.titleMedium,
                color = amountColor,
                fontWeight = FontWeight.Bold
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The highlighted "Amount due to Admin" (or private-book profit) block. */
@Composable
fun AmountDueBlock(
    label: String,
    amount: String,
    explanation: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("seller_payable_to_admin_banner"),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** Short facts ("2 sales", "75 GB allocated") that wrap as whole chips, never mid-fact. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        parts.filter { it.isNotBlank() }.forEach { part ->
            Text(
                text = part,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun InlineWarning(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Gross sales headline plus the sale count and allocated data. */
@Composable
fun GrossSalesHeader(metrics: LedgerSummaryMetrics, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Gross sales",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = TomanFormatter.format(metrics.totalSalesToman),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        MetadataLine(
            parts = listOf(
                salesCountLabel(metrics.salesCount),
                "${TomanFormatter.formatGb(metrics.totalGb)} allocated",
                if (metrics.totalBonusGb > 0.0) "incl. ${TomanFormatter.formatGb(metrics.totalBonusGb)} bonus" else ""
            )
        )
    }
}

fun salesCountLabel(count: Int): String = if (count == 1) "1 sale" else "$count sales"

/**
 * The financial breakdown in reading order: infrastructure, seller profit, admin profit, then the
 * amount due. For the Admin's own private book there is no one to pay, so it shows the business
 * profit instead of a self-transfer (the stored arithmetic is unchanged).
 */
@Composable
fun FinancialBreakdownSection(
    metrics: LedgerSummaryMetrics,
    isPrivateAdminBook: Boolean,
    modifier: Modifier = Modifier,
    ratesNote: String? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Financial breakdown")
        MetricBlock(
            label = "Infrastructure cost",
            amount = TomanFormatter.format(metrics.infraCostToman),
            detail = "${TomanFormatter.formatGb(metrics.totalGb)} × ${TomanFormatter.format(metrics.costPerGbToman)} per GB"
        )
        if (isPrivateAdminBook) {
            AmountDueBlock(
                label = "Business profit",
                amount = TomanFormatter.format(metrics.netProfitToman),
                explanation = "Gross sales minus infrastructure cost, before other expenses"
            )
        } else {
            MetricBlock(
                label = "Seller profit (${metrics.sellerProfitPercent}%)",
                amount = TomanFormatter.format(metrics.sellerProfitToman),
                amountColor = MaterialTheme.colorScheme.secondary
            )
            MetricBlock(
                label = "Admin profit (${metrics.adminProfitPercent}%)",
                amount = TomanFormatter.format(metrics.adminProfitToman),
                amountColor = MaterialTheme.colorScheme.primary
            )
            AmountDueBlock(
                label = "Amount due to Admin",
                amount = TomanFormatter.format(metrics.sellerPayableToAdminToman),
                explanation = "Infrastructure cost + admin profit"
            )
        }
        if (metrics.netProfitToman < 0L) {
            InlineWarning(
                "Sales are below the infrastructure cost: a loss of ${TomanFormatter.format(-metrics.netProfitToman)}, shared by the profit split."
            )
        }
        if (metrics.hasLegacyAssumedRates) {
            InlineWarning("This older record did not save its rates, so today's cost per GB and split are used.")
        }
        if (metrics.hasTotalsMismatch) {
            InlineWarning("The totals stored in this record don't match its sales. The figures shown are recalculated from the sales.")
        }
        if (!ratesNote.isNullOrBlank()) {
            Text(
                text = ratesNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun ratesLabel(metrics: LedgerSummaryMetrics): String =
    "${TomanFormatter.format(metrics.costPerGbToman)} per GB • Seller ${metrics.sellerProfitPercent}% / Admin ${metrics.adminProfitPercent}%"
