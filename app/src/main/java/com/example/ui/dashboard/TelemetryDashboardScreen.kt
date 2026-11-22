package com.example.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.model.AvailabilityState
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.ElmConnectionState
import com.example.data.model.ReadingStatus
import com.example.data.obd.BluetoothSppClient
import com.example.data.obd.D16aaPidCatalog
import com.example.data.obd.Elm327TelemetryRepository
import com.example.data.repository.DashboardRepository
import com.example.ui.auth.signOutUser
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CoralCritical
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOptimal
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceGraphite
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidBlack
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun TelemetryDashboardRoute(
  currentUserId: String,
) {
  val context = LocalContext.current
  val appContext = context.applicationContext
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  val bluetoothClient = remember(appContext) { BluetoothSppClient(appContext) }
  val telemetryRepository = remember(bluetoothClient) { Elm327TelemetryRepository(bluetoothClient) }

  val viewModel: TelemetryDashboardViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val dbId = appContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            TelemetryDashboardViewModel(
              dashboardRepository = DashboardRepository(db),
              telemetryRepository = telemetryRepository,
              currentUserId = currentUserId,
            )
          }
        },
    )

  val dashboardsState by viewModel.dashboardsState.collectAsStateWithLifecycle()
  val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
  val customPids by viewModel.customPids.collectAsStateWithLifecycle()
  val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()
  val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
  val supportedPids by viewModel.supportedPids.collectAsStateWithLifecycle()
  val readings by viewModel.readings.collectAsStateWithLifecycle()
  val batterySummary by viewModel.batterySummary.collectAsStateWithLifecycle()
  val tpmsReadings by viewModel.tpmsReadings.collectAsStateWithLifecycle()
  val bondedDevices by viewModel.bondedDevices.collectAsStateWithLifecycle()
  val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
  val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
  val elmLogs by viewModel.elmLogs.collectAsStateWithLifecycle()
  val probeResult by viewModel.probeResult.collectAsStateWithLifecycle()
  val isProbing by viewModel.isProbing.collectAsStateWithLifecycle()
  val bcmScanState by viewModel.bcmScanState.collectAsStateWithLifecycle()
  val statusBannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

  var showConnectionSheet by remember { mutableStateOf(false) }
  var showWidgetCatalog by remember { mutableStateOf(false) }
  var showPidInspector by remember { mutableStateOf(false) }
  var showCreateProfile by remember { mutableStateOf(false) }
  var editingWidget by remember { mutableStateOf<DashboardWidgetConfig?>(null) }

  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(statusBannerMessage) {
    statusBannerMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.clearStatusMessage()
    }
  }

  BackHandler(enabled = isEditMode) {
    viewModel.toggleEditMode(false)
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = VoidBlack,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    floatingActionButton = {
      if (isEditMode && activeProfile != null) {
        FloatingActionButton(
          onClick = { showWidgetCatalog = true },
          containerColor = CyanPrimary,
          contentColor = VoidBlack,
          modifier = Modifier.testTag("add_widget_fab"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.cd_add_widget),
          )
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      DashboardTopBar(
        connectionState = connectionState,
        isEditMode = isEditMode,
        onOpenConnection = {
          viewModel.refreshBondedDevices()
          showConnectionSheet = true
        },
        onOpenInspector = { showPidInspector = true },
        onToggleEditMode = { viewModel.toggleEditMode() },
        onSignOut = {
          viewModel.disconnectElm()
          signOutUser(credentialManager, scope)
        },
      )

      // Profile selector row
      val profiles = (dashboardsState as? DashboardsUiState.Ready)?.profiles.orEmpty()
      if (profiles.isNotEmpty()) {
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          items(profiles, key = { it.id }) { profile ->
            val selected = profile.id == activeProfile?.id
            FilterChip(
              selected = selected,
              onClick = { viewModel.selectProfile(profile.id) },
              label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                  selectedLabelColor = CyanPrimary,
                ),
            )
          }
          item {
            FilterChip(
              selected = false,
              onClick = { showCreateProfile = true },
              leadingIcon = {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              },
              label = { Text(stringResource(R.string.new_profile_chip)) },
            )
          }
          if (isEditMode && profiles.size > 1) {
            item {
              IconButton(onClick = { viewModel.deleteActiveProfile() }) {
                Icon(
                  imageVector = Icons.Rounded.DeleteOutline,
                  contentDescription = stringResource(R.string.cd_delete_profile),
                  tint = CoralCritical,
                )
              }
            }
          }
        }
      }

      // Active Alert Banner (only shown for genuine active warning/critical readings)
      val activeAlerts =
        remember(readings, batterySummary, customPids) {
          buildList {
            if (batterySummary.severity != ReadingStatus.NORMAL) {
              add("${batterySummary.stateLabel} (${batterySummary.voltage?.let { "%.2fV".format(it) } ?: ""})")
            }
            readings.values
              .filter { it.availability == AvailabilityState.ACTIVE && it.status != ReadingStatus.NORMAL }
              .filterNot { it.pidKey.startsWith("BATTERY") }
              .forEach { r ->
                val def = D16aaPidCatalog.findDefinition(r.pidKey, customPids)
                if (def != null) {
                  add("${def.shortLabel}: ${formatTelemetryValue(r, def)} ${def.unit}")
                }
              }
          }
        }

      AnimatedVisibility(visible = activeAlerts.isNotEmpty()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.small,
          color = AmberWarning.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.55f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.WarningAmber,
              contentDescription = null,
              tint = AmberWarning,
            )
            Text(
              text = activeAlerts.joinToString(" · "),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }

      when (val state = dashboardsState) {
        DashboardsUiState.Loading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyanPrimary)
          }
        }
        is DashboardsUiState.Error -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = state.message,
              style = MaterialTheme.typography.bodyLarge,
              color = CoralCritical,
            )
          }
        }
        is DashboardsUiState.Ready -> {
          val widgets = activeProfile?.widgets.orEmpty().sortedBy { it.order }
          if (widgets.isEmpty()) {
            EmptyDashboardPrompt(
              onAddWidget = { showWidgetCatalog = true },
              onOpenConnection = {
                viewModel.refreshBondedDevices()
                showConnectionSheet = true
              },
            )
          } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val columns =
                when {
                  maxWidth >= 840.dp -> 4
                  maxWidth >= 580.dp -> 3
                  else -> 2
                }
              LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 84.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                itemsIndexed(
                  items = widgets,
                  key = { _, w -> w.id },
                  span = { _, w -> GridItemSpan(w.spanColumns.toInt().coerceIn(1, columns)) },
                ) { idx, widget ->
                  val def = D16aaPidCatalog.findDefinition(widget.pidKey, customPids)
                  TelemetryWidgetCard(
                    widget = widget,
                    definition = def,
                    reading = readings[widget.pidKey],
                    batterySummary = batterySummary,
                    tpmsReadings = tpmsReadings,
                    isEditMode = isEditMode,
                    canMoveLeft = idx > 0,
                    canMoveRight = idx < widgets.lastIndex,
                    onMoveLeft = { viewModel.moveWidget(widget.id, -1) },
                    onMoveRight = { viewModel.moveWidget(widget.id, +1) },
                    onConfigure = { editingWidget = widget },
                    onRemove = { viewModel.removeWidget(widget.id) },
                    onOpenPidInspector = { showPidInspector = true },
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showConnectionSheet) {
    ConnectionSheet(
      bluetoothClient = bluetoothClient,
      connectionState = connectionState,
      bondedDevices = bondedDevices,
      discoveredDevices = discoveredDevices,
      isScanning = isScanning,
      elmLogs = elmLogs,
      onRefreshBonded = { viewModel.refreshBondedDevices() },
      onStartScan = { viewModel.startClassicScan() },
      onStopScan = { viewModel.stopClassicScan() },
      onConnectDevice = { viewModel.connectToElm(it) },
      onStartDemo = { viewModel.startDemoMode() },
      onDisconnect = { viewModel.disconnectElm() },
      onClearLogs = { viewModel.clearElmLogs() },
      onDismiss = { showConnectionSheet = false },
    )
  }

  if (showWidgetCatalog) {
    WidgetCatalogSheet(
      customPids = customPids,
      supportedPids = supportedPids,
      onAddWidget = { pidKey, style, span ->
        viewModel.addWidgetToActiveProfile(pidKey, style, span)
      },
      onOpenPidInspector = { showPidInspector = true },
      onDismiss = { showWidgetCatalog = false },
    )
  }

  if (showPidInspector) {
    PidInspectorSheet(
      supportedPids = supportedPids,
      customPids = customPids,
      probeResult = probeResult,
      isProbing = isProbing,
      bcmScanState = bcmScanState,
      onRescanPids = { viewModel.rescanSupportedPids() },
      onStartBcmScan = { viewModel.startBcmAndDpfScan() },
      onStartTpmsMenuSniff = { viewModel.startTpmsMenuSniff() },
      onApplyTpmsCandidate = { entry -> viewModel.applyTpmsCandidateToWheels(entry) },
      onCopyBcmReport = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
          ClipData.newPlainText("S-Cross CAN Report", viewModel.buildBcmScanClipboardReport())
        )
        viewModel.notifyReportCopied()
      },
      onProbeCommand = { h, c, f -> viewModel.probeCustomCommand(h, c, f) },
      onSaveCustomPid = { label, cmd, hdr, unit, formula, min, max, wheel, add ->
        viewModel.saveCustomPid(label, cmd, hdr, unit, formula, min, max, wheel, add)
      },
      onDeleteCustomPid = { viewModel.deleteCustomPid(it) },
      onDismiss = { showPidInspector = false },
    )
  }

  if (showCreateProfile) {
    CreateProfileDialog(
      onCreate = { viewModel.createProfile(it) },
      onDismiss = { showCreateProfile = false },
    )
  }

  editingWidget?.let { w ->
    WidgetConfigDialog(
      widget = w,
      definition = D16aaPidCatalog.findDefinition(w.pidKey, customPids),
      onSave = { style, span, warn, crit ->
        viewModel.updateWidgetAppearance(w.id, style, span, warn, crit)
      },
      onDismiss = { editingWidget = null },
    )
  }
}

