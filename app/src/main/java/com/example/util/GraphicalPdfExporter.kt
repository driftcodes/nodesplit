package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.ArchivedSaleItem
import com.example.data.model.FinancialCalculator
import com.example.data.model.LedgerArchive
import com.example.data.model.SaleEntry
import com.example.data.model.TomanFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class PdfReportRow(
    val index: Int,
    val customerName: String,
    val dataGb: Double,
    val baseDataGb: Double,
    val isNewUser: Boolean,
    val bonusGb: Double,
    val presetName: String,
    val priceToman: Long,
    val originalPriceToman: Long,
    val isPaid: Boolean,
    val note: String,
    val dateFormatted: String
)

data class PdfReportPayload(
    val reportTitle: String,
    val subtitle: String,
    val ownerName: String,
    val isArchivedSnapshot: Boolean,
    val isPrivateAdminBook: Boolean = false,
    val generatedAtFormatted: String,
    val totalSalesToman: Long,
    val totalGb: Double,
    val costPerGbToman: Long,
    val adminProfitPercent: Int,
    val infraCostToman: Long,
    val netProfitToman: Long,
    val sellerProfitToman: Long,
    val adminProfitToman: Long,
    val sellerPayableToAdminToman: Long,
    val rows: List<PdfReportRow>
) {
    val sellerProfitPercent: Int
        get() = (100 - adminProfitPercent.coerceIn(0, 100))
}

object GraphicalPdfExporter {

    private const val PAGE_WIDTH = 595 // A4 width in points (72 dpi)
    private const val PAGE_HEIGHT = 842 // A4 height in points
    private const val MARGIN = 32f
    private const val FOOTER_RESERVE = 36f

    fun buildPayloadFromActiveLedger(
        bookTitle: String,
        ownerName: String,
        entries: List<SaleEntry>,
        costPerGbToman: Long = 1_000L,
        adminProfitPercent: Int = 50,
        isPrivateAdminBook: Boolean = false
    ): PdfReportPayload {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)
        val rowDateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val rows = entries.mapIndexed { idx, entry ->
            val dateObj = entry.createdAt?.toDate() ?: Date()
            PdfReportRow(
                index = idx + 1,
                customerName = entry.customerName,
                dataGb = entry.dataGb,
                baseDataGb = entry.baseDataGb,
                isNewUser = entry.isNewUser,
                bonusGb = entry.bonusGb,
                presetName = entry.presetName.ifBlank { "Custom Plan" },
                priceToman = entry.priceToman,
                originalPriceToman = entry.originalPresetPriceToman,
                isPaid = entry.isPaid,
                note = entry.note,
                dateFormatted = rowDateFormat.format(dateObj)
            )
        }
        val totalSales = entries.sumOf { it.priceToman }
        val totalGb = entries.sumOf { it.dataGb }
        val calc = FinancialCalculator.calculate(
            grossSalesToman = totalSales,
            totalAllocatedGb = totalGb,
            costPerGbToman = costPerGbToman,
            adminSharePercent = adminProfitPercent
        )

