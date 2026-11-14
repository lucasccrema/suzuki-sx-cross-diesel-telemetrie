package com.example.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BatteryStatusSummary
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
import com.example.data.model.WidgetStyle
import com.example.data.obd.D16aaPidCatalog
import com.example.data.obd.Elm327TelemetryRepository
import com.example.data.repository.DashboardRepository
import com.google.firebase.firestore.FirebaseFirestoreException
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

  fun clearElmLogs() {
    telemetryRepository.bluetoothClient.clearLogs()
  }

  override fun onCleared() {
    super.onCleared()
    scanJob?.cancel()
  }

  private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

  private companion object {
    const val TAG = "TelemetryDashboardVM"
  }
}
