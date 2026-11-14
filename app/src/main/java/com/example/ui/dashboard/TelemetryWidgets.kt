package com.example.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TireRepair
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AvailabilityState
import com.example.data.model.BatteryStatusSummary
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.ObdPidDefinition
import com.example.data.model.ReadingStatus
import com.example.data.model.TelemetryReading
import com.example.data.model.TpmsWheelReading
import com.example.data.model.WidgetStyle
import com.example.data.obd.D16aaPidCatalog
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CoralCritical
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOptimal
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceGraphite
import com.example.ui.theme.TelemetryNumericStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun TelemetryWidgetCard(
  widget: DashboardWidgetConfig,
  definition: ObdPidDefinition?,
  reading: TelemetryReading?,
  batterySummary: BatteryStatusSummary,
  tpmsReadings: List<TpmsWheelReading>,
  isEditMode: Boolean,
  canMoveLeft: Boolean,
  canMoveRight: Boolean,
  onMoveLeft: () -> Unit,
  onMoveRight: () -> Unit,
  onConfigure: () -> Unit,
  onRemove: () -> Unit,
  onOpenPidInspector: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val style = widget.resolvedStyle()
  val status =
    when (style) {
      WidgetStyle.BATTERY_STATUS -> batterySummary.severity
      WidgetStyle.TPMS_QUAD ->
        tpmsReadings.maxByOrNull { it.status.ordinal }?.status ?: ReadingStatus.NORMAL
      else -> reading?.status ?: ReadingStatus.NORMAL
    }

  val accentColor =
    when (status) {
      ReadingStatus.CRITICAL -> CoralCritical
      ReadingStatus.WARNING -> AmberWarning
      ReadingStatus.NORMAL -> CyanPrimary
    }

  val borderColor =
    when {
      isEditMode -> CyanPrimary.copy(alpha = 0.55f)
      status == ReadingStatus.CRITICAL -> CoralCritical.copy(alpha = 0.7f)
      status == ReadingStatus.WARNING -> AmberWarning.copy(alpha = 0.65f)
      else -> BorderHairline
    }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("widget_card_${widget.pidKey}")
        .then(
          if (isEditMode) Modifier.clickable(onClick = onConfigure)
          else Modifier
        ),
    shape = MaterialTheme.shapes.medium,
    color = SurfaceGraphite,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = definition?.labelIt ?: widget.pidKey,
          style = MaterialTheme.typography.labelMedium,
          color = TextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (!isEditMode && definition != null && definition.command.isNotBlank()) {
          Text(
            text = definition.command,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
          )
        }
      }

      when (style) {
        WidgetStyle.BATTERY_STATUS -> {
          BatteryWidgetBody(
            summary = batterySummary,
            accentColor = accentColor,
          )
        }
        WidgetStyle.TPMS_QUAD -> {
          TpmsQuadWidgetBody(
            wheels = tpmsReadings,
            onOpenPidInspector = onOpenPidInspector,
          )
        }
        WidgetStyle.NUMERIC -> {
          NumericWidgetBody(
            definition = definition,
            reading = reading,
            accentColor = accentColor,
          )
        }
        WidgetStyle.BAR_GAUGE -> {
          BarGaugeWidgetBody(
            definition = definition,
            reading = reading,
            accentColor = accentColor,
          )
        }
        WidgetStyle.SPARKLINE -> {
          SparklineWidgetBody(
            definition = definition,
            reading = reading,
            accentColor = accentColor,
          )
        }
      }

      if (isEditMode) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = onMoveLeft,
              enabled = canMoveLeft,
              modifier = Modifier.minimumInteractiveComponentSize().size(36.dp),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_move_left),
                tint = if (canMoveLeft) TextSecondary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
              )
            }
            IconButton(
              onClick = onMoveRight,
              enabled = canMoveRight,
              modifier = Modifier.minimumInteractiveComponentSize().size(36.dp),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = stringResource(R.string.cd_move_right),
                tint = if (canMoveRight) TextSecondary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = onConfigure,
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .size(36.dp)
                  .testTag("edit_widget_${widget.id}"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_configure_widget),
                tint = CyanPrimary,
                modifier = Modifier.size(18.dp),
              )
            }
            IconButton(
              onClick = onRemove,
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .size(36.dp)
                  .testTag("remove_widget_${widget.id}"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_remove_widget),
                tint = CoralCritical,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NumericWidgetBody(
  definition: ObdPidDefinition?,
  reading: TelemetryReading?,
  accentColor: Color,
) {
  val valueText = formatTelemetryValue(reading, definition)
  val isUnsupported = reading?.availability == AvailabilityState.UNSUPPORTED
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = valueText,
        style = TelemetryNumericStyle.copy(fontSize = 30.sp, lineHeight = 34.sp),
        color = if (isUnsupported) TextMuted else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      if (!isUnsupported && definition != null) {
        Text(
          text = definition.unit,
          style = MaterialTheme.typography.labelMedium,
          color = accentColor,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
    }
    if (isUnsupported) {
      Text(
        text = stringResource(R.string.pid_not_supported_ecu),
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
      )
    }
  }
}

@Composable
private fun BarGaugeWidgetBody(
  definition: ObdPidDefinition?,
  reading: TelemetryReading?,
  accentColor: Color,
) {
  val minVal = definition?.minValue ?: 0.0
  val maxVal = (definition?.maxValue ?: 100.0).coerceAtLeast(minVal + 1.0)
  val rawFraction =
    reading?.value?.let { ((it - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f) } ?: 0f
  val animatedFraction by
    animateFloatAsState(
      targetValue = rawFraction,
      animationSpec = tween(durationMillis = 220),
      label = "bar_fraction",
    )

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    NumericWidgetBody(definition = definition, reading = reading, accentColor = accentColor)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(SurfaceElevated)
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(animatedFraction)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(accentColor)
      )
    }
  }
}