        return PdfReportPayload(
            reportTitle = TomanFormatter.sanitizeOwnerDisplayName(bookTitle),
            subtitle = "Active cycle statement",
            ownerName = TomanFormatter.sanitizeOwnerDisplayName(ownerName),
            isArchivedSnapshot = false,
            isPrivateAdminBook = isPrivateAdminBook,
            generatedAtFormatted = dateFormat.format(Date()),
            totalSalesToman = calc.grossSalesToman,
            totalGb = calc.totalAllocatedGb,
            costPerGbToman = calc.costPerGbToman,
            adminProfitPercent = calc.adminSharePercent,
            infraCostToman = calc.infrastructureCostToman,
            netProfitToman = calc.netProfitToman,
            sellerProfitToman = calc.sellerProfitToman,
            adminProfitToman = calc.adminProfitToman,
            sellerPayableToAdminToman = calc.amountSellerPaysAdminToman,
            rows = rows
        )
    }

    fun buildPayloadFromArchive(
        archive: LedgerArchive,
        fallbackCostPerGbToman: Long = 1_000L,
        fallbackAdminProfitPercent: Int = 50,
        isPrivateAdminBook: Boolean = false
    ): PdfReportPayload {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)
        val rowDateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val parsedItems: List<ArchivedSaleItem> = archive.parsedItems()
        val rows = parsedItems.mapIndexed { idx, item ->
            val dateStr = if (item.createdEpochMillis > 0L) {
                rowDateFormat.format(Date(item.createdEpochMillis))
            } else {
                "Archived"
            }
            PdfReportRow(
                index = idx + 1,
                customerName = item.customerName,
                dataGb = item.dataGb,
                baseDataGb = item.baseDataGb,
                isNewUser = item.isNewUser,
                bonusGb = item.bonusGb,
                presetName = item.presetName.ifBlank { "Custom Plan" },
                priceToman = item.priceToman,
                originalPriceToman = item.originalPresetPriceToman,
                isPaid = item.isPaid,
                note = item.note,
                dateFormatted = dateStr
            )
        }
        val archiveDate = archive.createdAt?.toDate() ?: Date()
        val metrics = archive.computeMetrics(
            fallbackCostPerGb = fallbackCostPerGbToman,
            fallbackAdminPercent = fallbackAdminProfitPercent
        )

        return PdfReportPayload(
            reportTitle = if (archive.isSubmission || archive.isLegacyReport) {
                "Sales report"
            } else {
                archive.cycleTitle.ifBlank { "Archived cycle" }
            },
            subtitle = when {
                archive.isVoided -> "VOIDED archive • Closed ${dateFormat.format(archiveDate)}"
                archive.isSubmission || archive.isLegacyReport -> "Report sent to Admin • Uploaded ${dateFormat.format(archiveDate)}"
                else -> "Closed cycle • Closed ${dateFormat.format(archiveDate)}"
            },
            ownerName = TomanFormatter.sanitizeOwnerDisplayName(archive.ownerName),
            isArchivedSnapshot = true,
            isPrivateAdminBook = isPrivateAdminBook,
            generatedAtFormatted = dateFormat.format(Date()),
            totalSalesToman = metrics.totalSalesToman,
            totalGb = metrics.totalGb,
            costPerGbToman = metrics.costPerGbToman,
            adminProfitPercent = metrics.adminProfitPercent,
            infraCostToman = metrics.infraCostToman,
            netProfitToman = metrics.netProfitToman,
            sellerProfitToman = metrics.sellerProfitToman,
            adminProfitToman = metrics.adminProfitToman,
            sellerPayableToAdminToman = metrics.sellerPayableToAdminToman,
            rows = rows
        )
    }

    private data class MeasuredPdfRow(
        val row: PdfReportRow,
        val customerLines: List<String>,
        val gbLines: List<String>,
        val planLines: List<String>,
        val noteLines: List<String>,
        val priceLines: List<String>,
        val height: Float
    )

    fun wrapTextToWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return listOf("")
        if (paint.measureText(clean) <= maxWidth) return listOf(clean)

        val lines = mutableListOf<String>()
        val words = clean.split(Regex("\\s+"))
        var currentLine = StringBuilder()

        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(candidate) <= maxWidth) {
                currentLine.clear()
                currentLine.append(candidate)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine.clear()
                }
                if (paint.measureText(word) <= maxWidth) {
                    currentLine.append(word)
                } else {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        val nextChunk = chunk.toString() + ch
                        if (paint.measureText(nextChunk) <= maxWidth) {
                            chunk.append(ch)
                        } else {
                            if (chunk.isNotEmpty()) lines.add(chunk.toString())
                            chunk = StringBuilder().append(ch)
                        }
                    }
                    if (chunk.isNotEmpty()) {
                        currentLine.append(chunk)
                    }
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return if (lines.isEmpty()) listOf(clean) else lines
    }

    private fun measureRow(
        row: PdfReportRow,
        primaryPaint: Paint,
        secondaryPaint: Paint,
        gbPaint: Paint
    ): MeasuredPdfRow {
        val customerMaxWidth = 148f
        val gbMaxWidth = 84f
        val planMaxWidth = 120f
        val priceMaxWidth = 96f

        val customerLines = wrapTextToWidth(row.customerName, primaryPaint, customerMaxWidth)
        val gbLabel = if (row.isNewUser && row.bonusGb > 0.0) {
            "${TomanFormatter.formatGbNumberOnly(row.dataGb)} GB total (${TomanFormatter.formatGbNumberOnly(row.baseDataGb)} + ${TomanFormatter.formatGbNumberOnly(row.bonusGb)} bonus)"
        } else {
            TomanFormatter.formatGb(row.dataGb)
        }
        val gbLines = wrapTextToWidth(gbLabel, gbPaint, gbMaxWidth)

        val planHeader = if (row.isNewUser) "${row.presetName} [New User]" else row.presetName
        val planLines = wrapTextToWidth(planHeader, secondaryPaint, planMaxWidth)

        val subNote = when {
            row.originalPriceToman > row.priceToman && row.note.isNotBlank() -> {
                val diff = row.originalPriceToman - row.priceToman
                "-${TomanFormatter.format(diff)} off • ${row.note}"
            }
            row.originalPriceToman > row.priceToman -> {
                val diff = row.originalPriceToman - row.priceToman
                "-${TomanFormatter.format(diff)} discount"
            }
            row.note.isNotBlank() -> row.note
            row.isNewUser && row.bonusGb > 0.0 -> "Includes +${TomanFormatter.formatGb(row.bonusGb)} bonus"
            else -> "Standard rate"
        }
        val noteLines = wrapTextToWidth(subNote, secondaryPaint, planMaxWidth)
        val priceLines = wrapTextToWidth(TomanFormatter.format(row.priceToman), primaryPaint, priceMaxWidth)

        val col1Lines = customerLines.size + 1 // +1 for date line
        val col2Lines = gbLines.size
        val col3Lines = planLines.size + noteLines.size
        val col4Lines = priceLines.size

        val maxLines = max(max(col1Lines, col2Lines), max(col3Lines, col4Lines))
        val computedHeight = max(36f, 12f + maxLines * 12f)

        return MeasuredPdfRow(
            row = row,
            customerLines = customerLines,
            gbLines = gbLines,
            planLines = planLines,
            noteLines = noteLines,
            priceLines = priceLines,
            height = computedHeight
        )
    }

    suspend fun generatePdfFile(context: Context, payload: PdfReportPayload): Result<File> =
        withContext(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            try {
                val primaryRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#0F172A")
                    textSize = 9.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val secondaryRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#475569")
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                val gbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

                val measuredRows = payload.rows.map {
                    measureRow(it, primaryRowPaint, secondaryRowPaint, gbTextPaint)
                }

                // Paginate dynamically by available height on each page
                val firstPageTableTop = MARGIN + 104f + 12f + 62f + 10f + 68f + 14f + 32f
                val nextPageTableTop = MARGIN + 28f + 32f
                val maxTableBottom = PAGE_HEIGHT - FOOTER_RESERVE

                val pages = mutableListOf<List<MeasuredPdfRow>>()
                if (measuredRows.isEmpty()) {
                    pages.add(emptyList())
                } else {
                    var currentPageRows = mutableListOf<MeasuredPdfRow>()
                    var currentY = firstPageTableTop
                    for (mRow in measuredRows) {
                        if (currentY + mRow.height > maxTableBottom && currentPageRows.isNotEmpty()) {
                            pages.add(currentPageRows)
                            currentPageRows = mutableListOf()
                            currentY = nextPageTableTop
                        }
                        currentPageRows.add(mRow)
                        currentY += mRow.height + 3f
                    }
                    if (currentPageRows.isNotEmpty()) {
                        pages.add(currentPageRows)
                    }
                }

                val totalPages = pages.size
                pages.forEachIndexed { pageIndex, pageRows ->
                    val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    drawReportPage(
                        canvas = page.canvas,
                        payload = payload,
                        rows = pageRows,
                        pageNumber = pageIndex + 1,
                        totalPages = totalPages
                    )
                    pdfDocument.finishPage(page)
                }

                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val safeTitle = payload.reportTitle
                    .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                    .take(32)
                    .ifBlank { "NodeSplit" }
                val fileName = "${safeTitle}_${System.currentTimeMillis()}.pdf"
                val outFile = File(exportDir, fileName)
                FileOutputStream(outFile).use { out ->
                    pdfDocument.writeTo(out)
                }
                Result.success(outFile)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pdfDocument.close()
            }
        }

    private fun drawReportPage(
        canvas: Canvas,
        payload: PdfReportPayload,
        rows: List<MeasuredPdfRow>,
        pageNumber: Int,
        totalPages: Int
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

        var currentY = MARGIN

        if (pageNumber == 1) {
            // 1. Graphical Gradient Hero Banner (No email addresses!)
            val bannerHeight = 104f
            val bannerRect = RectF(MARGIN, currentY, PAGE_WIDTH - MARGIN, currentY + bannerHeight)
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bannerRect.left,
                    bannerRect.top,
                    bannerRect.right,
                    bannerRect.bottom,
                    intArrayOf(
                        Color.parseColor("#0B111E"),
                        Color.parseColor("#0F292E"),
                        Color.parseColor("#064E3B")
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(bannerRect, 14f, 14f, bannerPaint)

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (payload.isArchivedSnapshot) Color.parseColor("#06B6D4") else Color.parseColor("#10B981")
            }
            val badgeRect = RectF(bannerRect.left + 20f, bannerRect.top + 14f, bannerRect.left + 162f, bannerRect.top + 33f)
            canvas.drawRoundRect(badgeRect, 10f, 10f, badgePaint)

            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#042F21")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val badgeLabel = if (payload.isArchivedSnapshot) "ARCHIVED SNAPSHOT" else "NODESPLIT STATEMENT"
            canvas.drawText(badgeLabel, badgeRect.left + 12f, badgeRect.top + 13f, badgeTextPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 17f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleLines = wrapTextToWidth(payload.reportTitle, titlePaint, bannerRect.width() - 40f)
            canvas.drawText(titleLines.firstOrNull().orEmpty(), bannerRect.left + 20f, bannerRect.top + 56f, titlePaint)

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            canvas.drawText(
                "${payload.subtitle}  •  Account: ${payload.ownerName}",
                bannerRect.left + 20f,
                bannerRect.top + 76f,
                subPaint
            )
            canvas.drawText(
                "Exported: ${payload.generatedAtFormatted}",
                bannerRect.left + 20f,
                bannerRect.top + 92f,
                subPaint
            )

            currentY += bannerHeight + 12f

            // 2. Top KPI Cards Row (Gross Sales, Total Allocated GB, Total Sales Count)
            val cardSpacing = 10f
            val totalWidth = PAGE_WIDTH - (MARGIN * 2)
            val cardWidth = (totalWidth - (cardSpacing * 2)) / 3f
            val cardHeight = 62f

            drawKpiCard(
                canvas = canvas,
                left = MARGIN,
                top = currentY,
                width = cardWidth,
                height = cardHeight,
                label = "GROSS SALES",
                value = TomanFormatter.format(payload.totalSalesToman),
                subValue = "${payload.rows.size} sales recorded",
                accentColor = Color.parseColor("#059669"),
                bgColor = Color.parseColor("#ECFDF5")
            )

            drawKpiCard(
                canvas = canvas,
                left = MARGIN + (cardWidth + cardSpacing) * 1,
                top = currentY,
                width = cardWidth,
                height = cardHeight,
                label = "TOTAL ALLOCATED DATA",
                value = TomanFormatter.formatGb(payload.totalGb),
                subValue = "Rate: ${TomanFormatter.format(payload.costPerGbToman)}/GB",
                accentColor = Color.parseColor("#0891B2"),
                bgColor = Color.parseColor("#ECFEFF")
            )

            drawKpiCard(
                canvas = canvas,
                left = MARGIN + (cardWidth + cardSpacing) * 2,
                top = currentY,
                width = cardWidth,
                height = cardHeight,
                label = "PROFIT SPLIT",
                value = "Seller ${payload.sellerProfitPercent}% / Admin ${payload.adminProfitPercent}%",
                subValue = "Net Profit: ${TomanFormatter.format(payload.netProfitToman)}",
                accentColor = Color.parseColor("#0F172A"),
                bgColor = Color.WHITE
            )

            currentY += cardHeight + 10f

            // 3. 3-Part Financial Split & Settlement Row
            val splitCardWidth = (totalWidth - (cardSpacing * 3)) / 4f
            val splitCardHeight = 68f

            drawKpiCard(
                canvas = canvas,
                left = MARGIN,
                top = currentY,
                width = splitCardWidth,
                height = splitCardHeight,
                label = "INFRASTRUCTURE COST",
                value = TomanFormatter.format(payload.infraCostToman),
                subValue = "${TomanFormatter.formatGb(payload.totalGb)} × ${TomanFormatter.format(payload.costPerGbToman)}",
                accentColor = Color.parseColor("#D97706"),
                bgColor = Color.parseColor("#FFFBEB")
            )

            if (payload.isPrivateAdminBook) {
                // The Admin's own book: no transfer to oneself, just the business profit.
                drawKpiCard(
                    canvas = canvas,
                    left = MARGIN + (splitCardWidth + cardSpacing) * 1,
                    top = currentY,
                    width = splitCardWidth * 3 + cardSpacing * 2,
                    height = splitCardHeight,
                    label = "BUSINESS PROFIT",
                    value = TomanFormatter.format(payload.netProfitToman),
                    subValue = "Gross sales minus infrastructure cost, before other expenses",
                    accentColor = Color.parseColor("#047857"),
                    bgColor = Color.parseColor("#D1FAE5")
                )
            } else {
                drawKpiCard(
                    canvas = canvas,
                    left = MARGIN + (splitCardWidth + cardSpacing) * 1,
                    top = currentY,
                    width = splitCardWidth,
                    height = splitCardHeight,
                    label = "SELLER PROFIT (${payload.sellerProfitPercent}%)",
                    value = TomanFormatter.format(payload.sellerProfitToman),
                    subValue = "Seller share",
                    accentColor = Color.parseColor("#0284C7"),
                    bgColor = Color.parseColor("#F0F9FF")
                )

                drawKpiCard(
                    canvas = canvas,
                    left = MARGIN + (splitCardWidth + cardSpacing) * 2,
                    top = currentY,
                    width = splitCardWidth,
                    height = splitCardHeight,
                    label = "ADMIN PROFIT (${payload.adminProfitPercent}%)",
                    value = TomanFormatter.format(payload.adminProfitToman),
                    subValue = "Admin share",
                    accentColor = Color.parseColor("#4F46E5"),
                    bgColor = Color.parseColor("#EEF2FF")
                )

                drawKpiCard(
                    canvas = canvas,
                    left = MARGIN + (splitCardWidth + cardSpacing) * 3,
                    top = currentY,
                    width = splitCardWidth,
                    height = splitCardHeight,
                    label = "AMOUNT DUE TO ADMIN",
                    value = TomanFormatter.format(payload.sellerPayableToAdminToman),
                    subValue = "Infrastructure + admin profit",
                    accentColor = Color.parseColor("#047857"),
                    bgColor = Color.parseColor("#D1FAE5")
                )
            }

            currentY += splitCardHeight + 14f
        } else {
            val contPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(
                "${payload.reportTitle} — Continued (Page $pageNumber of $totalPages)",
                MARGIN,
                currentY + 14f,
                contPaint
            )
            currentY += 28f
        }

        // 4. Customer Sales Table Header (No Paid/Unpaid column per requirement)
        val tableLeft = MARGIN
        val tableRight = PAGE_WIDTH - MARGIN
        val headerHeight = 28f
        val headerRect = RectF(tableLeft, currentY, tableRight, currentY + headerHeight)
        val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E293B") }
        canvas.drawRoundRect(headerRect, 6f, 6f, headerBgPaint)

        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val colIndexX = tableLeft + 10f
        val colCustomerX = tableLeft + 36f
        val colVolumeX = tableLeft + 190f
        val colPlanX = tableLeft + 286f
        val colPriceX = tableLeft + 416f

        val textBaselineHeader = currentY + 18f
        canvas.drawText("#", colIndexX, textBaselineHeader, headerTextPaint)
        canvas.drawText("CUSTOMER NAME", colCustomerX, textBaselineHeader, headerTextPaint)
        canvas.drawText("ALLOCATED GB", colVolumeX, textBaselineHeader, headerTextPaint)
        canvas.drawText("PLAN / NOTE", colPlanX, textBaselineHeader, headerTextPaint)
        canvas.drawText("PRICE (TOMAN)", colPriceX, textBaselineHeader, headerTextPaint)

        currentY += headerHeight + 4f

        if (rows.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 11f
            }
            canvas.drawText("No sales recorded in this ledger cycle.", tableLeft + 16f, currentY + 28f, emptyPaint)
        } else {
            val primaryRowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val secondaryRowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 8.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            rows.forEachIndexed { idx, measured ->
                val row = measured.row
                val rowTop = currentY
                val rowBottom = rowTop + measured.height
                val rowRect = RectF(tableLeft, rowTop, tableRight, rowBottom)

                val rowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (idx % 2 == 0) Color.WHITE else Color.parseColor("#F1F5F9")
                }
                canvas.drawRoundRect(rowRect, 4f, 4f, rowBgPaint)

                // Index
                canvas.drawText(row.index.toString(), colIndexX, rowTop + 16f, secondaryRowText)

                // Customer Name (wrapped) + Date
                var custY = rowTop + 14f
                measured.customerLines.forEach { line ->
                    canvas.drawText(line, colCustomerX, custY, primaryRowText)
                    custY += 11.5f
                }
                canvas.drawText(row.dateFormatted, colCustomerX, custY, secondaryRowText)

                // Allocated GB (wrapped)
                val gbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (row.isNewUser) Color.parseColor("#166534") else Color.parseColor("#0369A1")
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                var gbY = rowTop + 14f
                measured.gbLines.forEach { line ->
                    canvas.drawText(line, colVolumeX, gbY, gbTextPaint)
                    gbY += 11.5f
                }

                // Plan & Note (wrapped)
                var planY = rowTop + 14f
                measured.planLines.forEach { line ->
                    canvas.drawText(line, colPlanX, planY, primaryRowText)
                    planY += 11.5f
                }
                measured.noteLines.forEach { line ->
                    canvas.drawText(line, colPlanX, planY, secondaryRowText)
                    planY += 11.5f
                }

                // Price in Toman (wrapped)
                var priceY = rowTop + 15f
                measured.priceLines.forEach { line ->
                    canvas.drawText(line, colPriceX, priceY, primaryRowText)
                    priceY += 11.5f
                }

                currentY += measured.height + 3f
            }
        }

        // 5. Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8.5f
        }
        val footerY = PAGE_HEIGHT - 16f
        canvas.drawText(
            "NodeSplit statement  •  All amounts in Toman",
            MARGIN,
            footerY,
            footerPaint
        )
        canvas.drawText(
            "Page $pageNumber of $totalPages",
            PAGE_WIDTH - MARGIN - 65f,
            footerY,
            footerPaint
        )
    }

    private fun drawKpiCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        label: String,
        value: String,
        subValue: String,
        accentColor: Int,
        bgColor: Int
    ) {
        val rect = RectF(left, top, left + width, top + height)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
        canvas.drawRoundRect(rect, 10f, 10f, borderPaint)

        val stripeRect = RectF(left + 10f, top + 7f, left + 34f, top + 10f)
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(stripeRect, 2f, 2f, stripePaint)

        val innerWidth = width - 16f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 7.4f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelLines = wrapTextToWidth(label, labelPaint, innerWidth)
        canvas.drawText(labelLines.firstOrNull().orEmpty(), left + 8f, top + 21f, labelPaint)

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // PDF-02: An amount is never cut. Shrink a little, then put the unit on a second line.
        while (valuePaint.measureText(value) > innerWidth && valuePaint.textSize > 8f) {
            valuePaint.textSize -= 0.5f
        }
        if (valuePaint.measureText(value) <= innerWidth) {
            canvas.drawText(value, left + 8f, top + 38f, valuePaint)
        } else {
            val number = value.substringBeforeLast(" ")
            val unit = value.substringAfterLast(" ", "")
            canvas.drawText(number, left + 8f, top + 33f, valuePaint)
            if (unit.isNotBlank() && unit != number) canvas.drawText(unit, left + 8f, top + 43f, valuePaint)
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 7.5f
        }
        val subLines = wrapTextToWidth(subValue, subPaint, innerWidth)
        canvas.drawText(subLines.firstOrNull().orEmpty(), left + 8f, top + 53f, subPaint)
    }

    fun sharePdfFile(context: Context, pdfFile: File, reportTitle: String): Result<Unit> {
        return try {
            if (!pdfFile.exists()) return Result.failure(IllegalStateException("The PDF file is no longer available."))
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, pdfFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NodeSplit report - $reportTitle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openPdfFile(context: Context, pdfFile: File): Result<Unit> {
        return try {
            if (!pdfFile.exists()) return Result.failure(IllegalStateException("The PDF file is no longer available."))
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, pdfFile)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun copyFileToUri(context: Context, sourceFile: File, targetUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!sourceFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("The PDF file is no longer available. Export it again."))
                }
                context.contentResolver.openOutputStream(targetUri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: return@withContext Result.failure(IllegalStateException("Unable to open destination file"))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
