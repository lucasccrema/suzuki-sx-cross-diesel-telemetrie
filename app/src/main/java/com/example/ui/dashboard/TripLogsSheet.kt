package com.example.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ActiveTripUiState
import com.example.data.model.TripLogEntry
import com.example.data.model.TripTimelinePoint
import com.example.data.obd.TripCsvStorage
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
import com.example.ui.theme.VoidBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripLogsSheet(
  trips: List<TripLogEntry>,
  activeTrip: ActiveTripUiState,
  autoLogEnabled: Boolean,
  canManualStart: Boolean,
  onToggleAutoLog: (Boolean) -> Unit,
  onStartManual: () -> Unit,
  onStopTrip: () -> Unit,
  onRenameTrip: (String, String) -> Unit,
  onDeleteTrip: (TripLogEntry) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var expandedTripId by remember { mutableStateOf<String?>(null) }
  var renamingTrip by remember { mutableStateOf<TripLogEntry?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = VoidBlack,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .widthIn(max = 600.dp)
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.trip_logs_title),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = stringResource(R.string.trip_logs_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
          )
        }
        Icon(
          imageVector = Icons.Rounded.Route,
          contentDescription = null,
          tint = CyanPrimary,
          modifier = Modifier.size(28.dp),
        )
      }

      // Auto-log setting card
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SurfaceGraphite,
        border = BorderStroke(1.dp, BorderHairline),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.trip_auto_log_title),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.trip_auto_log_desc),
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondary,
            )
          }
          Switch(
            checked = autoLogEnabled,
            onCheckedChange = onToggleAutoLog,
            modifier = Modifier.testTag("auto_log_switch"),
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = VoidBlack,
                checkedTrackColor = CyanPrimary,
              ),
          )
        }
      }

      // Active recording controls or quick start button
      if (activeTrip.isRecording) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = EmeraldOptimal.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, EmeraldOptimal.copy(alpha = 0.5f)),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.FiberManualRecord,
                  contentDescription = null,
                  tint = CoralCritical,
                  modifier = Modifier.size(12.dp),
                )
                Text(
                  text = "Registrazione live · ${formatDuration(activeTrip.elapsedSec)}",
                  style = MaterialTheme.typography.titleSmall,
                  color = EmeraldOptimal,
                )
              }
              Text(
                text =
                  String.format(
                    Locale.ITALY,
                    "%.1f km · %.1f L/100km · coda %s · DPF %.0f°C",
                    activeTrip.distanceKm,
                    activeTrip.avgLitersPer100Km,
                    formatDuration(activeTrip.trafficSeconds),
                    activeTrip.maxDpfTempC,
                  ),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
              )
            }
            Button(
              onClick = onStopTrip,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = CoralCritical,
                  contentColor = VoidBlack,
                ),
              modifier = Modifier.testTag("sheet_stop_trip_button"),
            ) {
              Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.size(4.dp))
              Text(stringResource(R.string.trip_stop_save))
            }
          }
        }
      } else if (canManualStart) {
        Button(
          onClick = onStartManual,
          modifier = Modifier.fillMaxWidth().testTag("sheet_start_trip_button"),
          colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = VoidBlack),
        ) {
          Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.size(6.dp))
          Text("Avvia Log Tragitto adesso")
        }
      }

      if (trips.isEmpty()) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
          shape = MaterialTheme.shapes.large,
          color = SurfaceGraphite,
          border = BorderStroke(1.dp, BorderHairline),
        ) {
          Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = stringResource(R.string.trip_empty_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.trip_empty_desc),
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondary,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().height(420.dp),
          contentPadding = PaddingValues(bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(trips, key = { it.id }) { trip ->
            val isExpanded = expandedTripId == trip.id
            val hasFullLocalCsv =
              remember(trip.csvFileName) {
                TripCsvStorage.localCsvExists(context, trip.csvFileName)
              }
            TripLogCard(
              trip = trip,
              isExpanded = isExpanded,
              hasFullLocalCsv = hasFullLocalCsv,
              onToggleExpand = {
                expandedTripId = if (isExpanded) null else trip.id
              },
              onShareCsv = {
                runCatching {
                  val uri = TripCsvStorage.resolveShareableCsvUri(context, trip)
                  val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                      type = "text/csv"
                      putExtra(Intent.EXTRA_STREAM, uri)
                      putExtra(Intent.EXTRA_SUBJECT, "Telemetria S-Cross: ${trip.title}")
                      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                  context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.trip_export_csv))
                  )
                }
              },
              onRename = { renamingTrip = trip },
              onDelete = { onDeleteTrip(trip) },
            )
          }
        }
      }
    }
  }

  renamingTrip?.let { target ->
    var draftTitle by remember(target.id) { mutableStateOf(target.title) }
    AlertDialog(
      onDismissRequest = { renamingTrip = null },
      containerColor = SurfaceElevated,
      title = { Text(stringResource(R.string.trip_rename_title)) },
      text = {
        OutlinedTextField(
          value = draftTitle,
          onValueChange = { if (it.length <= 60) draftTitle = it },
          label = { Text(stringResource(R.string.trip_rename_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("rename_trip_input"),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (draftTitle.isNotBlank()) {
              onRenameTrip(target.id, draftTitle)
              renamingTrip = null
            }
          },
          modifier = Modifier.testTag("confirm_rename_trip_button"),
        ) {
          Text("Salva", color = CyanPrimary)
        }
      },
      dismissButton = {
        TextButton(onClick = { renamingTrip = null }) { Text("Annulla") }
      },
    )
  }
}

@Composable
private fun TripLogCard(
  trip: TripLogEntry,
  isExpanded: Boolean,
  hasFullLocalCsv: Boolean,
  onToggleExpand: () -> Unit,
  onShareCsv: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateStr =
    remember(trip.startedAtMillis) {
      SimpleDateFormat("EEE dd/MM/yyyy · HH:mm", Locale.ITALY).format(Date(trip.startedAtMillis))
    }
  val isLive = trip.status == "RECORDING"

  Surface(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onToggleExpand)
        .testTag("trip_card_${trip.id}"),
    shape = MaterialTheme.shapes.medium,
    color = SurfaceGraphite,
    border =
      BorderStroke(
        1.dp,
        if (isLive) EmeraldOptimal.copy(alpha = 0.6f)
        else if (isExpanded) CyanPrimary.copy(alpha = 0.6f)
        else BorderHairline,
      ),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = trip.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (trip.dpfRegenDetected) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = CoralCritical.copy(alpha = 0.18f),
              ) {
                Text(
                  text = "DPF REGEN",
                  style = MaterialTheme.typography.labelSmall,
                  color = CoralCritical,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
            if (trip.demoMode) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = AmberWarning.copy(alpha = 0.18f),
              ) {
                Text(
                  text = "DEMO",
                  style = MaterialTheme.typography.labelSmall,
                  color = AmberWarning,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            } else if (isLive) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = EmeraldOptimal.copy(alpha = 0.18f),
              ) {
                Text(
                  text = "IN CORSO",
                  style = MaterialTheme.typography.labelSmall,
                  color = EmeraldOptimal,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }
          Text(
            text = "$dateStr · ${formatDuration(trip.durationSec)} · ${trip.sampleCount} campioni",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onRename,
            modifier = Modifier.testTag("rename_trip_${trip.id}"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Edit,
              contentDescription = "Rinomina tragitto",
              tint = TextSecondary,
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("delete_trip_${trip.id}"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "Elimina tragitto",
              tint = CoralCritical,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      // Key metrics row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        TripStatBadge(
          label = "Distanza",
          value = String.format(Locale.ITALY, "%.1f km", trip.distanceKm),
          accent = CyanPrimary,
        )
        TripStatBadge(
          label = "Consumo est.",
          value =
            when {
              trip.avgLitersPer100Km > 0.0 ->
                String.format(Locale.ITALY, "%.1f L/100", trip.avgLitersPer100Km)
              trip.avgLitersPerHour > 0.0 ->
                String.format(Locale.ITALY, "%.1f L/h", trip.avgLitersPerHour)
              else -> "N/D"
            },
          accent =
            if (trip.avgLitersPer100Km >= 7.5) AmberWarning
            else MaterialTheme.colorScheme.onSurface,
        )
        TripStatBadge(
          label = "Coda (<10 km/h)",
          value = formatDuration(trip.trafficSeconds),
          accent =
            if (trip.trafficSeconds >= 180L) AmberWarning
            else MaterialTheme.colorScheme.onSurface,
        )
        TripStatBadge(
          label = "Max DPF",
          value = String.format(Locale.ITALY, "%.0f°C", trip.maxDpfTempC),
          accent = if (trip.dpfRegenDetected) CoralCritical else MaterialTheme.colorScheme.onSurface,
        )
      }

      if (isExpanded) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          color = VoidBlack,
          border = BorderStroke(1.dp, BorderHairline),
        ) {
          Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text =
                String.format(
                  Locale.ITALY,
                  "Gasolio ~%.2f L (%.1f L/h) · Vel. %.0f/%.0f km/h · Acqua %.0f°C · Batt. min %.2fV",
                  trip.estimatedFuelLiters,
                  trip.avgLitersPerHour,
                  trip.maxSpeedKmh,
                  trip.avgSpeedKmh,
                  trip.maxCoolantC,
                  trip.minBatteryV,
                ),
              style = MaterialTheme.typography.labelSmall,
              color = TextSecondary,
            )
            if (trip.dpfRegenDetected) {
              Text(
                text =
                  String.format(
                    Locale.ITALY,
                    "Rigenerazione attiva DPF rilevata (%s sopra 500°C): spiega eventuale consumo extra.",
                    formatDuration(trip.dpfRegenSeconds),
                  ),
                style = MaterialTheme.typography.labelSmall,
                color = CoralCritical,
              )
            }

            if (trip.timelinePoints.size >= 2) {
              TripTimelineChart(
                points = trip.timelinePoints,
                modifier = Modifier.fillMaxWidth().height(128.dp),
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                ChartLegendItem("Vel.", CyanPrimary)
                ChartLegendItem("L/h", CoralCritical)
                ChartLegendItem("DPF °C", AmberWarning)
                ChartLegendItem("Acqua", EmeraldOptimal)
              }
            } else {
              Box(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = "Andamento disponibile dal prossimo checkpoint (min. 2 punti).",
                  style = MaterialTheme.typography.bodySmall,
                  color = TextMuted,
                )
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text =
                  if (hasFullLocalCsv) "CSV completo su telefono"
                  else "CSV generato da riepilogo Cloud",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
              )
              OutlinedButton(
                onClick = onShareCsv,
                modifier = Modifier.testTag("share_csv_${trip.id}"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Share,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.trip_export_csv))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TripStatBadge(
  label: String,
  value: String,
  accent: Color,
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = TextMuted,
    )
    Text(
      text = value,
      style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
      color = accent,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun ChartLegendItem(label: String, color: Color) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Canvas(modifier = Modifier.size(8.dp)) {
      drawCircle(color = color)
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = TextSecondary,
    )
  }
}

@Composable
private fun TripTimelineChart(
  points: List<TripTimelinePoint>,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    if (points.size < 2 || w <= 0f || h <= 0f) return@Canvas

    val padV = 8.dp.toPx()
    val usableH = (h - padV * 2f).coerceAtLeast(10f)

    // Subtle horizontal grid lines
    listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
      val y = padV + usableH * frac
      drawLine(
        color = BorderHairline,
        start = Offset(0f, y),
        end = Offset(w, y),
        strokeWidth = 1f,
      )
    }

    fun drawTrace(values: List<Double>, minRange: Double, maxRange: Double, color: Color) {
      val path = Path()
      val span = (maxRange - minRange).coerceAtLeast(1.0)
      values.forEachIndexed { idx, v ->
        val x = w * idx.toFloat() / values.lastIndex.toFloat()
        val norm = ((v - minRange) / span).toFloat().coerceIn(0f, 1f)
        val y = padV + usableH * (1f - norm)
        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
      }
      drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }

    val maxSpd = (points.maxOfOrNull { it.speedKmh } ?: 100.0).coerceAtLeast(60.0)
    val maxDpf = (points.maxOfOrNull { it.dpfTempC } ?: 450.0).coerceAtLeast(350.0)
    val maxFuel = (points.maxOfOrNull { it.fuelLph } ?: 12.0).coerceAtLeast(8.0)
    drawTrace(points.map { it.coolantC }, 20.0, 115.0, EmeraldOptimal)
    drawTrace(points.map { it.dpfTempC }, 100.0, maxDpf, AmberWarning)
    drawTrace(points.map { it.fuelLph }, 0.0, maxFuel, CoralCritical)
    drawTrace(points.map { it.speedKmh }, 0.0, maxSpd, CyanPrimary)
  }
}

internal fun formatDuration(totalSec: Long): String {
  val clamped = totalSec.coerceAtLeast(0L)
  val hours = clamped / 3600L
  val mins = (clamped % 3600L) / 60L
  val secs = clamped % 60L
  return if (hours > 0L) {
    String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs)
  } else {
    String.format(Locale.ROOT, "%02d:%02d", mins, secs)
  }
}