@Composable
private fun SparklineWidgetBody(
  definition: ObdPidDefinition?,
  reading: TelemetryReading?,
  accentColor: Color,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    NumericWidgetBody(definition = definition, reading = reading, accentColor = accentColor)
    val history = reading?.history.orEmpty()
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(44.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(SurfaceElevated.copy(alpha = 0.55f))
          .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
      if (history.size >= 2) {
        D16aaSparklineCanvas(
          values = history,
          minRange = definition?.minValue,
          maxRange = definition?.maxValue,
          lineColor = accentColor,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Text(
          text = stringResource(R.string.waiting_samples),
          style = MaterialTheme.typography.labelSmall,
          color = TextMuted,
          modifier = Modifier.align(Alignment.CenterStart),
        )
      }
    }
  }
}

@Composable
private fun BatteryWidgetBody(
  summary: BatteryStatusSummary,
  accentColor: Color,
) {
  val statusColor =
    when (summary.severity) {
      ReadingStatus.CRITICAL -> CoralCritical
      ReadingStatus.WARNING -> AmberWarning
      ReadingStatus.NORMAL ->
        if ((summary.voltage ?: 0.0) >= 13.4) EmeraldOptimal else accentColor
    }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text =
            summary.voltage?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "--.--",
          style = TelemetryNumericStyle.copy(fontSize = 28.sp, lineHeight = 32.sp),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "V",
          style = MaterialTheme.typography.labelMedium,
          color = statusColor,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
        Text(
          text = summary.stateLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = statusColor,
          fontWeight = FontWeight.Medium,
        )
      }
      Text(
        text = summary.sourceLabel,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
      )
    }
    Box(
      modifier =
        Modifier.size(44.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(statusColor.copy(alpha = 0.14f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector =
          if ((summary.voltage ?: 0.0) >= 13.4) Icons.Rounded.BatteryChargingFull
          else Icons.Rounded.BatteryStd,
        contentDescription = stringResource(R.string.cd_battery_status),
        tint = statusColor,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}

@Composable
private fun TpmsQuadWidgetBody(
  wheels: List<TpmsWheelReading>,
  onOpenPidInspector: () -> Unit,
) {
  val allNeedsConfig = wheels.all { it.availability == AvailabilityState.NEEDS_BCM_CONFIG }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      wheels.take(2).forEach { wheel ->
        TpmsWheelPill(wheel = wheel, modifier = Modifier.weight(1f))
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      wheels.drop(2).take(2).forEach { wheel ->
        TpmsWheelPill(wheel = wheel, modifier = Modifier.weight(1f))
      }
    }
    if (allNeedsConfig) {
      Surface(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenPidInspector),
        color = SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.TireRepair,
            contentDescription = null,
            tint = CyanPrimary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = stringResource(R.string.tpms_configure_bcm_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = CyanPrimary,
          )
        }
      }
    }
  }
}

