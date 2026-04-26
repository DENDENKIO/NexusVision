package com.nexus.vision.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexus.vision.benchmark.PerformanceTracker

/**
 * Composable dashboard showing EASS + NCNN performance metrics.
 */
@Composable
fun BenchmarkDashboard(
    summary: PerformanceTracker.SessionSummary?,
    gpuName: String,
    isNcnnReady: Boolean,
    modifier: Modifier = Modifier
) {
    if (summary == null) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Performance Dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            // GPU Status
            MetricRow(
                label = "GPU",
                value = if (isNcnnReady) gpuName else "Not available",
                valueColor = if (isNcnnReady)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )

            MetricRow("Total Time", "${summary.totalTimeMs} ms")
            MetricRow("Tiles", "${summary.tileCount} total")
            MetricRow("Avg Tile", "${summary.avgTileTimeMs} ms")
            MetricRow("Min / Max", "${summary.minTileTimeMs} / ${summary.maxTileTimeMs} ms")

            // Route breakdown
            val routeA = summary.metadata["route_a"] ?: 0
            val routeB = summary.metadata["route_b"] ?: 0
            val routeC = summary.metadata["route_c"] ?: 0
            MetricRow("Routes", "A=$routeA  B=$routeB  C=$routeC")

            // Route C detail
            if (summary.ncnnTileCount > 0 || summary.cpuTileCount > 0) {
                HorizontalDivider()
                Text(
                    text = "Route C Detail",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                MetricRow("NCNN (GPU)", "${summary.ncnnTileCount} tiles")
                MetricRow("CPU Fallback", "${summary.cpuTileCount} tiles")
                if (summary.ncnnAvgMs > 0) {
                    MetricRow("NCNN Avg", "${summary.ncnnAvgMs} ms/tile")
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
