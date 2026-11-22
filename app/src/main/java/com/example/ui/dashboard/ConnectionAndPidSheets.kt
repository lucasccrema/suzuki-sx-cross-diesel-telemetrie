package com.example.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BcmScanEntry
import com.example.data.model.BcmScanState
import com.example.data.model.BluetoothDeviceItem
import com.example.data.model.CustomPidConfig
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.ElmConnectionState
import com.example.data.model.ElmLogEntry
import com.example.data.model.ObdPidDefinition
import com.example.data.model.PidProbeResult
import com.example.data.model.SupportedPidSnapshot
import com.example.data.model.WheelPosition
import com.example.data.model.WidgetStyle
import com.example.data.obd.BluetoothSppClient
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
import com.example.ui.theme.VoidBlack
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
  bluetoothClient: BluetoothSppClient,
  connectionState: ElmConnectionState,
  bondedDevices: List<BluetoothDeviceItem>,
  discoveredDevices: List<BluetoothDeviceItem>,
  isScanning: Boolean,
  elmLogs: List<ElmLogEntry>,
  onRefreshBonded: () -> Unit,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnectDevice: (BluetoothDeviceItem) -> Unit,
  onStartDemo: () -> Unit,
  onDisconnect: () -> Unit,
  onClearLogs: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var hasConnectPerm by remember { mutableStateOf(bluetoothClient.hasConnectPermission()) }
  var hasScanPerm by remember { mutableStateOf(bluetoothClient.hasScanPermission()) }
  var showLogs by remember { mutableStateOf(false) }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      hasConnectPerm = bluetoothClient.hasConnectPermission()
      hasScanPerm = bluetoothClient.hasScanPermission()
      if (hasConnectPerm) onRefreshBonded()
    }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SurfaceGraphite,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 20.dp)
          .padding(bottom = 28.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = stringResource(R.string.connection_sheet_title),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = stringResource(R.string.connection_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }
        IconButton(onClick = { showLogs = !showLogs }) {
          Icon(
            imageVector = Icons.Rounded.Terminal,
            contentDescription = stringResource(R.string.cd_toggle_logs),
            tint = if (showLogs) CyanPrimary else TextSecondary,
          )
        }
      }

      if (
        connectionState is ElmConnectionState.Connected ||
          connectionState is ElmConnectionState.DemoMode
      ) {
        OutlinedButton(
          onClick = onDisconnect,
          modifier = Modifier.fillMaxWidth().testTag("disconnect_elm_button"),
          border = BorderStroke(1.dp, CoralCritical.copy(alpha = 0.6f)),
        ) {
          Text(stringResource(R.string.disconnect_current), color = CoralCritical)
        }
      }

      // Demo D16AA simulator option
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SurfaceElevated,
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.45f)),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.demo_mode_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.demo_mode_desc),
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondary,
            )
          }
          Button(
            onClick = {
              onStartDemo()
              onDismiss()
            },
            modifier = Modifier.testTag("start_demo_button"),
            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = VoidBlack),
          ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("Demo")
          }
        }
      }

      if (!hasConnectPerm || !hasScanPerm) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = SurfaceElevated,
          border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = stringResource(R.string.bt_permission_needed_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.bt_permission_needed_desc),
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondary,
            )
            Button(
              onClick = { permissionLauncher.launch(bluetoothClient.requiredRuntimePermissions()) },
              modifier = Modifier.fillMaxWidth().testTag("grant_bt_permission_button"),
            ) {
              Text(stringResource(R.string.grant_bt_permissions))
            }
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.paired_devices_title),
            style = MaterialTheme.typography.titleMedium,
          )
          Row {
            IconButton(onClick = onRefreshBonded) {
              Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_refresh))
            }
            TextButton(
              onClick = { if (isScanning) onStopScan() else onStartScan() },
              modifier = Modifier.testTag("scan_bt_button"),
            ) {
              if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
                Text("Stop")
              } else {
                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Cerca")
              }
            }
          }
        }

        val allDevices = (bondedDevices + discoveredDevices).distinctBy { it.address }
        if (allDevices.isEmpty()) {
          Text(
            text = stringResource(R.string.no_bt_devices_found),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
          )
        } else {
          allDevices.forEach { device ->
            Surface(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(MaterialTheme.shapes.small)
                  .clickable {
                    onConnectDevice(device)
                    onDismiss()
                  },
              color = SurfaceElevated,
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, BorderHairline),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = CyanPrimary,
                  )
                  Column {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                      text = device.address,
                      style = MaterialTheme.typography.labelSmall,
                      color = TextMuted,
                    )
                  }
                }
                Text(
                  text = if (device.isBonded) "Associato" else "Trovato",
                  style = MaterialTheme.typography.labelMedium,
                  color = if (device.isBonded) EmeraldOptimal else CyanPrimary,
                )
              }
            }
          }
        }
      }

      if (showLogs) {
        HorizontalDivider(color = BorderHairline)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Log AT / OBD-II", style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = onClearLogs) { Text("Pulisci") }
        }
        Surface(
          modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
          shape = MaterialTheme.shapes.small,
          color = VoidBlack,
          border = BorderStroke(1.dp, BorderHairline),
        ) {
          if (elmLogs.isEmpty()) {
            Box(modifier = Modifier.padding(14.dp)) {
              Text(
                text = "Nessun comando scambiato finora.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
              )
            }
          } else {
            LazyColumn(
              contentPadding = PaddingValues(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              items(elmLogs.asReversed()) { entry ->
                Text(
                  text = "[${entry.direction}] ${entry.message}",
                  style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                  color =
                    when (entry.direction) {
                      "ERR" -> CoralCritical
                      "TX" -> CyanPrimary
                      "DEMO" -> AmberWarning
                      else -> TextSecondary
                    },
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCatalogSheet(
  customPids: List<CustomPidConfig>,
  supportedPids: SupportedPidSnapshot,
  onAddWidget: (pidKey: String, style: WidgetStyle, span: Long) -> Unit,
  onOpenPidInspector: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val definitions = remember(customPids) { D16aaPidCatalog.allDefinitions(customPids) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SurfaceGraphite,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.add_widget_title),
          style = MaterialTheme.typography.titleLarge,
        )
        TextButton(onClick = {
          onDismiss()
          onOpenPidInspector()
        }) {
          Text("PID Custom / BCM")
        }
      }

      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(definitions, key = { it.key }) { def ->
          val supportLabel =
            when {
              def.key == D16aaPidCatalog.PID_BATTERY_SMART || def.command == "ATRV" ->
                "Sempre disponibile" to EmeraldOptimal
              def.key == D16aaPidCatalog.PID_TPMS_QUAD ->
                "Richiede BCM/Custom" to AmberWarning
              def.isCustom ->
                "Custom (${def.canHeader.ifBlank { "7DF" }})" to CyanPrimary
              !supportedPids.discoveryCompleted ->
                "Mode 01 (${def.command})" to TextSecondary
              def.command in supportedPids.supportedCommands ->
                "Supportato ECU" to EmeraldOptimal
              else ->
                "Non rilevato su ECU" to TextMuted
            }

          Surface(
            modifier =
              Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable {
                  val defaultStyle =
                    when (def.key) {
                      D16aaPidCatalog.PID_BATTERY_SMART -> WidgetStyle.BATTERY_STATUS
                      D16aaPidCatalog.PID_TPMS_QUAD -> WidgetStyle.TPMS_QUAD
                      else -> WidgetStyle.NUMERIC
                    }
                  val defaultSpan =
                    if (
                      defaultStyle == WidgetStyle.BATTERY_STATUS ||
                        defaultStyle == WidgetStyle.TPMS_QUAD
                    ) 2L else 1L
                  onAddWidget(def.key, defaultStyle, defaultSpan)
                  onDismiss()
                },
            color = SurfaceElevated,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, BorderHairline),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(def.labelIt, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(def.category, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                  Text("·", color = TextMuted)
                  Text(supportLabel.first, style = MaterialTheme.typography.bodyMedium, color = supportLabel.second)
                }
              }
              Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.cd_add_widget),
                tint = CyanPrimary,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun WidgetConfigDialog(
  widget: DashboardWidgetConfig,
  definition: ObdPidDefinition?,
  onSave: (WidgetStyle, Long, Double?, Double?) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedStyle by remember(widget.id) { mutableStateOf(widget.resolvedStyle()) }
  var selectedSpan by remember(widget.id) { mutableStateOf(widget.spanColumns) }
  var warningText by remember(widget.id) {
    mutableStateOf(widget.warningThreshold?.toString() ?: "")
  }
  var criticalText by remember(widget.id) {
    mutableStateOf(widget.criticalThreshold?.toString() ?: "")
  }

  val availableStyles =
    remember(widget.pidKey) {
      when (widget.pidKey) {
        D16aaPidCatalog.PID_BATTERY_SMART -> listOf(WidgetStyle.BATTERY_STATUS, WidgetStyle.SPARKLINE)
        D16aaPidCatalog.PID_TPMS_QUAD -> listOf(WidgetStyle.TPMS_QUAD)
        else -> listOf(WidgetStyle.NUMERIC, WidgetStyle.BAR_GAUGE, WidgetStyle.SPARKLINE)
      }
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = SurfaceGraphite,
    title = { Text(definition?.labelIt ?: widget.pidKey) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Stile visualizzazione", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          availableStyles.forEach { st ->
            FilterChip(
              selected = selectedStyle == st,
              onClick = { selectedStyle = st },
              label = { Text(st.labelIt) },
            )
          }
        }

        Text("Larghezza riquadro", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = selectedSpan == 1L,
            onClick = { selectedSpan = 1L },
            label = { Text("1 Colonna (1x1)") },
          )
          FilterChip(
            selected = selectedSpan == 2L,
            onClick = { selectedSpan = 2L },
            label = { Text("Esteso (2x1)") },
          )
        }

        if (
          widget.pidKey != D16aaPidCatalog.PID_TPMS_QUAD &&
            widget.pidKey != D16aaPidCatalog.PID_BATTERY_SMART
        ) {
          OutlinedTextField(
            value = warningText,
            onValueChange = { warningText = it },
            label = { Text("Soglia attenzione (${definition?.unit.orEmpty()})") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = criticalText,
            onValueChange = { criticalText = it },
            label = { Text("Soglia allarme (${definition?.unit.orEmpty()})") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            selectedStyle,
            selectedSpan,
            warningText.toDoubleOrNull(),
            criticalText.toDoubleOrNull(),
          )
          onDismiss()
        }
      ) {
        Text("Salva")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Annulla") }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PidInspectorSheet(
  supportedPids: SupportedPidSnapshot,
  customPids: List<CustomPidConfig>,
  probeResult: PidProbeResult?,
  isProbing: Boolean,
  bcmScanState: BcmScanState,
  onRescanPids: () -> Unit,
  onStartBcmScan: () -> Unit,
  onStartTpmsMenuSniff: () -> Unit,
  onApplyTpmsCandidate: (BcmScanEntry) -> Unit,
  onCopyBcmReport: () -> Unit,
  onProbeCommand: (canHeader: String, command: String, formula: String) -> Unit,
  onSaveCustomPid: (
    label: String,
    command: String,
    canHeader: String,
    unit: String,
    formula: String,
    minValue: Double,
    maxValue: Double,
    targetWheel: String,
    addToDashboard: Boolean,
  ) -> Unit,
  onDeleteCustomPid: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var label by remember { mutableStateOf("Sensore Custom D16AA") }
  var canHeader by remember { mutableStateOf("7E0") }
  var command by remember { mutableStateOf("017C") }
  var unit by remember { mutableStateOf("°C") }
  var formula by remember { mutableStateOf("(B*256+C)/10-40") }
  var targetWheel by remember { mutableStateOf("") }
  var addToDashboard by remember { mutableStateOf(true) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SurfaceGraphite,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 20.dp)
          .padding(bottom = 28.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Ispettore PID & Multi-Header CAN", style = MaterialTheme.typography.titleLarge)
          Text(
            text =
              if (supportedPids.discoveryCompleted) {
                "ECU: ${supportedPids.ecuName.ifBlank { "S-Cross D16AA" }} · ${supportedPids.supportedCommands.size} PID attivi"
              } else {
                "Collegati all'ELM327 per rilevare automaticamente i PID"
              },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }
        TextButton(onClick = onRescanPids) { Text("Scansiona") }
      }

      if (supportedPids.supportedCommands.isNotEmpty()) {
        Text(
          text = "Bitmask Mode 01 rilevati: ${supportedPids.supportedCommands.sorted().joinToString(" ")}",
          style = MaterialTheme.typography.labelSmall,
          color = EmeraldOptimal,
        )
      }

      // Scansione automatica BCM / TPMS / DPF
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SurfaceElevated,
        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = stringResource(R.string.bcm_scan_card_title),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = stringResource(R.string.bcm_scan_card_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )

          Button(
            onClick = onStartBcmScan,
            enabled = !bcmScanState.isScanning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan_bcm_tpms_button"),
          ) {
            if (bcmScanState.isScanning) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = VoidBlack,
              )
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.bcm_scan_running))
            } else {
              Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.bcm_scan_button))
            }
          }

          OutlinedButton(
            onClick = onStartTpmsMenuSniff,
            enabled = !bcmScanState.isScanning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sniff_tpms_menu_button"),
            border = BorderStroke(1.dp, EmeraldOptimal.copy(alpha = 0.65f)),
          ) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp), tint = EmeraldOptimal)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.tpms_menu_sniff_button), color = EmeraldOptimal)
          }

          Text(
            text = stringResource(R.string.tpms_menu_sniff_hint),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
          )

          if (bcmScanState.isScanning) {
            LinearProgressIndicator(
              progress = { bcmScanState.progressFraction.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
              color = CyanPrimary,
              trackColor = VoidBlack,
            )
            Text(
              text = "${bcmScanState.completedProbes}/${bcmScanState.totalProbes} · ${bcmScanState.currentStepLabel}",
              style = MaterialTheme.typography.labelSmall,
              color = CyanPrimary,
            )
          }

          bcmScanState.errorMessage?.let { err ->
            Text(
              text = err,
              style = MaterialTheme.typography.labelSmall,
              color = AmberWarning,
            )
          }

          if (bcmScanState.hasRun && !bcmScanState.isScanning) {
            if (bcmScanState.foundEntries.isEmpty() && bcmScanState.errorMessage == null) {
              Text(
                text = stringResource(R.string.bcm_scan_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
              )
            } else if (bcmScanState.foundEntries.isNotEmpty()) {
              Text(
                text = "Risposte rilevate (${bcmScanState.foundEntries.size}):",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldOptimal,
              )
              bcmScanState.foundEntries.forEachIndexed { idx, entry ->
                Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = MaterialTheme.shapes.small,
                  color = VoidBlack,
                  border =
                    BorderStroke(
                      1.dp,
                      if (entry.tpmsCandidate != null) EmeraldOptimal.copy(alpha = 0.6f)
                      else BorderHairline,
                    ),
                ) {
                  Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(
                      text = "${entry.categoryHint} · ${entry.fullHeaderSpec} -> ${entry.command}",
                      style = MaterialTheme.typography.labelMedium,
                      color = CyanPrimary,
                    )
                    Text(
                      text =
                        "Byte: ${
                          entry.parsedBytes.joinToString(" ") {
                            String.format(Locale.ROOT, "%02X", it)
                          }
                        }",
                      style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                      color = TextSecondary,
                    )
                    entry.tpmsCandidate?.let { cand ->
                      val bars =
                        cand.samplePressuresBar.joinToString(" / ") {
                          String.format(Locale.ROOT, "%.2f", it)
                        }
                      Text(
                        text = "Candidato TPMS (${cand.scaleLabel}): $bars bar",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldOptimal,
                      )
                      if (entry.command != "PASSIVE") {
                        Button(
                          onClick = { onApplyTpmsCandidate(entry) },
                          modifier =
                            Modifier.fillMaxWidth()
                              .heightIn(min = 48.dp)
                              .testTag("apply_tpms_candidate_$idx"),
                          colors =
                            ButtonDefaults.buttonColors(
                              containerColor = EmeraldOptimal,
                              contentColor = VoidBlack,
                            ),
                        ) {
                          Text(stringResource(R.string.bcm_apply_tpms_4_wheels))
                        }
                      } else {
                        Text(
                          text = "Frame CAN passivo rilevato: copia il report per configurare l'ascolto continuo.",
                          style = MaterialTheme.typography.labelSmall,
                          color = CyanPrimary,
                        )
                      }
                    }
                    if (entry.command != "PASSIVE") {
                      TextButton(
                        onClick = {
                          canHeader = entry.fullHeaderSpec
                          command = entry.command
                          if (entry.tpmsCandidate != null) {
                            formula = entry.tpmsCandidate.formulaPerWheel.firstOrNull() ?: "A*0.02"
                            unit = "bar"
                            label = "TPMS (${entry.fullHeaderSpec})"
                          } else {
                            formula = "A"
                            label = "${entry.categoryHint} ${entry.command}".take(36)
                          }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("use_scan_entry_$idx"),
                      ) {
                        Text(stringResource(R.string.bcm_use_in_form))
                      }
                    }
                  }
                }
              }
            }
          }

          if (bcmScanState.hasRun || supportedPids.discoveryCompleted) {
            OutlinedButton(
              onClick = onCopyBcmReport,
              enabled = !bcmScanState.isScanning,
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("copy_bcm_report_button"),
            ) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.bcm_copy_report))
            }
          }
        }
      }

      HorizontalDivider(color = BorderHairline)
      Text("Test & Creazione PID (Motore / DPF / BCM TPMS)", style = MaterialTheme.typography.titleMedium)

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        listOf("7DF", "7E0", "751", "751:7A0", "7A0").forEach { preset ->
          FilterChip(
            selected = canHeader.equals(preset, ignoreCase = true),
            onClick = { canHeader = preset },
            label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = canHeader,
          onValueChange = { canHeader = it.uppercase(Locale.ROOT).take(8) },
          label = { Text("Header TX[:RX]") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = command,
          onValueChange = { command = it.uppercase(Locale.ROOT).take(12) },
          label = { Text("Comando Hex") },
          singleLine = true,
          modifier = Modifier.weight(1.3f),
        )
      }

      OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Nome sensore") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = formula,
          onValueChange = { formula = it },
          label = { Text("Formula (A..H)") },
          singleLine = true,
          modifier = Modifier.weight(1.6f),
        )
        OutlinedTextField(
          value = unit,
          onValueChange = { unit = it },
          label = { Text("Unità") },
          singleLine = true,
          modifier = Modifier.weight(0.8f),
        )
      }

      Text("Associa a ruota TPMS (opzionale)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
          selected = targetWheel.isEmpty(),
          onClick = { targetWheel = "" },
          label = { Text("Generico") },
        )
        WheelPosition.entries.forEach { w ->
          FilterChip(
            selected = targetWheel == w.code,
            onClick = {
              targetWheel = w.code
              unit = "bar"
              label = "TPMS ${w.labelIt}"
            },
            label = { Text(w.code) },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Aggiungi subito alla dashboard attiva", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = addToDashboard, onCheckedChange = { addToDashboard = it })
      }

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
          onClick = { onProbeCommand(canHeader, command, formula) },
          enabled = !isProbing,
          modifier = Modifier.weight(1f).testTag("probe_pid_button"),
        ) {
          if (isProbing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Text("Testa Comando")
          }
        }
        Button(
          onClick = {
            onSaveCustomPid(
              label,
              command,
              canHeader,
              unit,
              formula,
              0.0,
              if (targetWheel.isNotEmpty()) 4.0 else 1000.0,
              targetWheel,
              addToDashboard,
            )
          },
          modifier = Modifier.weight(1f).testTag("save_custom_pid_button"),
        ) {
          Text("Salva su Cloud")
        }
      }

      probeResult?.let { res ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.small,
          color = VoidBlack,
          border = BorderStroke(1.dp, BorderHairline),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "Risposta ${res.canHeader.ifBlank { "7DF" }} -> ${res.command}",
              style = MaterialTheme.typography.labelMedium,
              color = CyanPrimary,
            )
            Text(
              text = res.errorMessage ?: "Raw: ${res.rawResponse.ifBlank { "Vuoto" }}",
              style = MaterialTheme.typography.labelSmall,
              color = if (res.errorMessage != null) AmberWarning else TextSecondary,
            )
            if (res.parsedBytes.isNotEmpty()) {
              Text(
                text = "Byte: ${res.parsedBytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }} | Valore: ${res.decodedValue ?: "N/D"}",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldOptimal,
              )
            }
          }
        }
      }

      if (customPids.isNotEmpty()) {
        HorizontalDivider(color = BorderHairline)
        Text("PID Personalizzati Salvati (${customPids.size})", style = MaterialTheme.typography.titleMedium)
        customPids.forEach { cp ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(cp.label, style = MaterialTheme.typography.bodyLarge)
              Text(
                text = "${cp.canHeader.ifBlank { "7DF" }} · ${cp.command} · ${cp.formula}${if (cp.targetWheel.isNotBlank()) " · TPMS ${cp.targetWheel}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
              )
            }
            IconButton(onClick = { onDeleteCustomPid(cp.id) }) {
              Icon(Icons.Rounded.DeleteOutline, contentDescription = "Elimina PID", tint = CoralCritical)
            }
          }
        }
      }
    }
  }
}

@Composable
fun CreateProfileDialog(
  onCreate: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = SurfaceGraphite,
    title = { Text(stringResource(R.string.new_profile_title)) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(50) },
        label = { Text(stringResource(R.string.new_profile_label)) },
        placeholder = { Text("es. Viaggio Autostrada") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(
        onClick = {
          onCreate(name)
          onDismiss()
        },
        enabled = name.trim().isNotEmpty(),
      ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("Crea")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Annulla") }
    },
  )
}