@Composable
private fun DashboardTopBar(
  connectionState: ElmConnectionState,
  isEditMode: Boolean,
  onOpenConnection: () -> Unit,
  onOpenInspector: () -> Unit,
  onToggleEditMode: () -> Unit,
  onSignOut: () -> Unit,
) {
  val statusMeta =
    when (connectionState) {
      is ElmConnectionState.Connected ->
        Triple("CAN · ${connectionState.deviceName}", EmeraldOptimal, true)
      is ElmConnectionState.DemoMode ->
        Triple("Simulatore D16AA", AmberWarning, true)
      is ElmConnectionState.Connecting ->
        Triple("Connessione...", CyanPrimary, false)
      is ElmConnectionState.InitializingElm ->
        Triple(connectionState.step, CyanPrimary, false)
      is ElmConnectionState.DiscoveringPids ->
        Triple("Scansione PID...", CyanPrimary, false)
      is ElmConnectionState.Error ->
        Triple("Errore ELM327", CoralCritical, false)
      ElmConnectionState.Disconnected ->
        Triple("ELM327 Disconnesso", TextSecondary, false)
    }

  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.dashboard_header_title),
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.size(4.dp))
      Surface(
        modifier =
          Modifier.clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenConnection)
            .testTag("connection_status_pill"),
        shape = RoundedCornerShape(20.dp),
        color = SurfaceGraphite,
        border = BorderStroke(1.dp, statusMeta.second.copy(alpha = 0.5f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusMeta.second))
          Icon(
            imageVector = Icons.Rounded.Bluetooth,
            contentDescription = null,
            tint = statusMeta.second,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = statusMeta.first,
            style = MaterialTheme.typography.labelMedium,
            color = statusMeta.second,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = onOpenInspector,
        modifier = Modifier.testTag("pid_inspector_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Tune,
          contentDescription = stringResource(R.string.cd_pid_inspector),
          tint = TextSecondary,
        )
      }
      IconButton(
        onClick = onToggleEditMode,
        modifier = Modifier.testTag("toggle_edit_mode_button"),
      ) {
        Icon(
          imageVector = if (isEditMode) Icons.Rounded.Done else Icons.Rounded.Edit,
          contentDescription = stringResource(R.string.cd_toggle_edit_mode),
          tint = if (isEditMode) CyanPrimary else TextSecondary,
        )
      }
      IconButton(
        onClick = onSignOut,
        modifier = Modifier.testTag("sign_out_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.Logout,
          contentDescription = stringResource(R.string.cd_sign_out),
          tint = TextSecondary,
        )
      }
    }
  }
}

@Composable
private fun EmptyDashboardPrompt(
  onAddWidget: () -> Unit,
  onOpenConnection: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    shape = MaterialTheme.shapes.large,
    color = SurfaceGraphite,
    border = BorderStroke(1.dp, BorderHairline),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.Bluetooth,
        contentDescription = null,
        tint = CyanPrimary,
        modifier = Modifier.size(36.dp),
      )
      Text("Nessun widget in questo profilo", style = MaterialTheme.typography.titleMedium)
      Text(
        text = "Aggiungi widget dal catalogo PID D16AA oppure connetti il tuo adattatore ELM327.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onOpenConnection) { Text("Connessione ELM327") }
        TextButton(onClick = onAddWidget) { Text("Aggiungi Widget") }
      }
    }
  }
}