@Composable
private fun TpmsWheelPill(wheel: TpmsWheelReading, modifier: Modifier = Modifier) {
  val color =
    when {
      wheel.availability == AvailabilityState.NEEDS_BCM_CONFIG -> TextMuted
      wheel.status == ReadingStatus.CRITICAL -> CoralCritical
      wheel.status == ReadingStatus.WARNING -> AmberWarning
      wheel.pressureBar != null -> EmeraldOptimal
      else -> TextSecondary
    }
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(10.dp))
        .background(SurfaceElevated)
        .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
        text = wheel.wheel.labelIt,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
      )
      Text(
        text =
          wheel.configuredHeader?.takeIf { it.isNotBlank() }?.let { "$it · ${wheel.configuredCommand}" }
            ?: wheel.wheel.code,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
      )
    }
    Text(
      text =
        wheel.pressureBar?.let { String.format(Locale.ROOT, "%.2f bar", it) }
          ?: if (wheel.availability == AvailabilityState.NEEDS_BCM_CONFIG) "BCM?" else "--",
      style = TelemetryNumericStyle.copy(fontSize = 15.sp),
      color = color,
    )
  }
}

@Composable
fun D16aaSparklineCanvas(
  values: List<Double>,
  minRange: Double?,
  maxRange: Double?,
  lineColor: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
    val dataMin = values.minOrNull() ?: 0.0
    val dataMax = values.maxOrNull() ?: 1.0
    val minY = minOf(dataMin, minRange ?: dataMin)
    val maxY = maxOf(dataMax, maxRange ?: dataMax)
    val span = (maxY - minY).takeIf { it > 0.001 } ?: 1.0

    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
    val points =
      values.mapIndexed { idx, v ->
        val x = idx * stepX
        val norm = ((v - minY) / span).toFloat().coerceIn(0f, 1f)
        val y = size.height - norm * (size.height - 4f) - 2f
        Offset(x, y)
      }

    val linePath = Path().apply {
      moveTo(points.first().x, points.first().y)
      for (i in 1 until points.size) {
        lineTo(points[i].x, points[i].y)
      }
    }

    val fillPath = Path().apply {
      addPath(linePath)
      lineTo(points.last().x, size.height)
      lineTo(points.first().x, size.height)
      close()
    }

    drawPath(
      path = fillPath,
      brush =
        Brush.verticalGradient(
          colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
          startY = 0f,
          endY = size.height,
        ),
    )

    drawPath(
      path = linePath,
      color = lineColor,
      style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    val last = points.last()
    drawCircle(color = lineColor, radius = 3.2.dp.toPx(), center = last)
  }
}

fun formatTelemetryValue(reading: TelemetryReading?, definition: ObdPidDefinition?): String {
  if (reading == null || reading.availability == AvailabilityState.WAITING) return "--"
  if (reading.availability == AvailabilityState.UNSUPPORTED) return "N/D"
  val v = reading.value ?: return "--"
  val decimals = definition?.decimals ?: 1
  return if (decimals == 0) {
    String.format(Locale.ROOT, "%.0f", v)
  } else {
    String.format(Locale.ROOT, "%.${decimals}f", v)
  }
}
