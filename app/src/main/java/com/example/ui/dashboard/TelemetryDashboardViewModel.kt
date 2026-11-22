package com.example.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BatteryStatusSummary
import com.example.data.model.BcmScanEntry
import com.example.data.model.BcmScanState
import com.example.data.model.BluetoothDeviceItem
import com.example.data.model.CustomPidConfig
import com.example.data.model.DashboardProfile
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.ElmConnectionState
import com.example.data.model.ElmLogEntry
import com.example.data.model.PidProbeResult
import com.example.data.model.SupportedPidSnapshot
import com.example.data.model.TelemetryReading
import com.example.data.model.TpmsWheelReading
import com.example.data.model.WheelPosition
import com.example.data.model.WidgetStyle
import com.example.data.obd.D16aaPidCatalog
import com.example.data.obd.Elm327TelemetryRepository
import com.example.data.repository.DashboardRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DashboardsUiState {
  data object Loading : DashboardsUiState

  data class Ready(val profiles: List<DashboardProfile>) : DashboardsUiState

  data class Error(val message: String) : DashboardsUiState
}

class TelemetryDashboardViewModel(
  private val dashboardRepository: DashboardRepository,
  private val telemetryRepository: Elm327TelemetryRepository,
  private val currentUserId: String,
) : ViewModel() {

  private val _selectedProfileId = MutableStateFlow<String?>(null)
  private val _isEditMode = MutableStateFlow(false)
  val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

  private val _bondedDevices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
  val bondedDevices: StateFlow<List<BluetoothDeviceItem>> = _bondedDevices.asStateFlow()

  private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
  val discoveredDevices: StateFlow<List<BluetoothDeviceItem>> = _discoveredDevices.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  private val _probeResult = MutableStateFlow<PidProbeResult?>(null)
  val probeResult: StateFlow<PidProbeResult?> = _probeResult.asStateFlow()

  private val _isProbing = MutableStateFlow(false)
  val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

  private val _statusBannerMessage = MutableStateFlow<String?>(null)
  val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

  private var scanJob: Job? = null
  private var bcmScanJob: Job? = null

  val dashboardsState: StateFlow<DashboardsUiState> =
    dashboardRepository
      .observeDashboards(currentUserId)
      .map<List<DashboardProfile>, DashboardsUiState> { DashboardsUiState.Ready(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing dashboards", rootError)
        emit(DashboardsUiState.Error(rootError.message ?: "Errore sincronizzazione cloud"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = DashboardsUiState.Loading,
      )

  val customPids: StateFlow<List<CustomPidConfig>> =
    dashboardRepository
      .observeCustomPids(currentUserId)
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        Log.w(TAG, "Error observing custom PIDs", firestoreError ?: error)
        emit(emptyList())
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList(),
      )

  val activeProfile: StateFlow<DashboardProfile?> =
    combine(dashboardsState, _selectedProfileId) { state, selectedId ->
        val list = (state as? DashboardsUiState.Ready)?.profiles.orEmpty()
        list.firstOrNull { it.id == selectedId }
          ?: list.firstOrNull { it.isDefault }
          ?: list.firstOrNull()
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null,
      )

  val connectionState: StateFlow<ElmConnectionState> = telemetryRepository.connectionState
  val supportedPids: StateFlow<SupportedPidSnapshot> = telemetryRepository.supportedPids
  val readings: StateFlow<Map<String, TelemetryReading>> = telemetryRepository.readings
  val batterySummary: StateFlow<BatteryStatusSummary> = telemetryRepository.batterySummary
  val tpmsReadings: StateFlow<List<TpmsWheelReading>> = telemetryRepository.tpmsReadings
  val elmLogs: StateFlow<List<ElmLogEntry>> = telemetryRepository.bluetoothClient.logs
  val bcmScanState: StateFlow<BcmScanState> = telemetryRepository.bcmScanState

  init {
    viewModelScope.launch {
      runCatching { dashboardRepository.ensureDefaultDashboards(currentUserId) }
        .onFailure { Log.w(TAG, "Failed to initialize default dashboards", it) }
    }
    refreshBondedDevices()

    // Keep ELM327 polling loop aligned with active dashboard widgets and custom PIDs
    viewModelScope.launch {
      combine(activeProfile, customPids) { profile, custom -> profile to custom }
        .collect { (profile, custom) ->
          val widgets = profile?.widgets.orEmpty()
          val pidKeys = widgets.map { it.pidKey }.toSet()
          val thresholds =
            widgets.associate { w ->
              w.pidKey to (w.warningThreshold to w.criticalThreshold)
            }
          telemetryRepository.updatePollingTargets(pidKeys, custom, thresholds)
        }
    }
  }

  fun selectProfile(profileId: String) {
    _selectedProfileId.value = profileId
  }

  fun toggleEditMode(enabled: Boolean = !_isEditMode.value) {
    _isEditMode.value = enabled
  }

  fun clearStatusMessage() {
    _statusBannerMessage.value = null
  }

  fun createProfile(name: String) {
    val cleanName = name.trim().take(50)
    if (cleanName.isEmpty()) return
    viewModelScope.launch {
      runCatching {
        val profile =
          DashboardProfile(
            userId = currentUserId,
            name = cleanName,
            isDefault = false,
            widgets =
              listOf(
                DashboardWidgetConfig("w_${shortId()}", "RPM", WidgetStyle.NUMERIC.name, 1L, 0L),
                DashboardWidgetConfig("w_${shortId()}", "SPEED", WidgetStyle.NUMERIC.name, 1L, 1L),
                DashboardWidgetConfig(
                  "w_${shortId()}",
                  D16aaPidCatalog.PID_BATTERY_SMART,
                  WidgetStyle.BATTERY_STATUS.name,
                  2L,
                  2L,
                ),
              ),
          )
        val createdId = dashboardRepository.createDashboard(currentUserId, profile)
        _selectedProfileId.value = createdId
        _isEditMode.value = true
      }.onFailure {
        _statusBannerMessage.value = it.localizedMessage ?: "Errore creazione profilo"
      }
    }
  }

  fun deleteActiveProfile() {
    val profile = activeProfile.value ?: return
    val profiles = (dashboardsState.value as? DashboardsUiState.Ready)?.profiles.orEmpty()
    if (profiles.size <= 1) {
      _statusBannerMessage.value = "Deve rimanere almeno un profilo dashboard."
      return
    }
    viewModelScope.launch {
      runCatching {
        dashboardRepository.deleteDashboard(currentUserId, profile.id)
        _selectedProfileId.value = profiles.firstOrNull { it.id != profile.id }?.id
      }.onFailure {
        _statusBannerMessage.value = it.localizedMessage ?: "Errore eliminazione profilo"
      }
    }
  }

  fun addWidgetToActiveProfile(pidKey: String, style: WidgetStyle, spanColumns: Long) {
    val profile = activeProfile.value ?: return
    if (profile.widgets.size >= 24) {
      _statusBannerMessage.value = "Limite massimo di 24 widget raggiunto per profilo."
      return
    }
    val def = D16aaPidCatalog.findDefinition(pidKey, customPids.value)
    val newWidget =
      DashboardWidgetConfig(
        id = "w_${shortId()}",
        pidKey = pidKey,
        style = style.name,
        spanColumns = spanColumns.coerceIn(1L, 2L),
        order = profile.widgets.size.toLong(),
        warningThreshold = def?.defaultWarningThreshold,
        criticalThreshold = def?.defaultCriticalThreshold,
      )
    persistUpdatedWidgets(profile, profile.widgets + newWidget)
  }

  fun removeWidget(widgetId: String) {
    val profile = activeProfile.value ?: return
    persistUpdatedWidgets(profile, profile.widgets.filterNot { it.id == widgetId })
  }

  fun moveWidget(widgetId: String, delta: Int) {
    val profile = activeProfile.value ?: return
    val list = profile.widgets.sortedBy { it.order }.toMutableList()
    val index = list.indexOfFirst { it.id == widgetId }
    val target = index + delta
    if (index !in list.indices || target !in list.indices) return
    val item = list.removeAt(index)
    list.add(target, item)
    persistUpdatedWidgets(profile, list)
  }

  fun updateWidgetAppearance(
    widgetId: String,
    style: WidgetStyle,
    spanColumns: Long,
    warningThreshold: Double?,
    criticalThreshold: Double?,
  ) {
    val profile = activeProfile.value ?: return
    val updated =
      profile.widgets.map { w ->
        if (w.id == widgetId) {
          w.copy(
            style = style.name,
            spanColumns = spanColumns.coerceIn(1L, 2L),
            warningThreshold = warningThreshold,
            criticalThreshold = criticalThreshold,
          )
        } else w
      }
    persistUpdatedWidgets(profile, updated)
  }

  private fun persistUpdatedWidgets(
    profile: DashboardProfile,
    newWidgets: List<DashboardWidgetConfig>,
  ) {
    val reindexed = newWidgets.mapIndexed { idx, w -> w.copy(order = idx.toLong()) }
    viewModelScope.launch {
      runCatching {
        dashboardRepository.updateDashboard(currentUserId, profile.copy(widgets = reindexed))
      }.onFailure {
        _statusBannerMessage.value = it.localizedMessage ?: "Errore salvataggio layout su cloud"
      }
    }
  }

  fun refreshBondedDevices() {
    _bondedDevices.value = telemetryRepository.bluetoothClient.getBondedDevices()
  }

  fun startClassicScan() {
    scanJob?.cancel()
    _discoveredDevices.value = emptyList()
    _isScanning.value = true
    scanJob =
      viewModelScope.launch {
        telemetryRepository.bluetoothClient
          .scanClassicDevices()
          .catch { _isScanning.value = false }
          .collect { found ->
            _discoveredDevices.update { current ->
              if (current.any { it.address == found.address }) current else current + found
            }
          }
        _isScanning.value = false
      }
  }

  fun stopClassicScan() {
    scanJob?.cancel()
    scanJob = null
    _isScanning.value = false
  }

  fun connectToElm(device: BluetoothDeviceItem) {
    stopClassicScan()
    telemetryRepository.connectToDevice(device.address, device.name)
  }

  fun startDemoMode() {
    stopClassicScan()
    telemetryRepository.startDemoMode()
  }

  fun disconnectElm() {
    telemetryRepository.disconnect()
  }

  fun rescanSupportedPids() {
    viewModelScope.launch {
      telemetryRepository.rescanSupportedPids()
    }
  }

  fun probeCustomCommand(canHeader: String, command: String, formula: String) {
    viewModelScope.launch {
      _isProbing.value = true
      _probeResult.value = telemetryRepository.probeSingleCommand(canHeader, command, formula)
      _isProbing.value = false
    }
  }

  fun saveCustomPid(
    label: String,
    command: String,
    canHeader: String,
    unit: String,
    formula: String,
    minValue: Double,
    maxValue: Double,
    targetWheel: String,
    addToActiveDashboard: Boolean,
  ) {
    val cleanLabel = label.trim().take(50)
    val cleanCommand = command.uppercase().replace(" ", "").take(16)
    if (cleanLabel.isEmpty() || cleanCommand.length < 2) return

    val pidKey = "CUSTOM_${shortId().uppercase()}"
    viewModelScope.launch {
      runCatching {
        val config =
          CustomPidConfig(
            userId = currentUserId,
            pidKey = pidKey,
            label = cleanLabel,
            command = cleanCommand,
            canHeader = canHeader.uppercase().trim().take(8),
            unit = unit.trim().take(16),
            formula = formula.trim().ifBlank { "A" }.take(80),
            minValue = minValue,
            maxValue = maxValue.coerceAtLeast(minValue),
            targetWheel = targetWheel,
          )
        dashboardRepository.saveCustomPid(currentUserId, config)
        if (addToActiveDashboard && targetWheel.isBlank()) {
          addWidgetToActiveProfile(pidKey, WidgetStyle.NUMERIC, 1L)
        }
        _statusBannerMessage.value = "PID personalizzato salvato sul tuo account Google."
      }.onFailure {
        _statusBannerMessage.value = it.localizedMessage ?: "Errore salvataggio PID"
      }
    }
  }

  fun deleteCustomPid(pidId: String) {
    viewModelScope.launch {
      runCatching { dashboardRepository.deleteCustomPid(currentUserId, pidId) }
        .onFailure { _statusBannerMessage.value = it.localizedMessage ?: "Errore eliminazione PID" }
    }
  }

  fun startBcmAndDpfScan() {
    if (bcmScanState.value.isScanning) return
    bcmScanJob?.cancel()
    bcmScanJob =
      viewModelScope.launch {
        telemetryRepository.scanBcmAndExtendedPids()
      }
  }

  fun startTpmsMenuSniff() {
    if (bcmScanState.value.isScanning) return
    bcmScanJob?.cancel()
    bcmScanJob =
      viewModelScope.launch {
        telemetryRepository.sniffTpmsMenuTraffic()
      }
  }

  fun applyTpmsCandidateToWheels(entry: BcmScanEntry) {
    if (entry.command.equals("PASSIVE", ignoreCase = true)) return
    val candidate = entry.tpmsCandidate ?: return
    if (candidate.formulaPerWheel.size < 4) return

    viewModelScope.launch {
      runCatching {
        val existingWheelPids = customPids.value.filter { it.targetWheel.isNotBlank() }
        existingWheelPids.forEach { old ->
          if (old.id.isNotBlank()) {
            dashboardRepository.deleteCustomPid(currentUserId, old.id)
          }
        }
        WheelPosition.entries.forEachIndexed { idx, wheel ->
          val config =
            CustomPidConfig(
              userId = currentUserId,
              pidKey = "TPMS_${wheel.code}_${shortId().uppercase(Locale.ROOT)}",
              label = "TPMS ${wheel.labelIt}",
              command = entry.command.uppercase(Locale.ROOT).take(16),
              canHeader = entry.fullHeaderSpec.uppercase(Locale.ROOT).take(8),
              unit = "bar",
              formula = candidate.formulaPerWheel[idx].take(80),
              minValue = 0.0,
              maxValue = 4.0,
              targetWheel = wheel.code,
            )
          dashboardRepository.saveCustomPid(currentUserId, config)
        }
        _statusBannerMessage.value =
          "TPMS applicato alle 4 ruote (${entry.fullHeaderSpec} · ${entry.command})"
      }.onFailure {
        _statusBannerMessage.value = it.localizedMessage ?: "Errore configurazione ruote TPMS"
      }
    }
  }

  fun buildBcmScanClipboardReport(): String {
    val snapshot = supportedPids.value
    val scan = bcmScanState.value
    val curReadings = readings.value
    val ecuV = curReadings["BATTERY_ECU"]?.value?.let { String.format(Locale.ROOT, "%.2fV", it) } ?: "N/D"
    val atrvV = curReadings["BATTERY_ATRV"]?.value?.let { String.format(Locale.ROOT, "%.2fV", it) } ?: "N/D"
    val scanStatus =
      when {
        !scan.hasRun -> "NON ESEGUITA (tocca prima 'Cerca moduli BCM / TPMS')"
        scan.isScanning -> "IN CORSO (${scan.completedProbes}/${scan.totalProbes})"
        else -> "COMPLETATA (${scan.completedProbes}/${scan.totalProbes})"
      }

    return buildString {
      appendLine("=== S-Cross D16AA CAN Report ===")
      appendLine("Protocollo: ${snapshot.protocolName.ifBlank { scan.protocolName }.ifBlank { "N/D" }}")
      appendLine("ECU (090A): ${snapshot.ecuName.ifBlank { scan.ecuInfo }.ifBlank { "N/D" }}")
      appendLine("Batteria: 0142=$ecuV | ATRV=$atrvV")
      appendLine("Mode 01 supportati (${snapshot.supportedCommands.size}): ${snapshot.supportedCommands.sorted().joinToString(" ")}")
      appendLine("Stato scansione BCM: $scanStatus")
      scan.errorMessage?.let { appendLine("Errore scansione: $it") }
      appendLine()
      appendLine("--- Risposte moduli estesi (${scan.foundEntries.size}) ---")
      if (scan.foundEntries.isEmpty()) {
        appendLine("Nessuna risposta positiva.")
      } else {
        scan.foundEntries.forEach { e ->
          val hex = e.parsedBytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }
          appendLine("[${e.categoryHint}] ${e.fullHeaderSpec} -> ${e.command}")
          appendLine("  Bytes: $hex")
          appendLine("  Raw: ${e.rawResponse.replace("\n", " | ")}")
          e.tpmsCandidate?.let { cand ->
            val bars = cand.samplePressuresBar.joinToString(" / ") { String.format(Locale.ROOT, "%.2f", it) }
            appendLine("  TPMS Candidate (${cand.scaleLabel}): $bars bar | formule=${cand.formulaPerWheel.joinToString(",")}")
          }
        }
      }
      if (scan.scanTrace.isNotEmpty()) {
        appendLine()
        appendLine("--- Traccia scansione CAN (${scan.scanTrace.size}) ---")
        scan.scanTrace.forEach { appendLine(it) }
      }
      val logTail = elmLogs.value.takeLast(25)
      if (logTail.isNotEmpty()) {
        appendLine()
        appendLine("--- Ultimi log ELM327 ---")
        logTail.forEach { l -> appendLine("[${l.direction}] ${l.message}") }
      }
    }.trim()
  }

  fun notifyReportCopied() {
    _statusBannerMessage.value = "Report scansione copiato negli appunti."
  }

  fun clearElmLogs() {
    telemetryRepository.bluetoothClient.clearLogs()
  }

  override fun onCleared() {
    super.onCleared()
    scanJob?.cancel()
    bcmScanJob?.cancel()
  }

  private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

  private companion object {
    const val TAG = "TelemetryDashboardVM"
  }
}
