package com.example.data.obd

import com.example.data.model.AvailabilityState
import com.example.data.model.BatteryStatusSummary
import com.example.data.model.CustomPidConfig
import com.example.data.model.ElmConnectionState
import com.example.data.model.ObdPidDefinition
import com.example.data.model.PidProbeResult
import com.example.data.model.PollingPriority
import com.example.data.model.ReadingStatus
import com.example.data.model.SupportedPidSnapshot
import com.example.data.model.TelemetryReading
import com.example.data.model.TpmsWheelReading
import com.example.data.model.WheelPosition
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Elm327TelemetryRepository(
  val bluetoothClient: BluetoothSppClient,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _connectionState = MutableStateFlow<ElmConnectionState>(ElmConnectionState.Disconnected)
  val connectionState: StateFlow<ElmConnectionState> = _connectionState.asStateFlow()

  private val _supportedPids = MutableStateFlow(SupportedPidSnapshot())
  val supportedPids: StateFlow<SupportedPidSnapshot> = _supportedPids.asStateFlow()

  private val _readings = MutableStateFlow<Map<String, TelemetryReading>>(emptyMap())
  val readings: StateFlow<Map<String, TelemetryReading>> = _readings.asStateFlow()

  private val _batterySummary = MutableStateFlow(BatteryStatusSummary())
  val batterySummary: StateFlow<BatteryStatusSummary> = _batterySummary.asStateFlow()

  private val _tpmsReadings = MutableStateFlow(defaultUnconfiguredTpms())
  val tpmsReadings: StateFlow<List<TpmsWheelReading>> = _tpmsReadings.asStateFlow()

  private var pollingJob: Job? = null
  private var activeDefinitions: List<ObdPidDefinition> = D16aaPidCatalog.standardPids
  private var activeCustomPids: List<CustomPidConfig> = emptyList()
  private var activeWidgetThresholds: Map<String, Pair<Double?, Double?>> = emptyMap()
  private var activeCanHeader: String = DEFAULT_OBD_HEADER

  fun updatePollingTargets(
    widgetPidKeys: Set<String>,
    customPids: List<CustomPidConfig>,
    thresholds: Map<String, Pair<Double?, Double?>>,
  ) {
    activeCustomPids = customPids
    activeWidgetThresholds = thresholds
    val defs = mutableListOf<ObdPidDefinition>()

    val expandedKeys = widgetPidKeys.toMutableSet()
    if (D16aaPidCatalog.PID_BATTERY_SMART in expandedKeys) {
      expandedKeys.add("BATTERY_ECU")
      expandedKeys.add("BATTERY_ATRV")
      expandedKeys.add("RPM")
    }
    if (D16aaPidCatalog.PID_TPMS_QUAD in expandedKeys) {
      customPids.filter { it.targetWheel.isNotBlank() }.forEach { expandedKeys.add(it.pidKey) }
    }

    for (key in expandedKeys) {
      if (key == D16aaPidCatalog.PID_BATTERY_SMART || key == D16aaPidCatalog.PID_TPMS_QUAD) continue
      D16aaPidCatalog.findDefinition(key, customPids)?.let { defs.add(it) }
    }
    activeDefinitions = defs
    if (_connectionState.value !is ElmConnectionState.DemoMode) {
      refreshTpmsUnconfiguredSlots()
    }
  }

  fun connectToDevice(address: String, fallbackName: String) {
    pollingJob?.cancel()
    pollingJob =
      scope.launch {
        _connectionState.value = ElmConnectionState.Connecting(fallbackName)
        val connectRes = bluetoothClient.connect(address)
        if (connectRes.isFailure) {
          _connectionState.value =
            ElmConnectionState.Error(
              connectRes.exceptionOrNull()?.message ?: "Impossibile connettersi al dispositivo ELM327"
            )
          return@launch
        }

        val deviceName = connectRes.getOrDefault(fallbackName)
        try {
          _connectionState.value = ElmConnectionState.InitializingElm(deviceName, "Reset ATZ...")
          bluetoothClient.sendCommand("ATZ", timeoutMs = 2800L)
          delay(500L)

          _connectionState.value = ElmConnectionState.InitializingElm(deviceName, "Configurazione CAN...")
          bluetoothClient.sendCommand("ATE0")
          bluetoothClient.sendCommand("ATL0")
          bluetoothClient.sendCommand("ATS0")
          bluetoothClient.sendCommand("ATH0")
          bluetoothClient.sendCommand("ATSP0")
          activeCanHeader = DEFAULT_OBD_HEADER

          val protocolRaw = runCatching { bluetoothClient.sendCommand("ATDP") }.getOrDefault("CAN Auto")
          val atrvRaw = runCatching { bluetoothClient.sendCommand("ATRV") }.getOrDefault("")
          D16aaPidCatalog.parseAtrvVoltage(atrvRaw)?.let { v ->
            updateReading("BATTERY_ATRV", v, AvailabilityState.ACTIVE)
            _batterySummary.value = D16aaPidCatalog.classifyBattery(v, null)
          }

          _connectionState.value = ElmConnectionState.DiscoveringPids(deviceName)
          val discovered = discoverSupportedPids()
          _supportedPids.value = discovered.copy(protocolName = protocolRaw)

          _connectionState.value =
            ElmConnectionState.Connected(
              deviceName = deviceName,
              deviceAddress = address,
              protocol = protocolRaw,
              ecuName = discovered.ecuName.ifBlank { "Suzuki D16AA ECU" },
            )

          runLivePollingLoop()
        } catch (e: Exception) {
          bluetoothClient.disconnect()
          _connectionState.value =
            ElmConnectionState.Error(e.message ?: "Errore inizializzazione ELM327")
        }
      }
  }

  fun startDemoMode() {
    pollingJob?.cancel()
    scope.launch { bluetoothClient.disconnect() }
    bluetoothClient.appendLog("DEMO", "Simulatore Suzuki S-Cross D16AA avviato")
    _supportedPids.value =
      SupportedPidSnapshot(
        supportedCommands =
          setOf(
            "ATRV", "0104", "0105", "010B", "010C", "010D", "010F",
            "0110", "0111", "0123", "0133", "0142", "015C", "017C",
          ),
        unsupportedCommands = setOf("0178"),
        ecuName = "D16AA-DEMO (Simulazione)",
        protocolName = "ISO 15765-4 (CAN 11/500)",
        discoveryCompleted = true,
      )
    _connectionState.value = ElmConnectionState.DemoMode

    pollingJob =
      scope.launch {
        var tick = 0
        while (isActive) {
          val t = tick * 0.25
          val rpm = 1850.0 + 620.0 * sin(t * 0.6) + 180.0 * sin(t * 1.9)
          val speed = (78.0 + 26.0 * sin(t * 0.35)).coerceIn(0.0, 140.0)
          val load = (48.0 + 28.0 * sin(t * 0.7)).coerceIn(12.0, 96.0)
          val turbo = (135.0 + 75.0 * (load / 100.0) + 12.0 * sin(t * 1.1)).coerceIn(98.0, 245.0)
          val rail = (640.0 + 680.0 * (load / 100.0)).coerceIn(280.0, 1650.0)
          val coolant = (88.0 + 3.0 * sin(t * 0.08)).coerceIn(70.0, 96.0)
          val iat = (34.0 + 4.0 * sin(t * 0.12)).coerceIn(20.0, 55.0)
          val maf = (24.0 + 42.0 * (rpm / 3500.0) * (turbo / 200.0)).coerceIn(8.0, 140.0)
          val oil = (94.0 + 2.5 * sin(t * 0.06)).coerceIn(75.0, 112.0)
          val dpfTemp = (340.0 + 95.0 * sin(t * 0.14) + 60.0 * (load / 100.0)).coerceIn(220.0, 620.0)
          val bat = 14.28 + 0.08 * sin(t * 0.4)
          val baro = 100.0
          val throttle = (32.0 + 25.0 * sin(t * 0.7)).coerceIn(0.0, 100.0)

          updateReading("RPM", rpm, AvailabilityState.ACTIVE)
          updateReading("SPEED", speed, AvailabilityState.ACTIVE)
          updateReading("TURBO_MAP", turbo, AvailabilityState.ACTIVE)
          updateReading("COOLANT_TEMP", coolant, AvailabilityState.ACTIVE)
          updateReading("FUEL_RAIL", rail, AvailabilityState.ACTIVE)
          updateReading("ENGINE_LOAD", load, AvailabilityState.ACTIVE)
          updateReading("MAF", maf, AvailabilityState.ACTIVE)
          updateReading("INTAKE_TEMP", iat, AvailabilityState.ACTIVE)
          updateReading("OIL_TEMP", oil, AvailabilityState.ACTIVE)
          updateReading("EXHAUST_DPF_TEMP", dpfTemp, AvailabilityState.ACTIVE)
          updateReading("BATTERY_ECU", bat, AvailabilityState.ACTIVE)
          updateReading("BATTERY_ATRV", bat - 0.1, AvailabilityState.ACTIVE)
          updateReading("BARO_PRESSURE", baro, AvailabilityState.ACTIVE)
          updateReading("THROTTLE", throttle, AvailabilityState.ACTIVE)
          updateReading("EGT_BANK1", null, AvailabilityState.UNSUPPORTED)

          _batterySummary.value = D16aaPidCatalog.classifyBattery(bat, rpm)
          _tpmsReadings.value =
            listOf(
              TpmsWheelReading(WheelPosition.FL, 2.42, "DEMO", "SIM", ReadingStatus.NORMAL, AvailabilityState.ACTIVE),
              TpmsWheelReading(WheelPosition.FR, 2.40, "DEMO", "SIM", ReadingStatus.NORMAL, AvailabilityState.ACTIVE),
              TpmsWheelReading(WheelPosition.RL, 2.25, "DEMO", "SIM", ReadingStatus.NORMAL, AvailabilityState.ACTIVE),
              TpmsWheelReading(WheelPosition.RR, 2.28, "DEMO", "SIM", ReadingStatus.NORMAL, AvailabilityState.ACTIVE),
            )

          tick++
          delay(450L)
        }
      }
  }

  fun disconnect() {
    pollingJob?.cancel()
    pollingJob = null
    scope.launch {
      bluetoothClient.disconnect()
      _connectionState.value = ElmConnectionState.Disconnected
      _batterySummary.value = BatteryStatusSummary()
      refreshTpmsUnconfiguredSlots()
    }
  }

  suspend fun rescanSupportedPids(): SupportedPidSnapshot {
    if (_connectionState.value is ElmConnectionState.DemoMode) return _supportedPids.value
    if (_connectionState.value !is ElmConnectionState.Connected) return _supportedPids.value
    val discovered = discoverSupportedPids()
    _supportedPids.value = discovered.copy(protocolName = _supportedPids.value.protocolName)
    return _supportedPids.value
  }

  suspend fun probeSingleCommand(
    canHeader: String,
    command: String,
    formula: String,
  ): PidProbeResult {
    val cleanCmd = command.uppercase(Locale.ROOT).replace(" ", "")
    val cleanHeader = canHeader.uppercase(Locale.ROOT).trim()
    if (_connectionState.value is ElmConnectionState.DemoMode) {
      return PidProbeResult(
        command = cleanCmd,
        canHeader = cleanHeader,
        rawResponse = "SIMULAZIONE ATTIVA — collegati all'ELM327 reale per interrogare la ECU",
        parsedBytes = emptyList(),
        decodedValue = null,
        errorMessage = "Modalità Demo attiva",
      )
    }
    if (_connectionState.value !is ElmConnectionState.Connected) {
      return PidProbeResult(
        command = cleanCmd,
        canHeader = cleanHeader,
        rawResponse = "",
        parsedBytes = emptyList(),
        decodedValue = null,
        errorMessage = "ELM327 non connesso",
      )
    }
    return try {
      switchHeaderIfNeeded(cleanHeader.ifBlank { DEFAULT_OBD_HEADER })
      val raw = bluetoothClient.sendCommand(cleanCmd, timeoutMs = 2500L)
      switchHeaderIfNeeded(DEFAULT_OBD_HEADER)
      if (cleanCmd == "ATRV") {
        val v = D16aaPidCatalog.parseAtrvVoltage(raw)
        PidProbeResult(cleanCmd, cleanHeader, raw, emptyList(), v)
      } else {
        val bytes = D16aaPidCatalog.parseElmHexBytes(raw, cleanCmd)
        val decoded =
          if (bytes.isNotEmpty()) D16aaPidCatalog.evalPidFormula(formula, bytes) else null
        PidProbeResult(cleanCmd, cleanHeader, raw, bytes, decoded)
      }
    } catch (e: Exception) {
      PidProbeResult(cleanCmd, cleanHeader, "", emptyList(), null, e.message ?: "Errore comando")
    }
  }

  private suspend fun discoverSupportedPids(): SupportedPidSnapshot {
    val supported = mutableSetOf("ATRV")
    val unsupported = mutableSetOf<String>()
    switchHeaderIfNeeded(DEFAULT_OBD_HEADER)

    val bitmaskQueries = listOf("0100" to 0x00, "0120" to 0x20, "0140" to 0x40, "0160" to 0x60)
    for ((cmd, offset) in bitmaskQueries) {
      val raw = runCatching { bluetoothClient.sendCommand(cmd, timeoutMs = 2500L) }.getOrDefault("")
      val bytes = D16aaPidCatalog.parseElmHexBytes(raw, cmd)
      if (bytes.size >= 4) {
        supported.addAll(D16aaPidCatalog.decodeBitmaskPids(offset, bytes))
        val hasNextBlock = (bytes[3] and 0x01) != 0
        if (!hasNextBlock) break
      } else {
        break
      }
    }

    for (std in D16aaPidCatalog.standardPids) {
      if (std.command.startsWith("01") && std.command !in supported) {
        unsupported.add(std.command)
      }
    }

    val ecuName =
      runCatching {
        val raw = bluetoothClient.sendCommand("090A", timeoutMs = 2000L)
        val bytes = D16aaPidCatalog.parseElmHexBytes(raw, "090A")
        bytes
          .drop(1)
          .filter { it in 32..126 }
          .map { it.toChar() }
          .joinToString("")
          .trim()
      }.getOrDefault("")

    return SupportedPidSnapshot(
      supportedCommands = supported,
      unsupportedCommands = unsupported,
      ecuName = ecuName,
      discoveryCompleted = true,
    )
  }

  private suspend fun runLivePollingLoop() {
    var cycle = 0
    while (scope.isActive && _connectionState.value is ElmConnectionState.Connected) {
      val targets = activeDefinitions.toList()
      val supportedSet = _supportedPids.value.supportedCommands

      if (targets.isEmpty()) {
        delay(500L)
        continue
      }

      for (def in targets) {
        if (!scope.isActive || _connectionState.value !is ElmConnectionState.Connected) break
        val shouldPollThisCycle =
          when (def.priority) {
            PollingPriority.HIGH -> true
            PollingPriority.MEDIUM -> cycle % 2 == 0
            PollingPriority.LOW -> cycle % 4 == 0
          }
        if (!shouldPollThisCycle) continue

        if (!def.isCustom && def.command.startsWith("01") && supportedSet.isNotEmpty() && def.command !in supportedSet) {
          updateReading(def.key, null, AvailabilityState.UNSUPPORTED)
          continue
        }

        val targetHeader = def.canHeader.ifBlank { DEFAULT_OBD_HEADER }
        try {
          switchHeaderIfNeeded(targetHeader)
          val raw = bluetoothClient.sendCommand(def.command, timeoutMs = 1800L)
          if (def.command == "ATRV") {
            val v = D16aaPidCatalog.parseAtrvVoltage(raw)
            if (v != null) {
              updateReading(def.key, v, AvailabilityState.ACTIVE)
            }
          } else {
            val bytes = D16aaPidCatalog.parseElmHexBytes(raw, def.command)
            val decoded = D16aaPidCatalog.evalPidFormula(def.formula, bytes)
            if (decoded != null) {
              updateReading(def.key, decoded, AvailabilityState.ACTIVE)
            } else if (raw.contains("NO DATA", ignoreCase = true)) {
              updateReading(def.key, null, AvailabilityState.UNSUPPORTED)
            }
          }
        } catch (_: Exception) {
          // Keep loop resilient across transient frame timeouts
        }
        delay(25L)
      }

      // Refresh battery interpretation from 0142 or ATRV
      val ecuV = _readings.value["BATTERY_ECU"]?.value
      val atrvV = _readings.value["BATTERY_ATRV"]?.value
      val rpm = _readings.value["RPM"]?.value
      _batterySummary.value =
        D16aaPidCatalog.classifyBattery(ecuV ?: atrvV, rpm).copy(
          sourceLabel = if (ecuV != null) "PID 0142 (ECU)" else "ATRV (Presa OBD)"
        )

      // Refresh TPMS readings from any configured custom wheel PIDs
      refreshRealTpmsSlots()

      cycle++
      delay(80L)
    }
  }

  private suspend fun switchHeaderIfNeeded(targetHeader: String) {
    val normalized = targetHeader.uppercase(Locale.ROOT).trim().ifBlank { DEFAULT_OBD_HEADER }
    if (normalized != activeCanHeader) {
      bluetoothClient.sendCommand("ATSH$normalized", timeoutMs = 1500L)
      activeCanHeader = normalized
    }
  }

  private fun updateReading(pidKey: String, value: Double?, availability: AvailabilityState) {
    val def = D16aaPidCatalog.findDefinition(pidKey, activeCustomPids)
    val customThresholds = activeWidgetThresholds[pidKey]
    val warnThresh = customThresholds?.first ?: def?.defaultWarningThreshold
    val critThresh = customThresholds?.second ?: def?.defaultCriticalThreshold

    val status =
      if (value == null) {
        ReadingStatus.NORMAL
      } else if (pidKey.startsWith("BATTERY")) {
        // Low voltage thresholds for battery
        when {
          critThresh != null && value <= critThresh -> ReadingStatus.CRITICAL
          warnThresh != null && value <= warnThresh -> ReadingStatus.WARNING
          value > 15.1 -> ReadingStatus.CRITICAL
          else -> ReadingStatus.NORMAL
        }
      } else {
        when {
          critThresh != null && value >= critThresh -> ReadingStatus.CRITICAL
          warnThresh != null && value >= warnThresh -> ReadingStatus.WARNING
          else -> ReadingStatus.NORMAL
        }
      }

    _readings.update { current ->
      val prev = current[pidKey]
      val newHistory =
        if (value != null) ((prev?.history ?: emptyList()) + value).takeLast(30)
        else prev?.history ?: emptyList()
      current +
        (pidKey to
          TelemetryReading(
            pidKey = pidKey,
            value = value,
            availability = availability,
            status = status,
            history = newHistory,
            updatedAtMillis = System.currentTimeMillis(),
          ))
    }
  }

  private fun refreshRealTpmsSlots() {
    val currentReadings = _readings.value
    _tpmsReadings.value =
      WheelPosition.entries.map { wheel ->
        val customPid = activeCustomPids.firstOrNull { it.targetWheel == wheel.code }
        if (customPid == null) {
          TpmsWheelReading(
            wheel = wheel,
            availability = AvailabilityState.NEEDS_BCM_CONFIG,
          )
        } else {
          val reading = currentReadings[customPid.pidKey]
          val p = reading?.value
          val status =
            when {
              p == null -> ReadingStatus.NORMAL
              p < 1.9 -> ReadingStatus.CRITICAL
              p < 2.1 -> ReadingStatus.WARNING
              else -> ReadingStatus.NORMAL
            }
          TpmsWheelReading(
            wheel = wheel,
            pressureBar = p,
            configuredCommand = customPid.command,
            configuredHeader = customPid.canHeader,
            status = status,
            availability = reading?.availability ?: AvailabilityState.WAITING,
          )
        }
      }
  }

  private fun refreshTpmsUnconfiguredSlots() {
    _tpmsReadings.value =
      WheelPosition.entries.map { wheel ->
        val customPid = activeCustomPids.firstOrNull { it.targetWheel == wheel.code }
        TpmsWheelReading(
          wheel = wheel,
          configuredCommand = customPid?.command,
          configuredHeader = customPid?.canHeader,
          availability =
            if (customPid == null) AvailabilityState.NEEDS_BCM_CONFIG
            else AvailabilityState.WAITING,
        )
      }
  }

  private fun defaultUnconfiguredTpms(): List<TpmsWheelReading> =
    WheelPosition.entries.map { TpmsWheelReading(wheel = it) }

  private companion object {
    const val DEFAULT_OBD_HEADER = "7DF"
  }
}
