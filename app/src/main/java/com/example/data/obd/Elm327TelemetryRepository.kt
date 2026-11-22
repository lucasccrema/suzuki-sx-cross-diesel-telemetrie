package com.example.data.obd

import com.example.data.model.AvailabilityState
import com.example.data.model.BatteryStatusSummary
import com.example.data.model.BcmScanEntry
import com.example.data.model.BcmScanState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

  private val _bcmScanState = MutableStateFlow(BcmScanState())
  val bcmScanState: StateFlow<BcmScanState> = _bcmScanState.asStateFlow()

  private var pollingJob: Job? = null
  private var activeDefinitions: List<ObdPidDefinition> = D16aaPidCatalog.standardPids
  private var activeCustomPids: List<CustomPidConfig> = emptyList()
  private var activeWidgetThresholds: Map<String, Pair<Double?, Double?>> = emptyMap()
  private var activeTxHeader: String = DEFAULT_OBD_HEADER
  private var activeRxFilter: String = ""

  private val exclusiveOperationMutex = Mutex()
  private val isExclusiveOperation = MutableStateFlow(false)

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
          activeTxHeader = DEFAULT_OBD_HEADER
          activeRxFilter = ""

          val protocolRaw = runCatching { bluetoothClient.sendCommand("ATDP") }.getOrDefault("CAN Auto")
          val atrvRaw = runCatching { bluetoothClient.sendCommand("ATRV") }.getOrDefault("")
          D16aaPidCatalog.parseAtrvVoltage(atrvRaw)?.let { v ->
            updateReading("BATTERY_ATRV", v, AvailabilityState.ACTIVE)
            _batterySummary.value = D16aaPidCatalog.classifyBattery(v, null)
          }

          _connectionState.value = ElmConnectionState.DiscoveringPids(deviceName)
          val discovered = discoverSupportedPids()
          val resolvedProto = discovered.protocolName.ifBlank { protocolRaw }
          _supportedPids.value = discovered.copy(protocolName = resolvedProto)

          _connectionState.value =
            ElmConnectionState.Connected(
              deviceName = deviceName,
              deviceAddress = address,
              protocol = resolvedProto,
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
    isExclusiveOperation.value = false
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
    isExclusiveOperation.value = true
    return try {
      exclusiveOperationMutex.withLock {
        val discovered = discoverSupportedPids()
        _supportedPids.value = discovered
        _supportedPids.value
      }
    } finally {
      isExclusiveOperation.value = false
    }
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
    isExclusiveOperation.value = true
    return try {
      exclusiveOperationMutex.withLock {
        try {
          switchHeaderIfNeeded(cleanHeader.ifBlank { DEFAULT_OBD_HEADER })
          val raw = bluetoothClient.sendCommand(cleanCmd, timeoutMs = 2500L)
          if (cleanCmd == "ATRV") {
            val v = D16aaPidCatalog.parseAtrvVoltage(raw)
            PidProbeResult(cleanCmd, cleanHeader, raw, emptyList(), v)
          } else {
            val bytes = D16aaPidCatalog.parseElmHexBytes(raw, cleanCmd)
            val decoded =
              if (bytes.isNotEmpty()) D16aaPidCatalog.evalPidFormula(formula, bytes) else null
            PidProbeResult(cleanCmd, cleanHeader, raw, bytes, decoded)
          }
        } finally {
          runCatching { switchHeaderIfNeeded(DEFAULT_OBD_HEADER) }
        }
      }
    } catch (e: Exception) {
      PidProbeResult(cleanCmd, cleanHeader, "", emptyList(), null, e.message ?: "Errore comando")
    } finally {
      isExclusiveOperation.value = false
    }
  }

  suspend fun scanBcmAndExtendedPids() =
    withContext(Dispatchers.IO) {
      val conn = _connectionState.value
      val snapshot = _supportedPids.value
      if (conn is ElmConnectionState.DemoMode) {
        _bcmScanState.value =
          BcmScanState(
            isScanning = false,
            hasRun = true,
            ecuInfo = snapshot.ecuName,
            protocolName = snapshot.protocolName,
            errorMessage =
              "Modalità Demo attiva: collegati all'adattatore ELM327 in auto per interrogare BCM, TPMS e DPF reali.",
          )
        return@withContext
      }
      if (conn !is ElmConnectionState.Connected) {
        _bcmScanState.value =
          BcmScanState(
            isScanning = false,
            hasRun = true,
            errorMessage = "Collega prima l'adattatore ELM327 via Bluetooth.",
          )
        return@withContext
      }

      val stage1Headers = buildStage1Headers()
      val totalSteps = stage1Headers.size * 2 + PRIORITY_SUZUKI_HEADERS.size + 11 + 6 + 14 + 7 + 1
      val foundEntries = mutableListOf<BcmScanEntry>()
      val traceLines = mutableListOf<String>()
      val noDataHeaders = mutableListOf<String>()
      val responsiveTxHeaders = linkedMapOf<String, String>() // txHeader -> observedRxHeader
      var resolvedProtocol = snapshot.protocolName
      var completedCount = 0

      _bcmScanState.value =
        BcmScanState(
          isScanning = true,
          currentStepLabel = "Verifica adattatore e filtri CAN...",
          progressFraction = 0f,
          totalProbes = totalSteps,
          completedProbes = 0,
          ecuInfo = snapshot.ecuName,
          protocolName = snapshot.protocolName,
          foundEntries = emptyList(),
          scanTrace = emptyList(),
          errorMessage = null,
          hasRun = true,
        )

      isExclusiveOperation.value = true
      var abortedError: String? = null
      try {
        exclusiveOperationMutex.withLock {
          val ati = runCatching { bluetoothClient.sendCommand("ATI", timeoutMs = 1200L) }.getOrElse { "ERR" }.replace("\n", " ").trim()
          val at1 = runCatching { bluetoothClient.sendCommand("AT@1", timeoutMs = 1200L) }.getOrElse { "ERR" }.replace("\n", " ").trim()
          val dp = runCatching { bluetoothClient.sendCommand("ATDP", timeoutMs = 1500L) }.getOrElse { "ERR:${it.javaClass.simpleName}" }
          val dpn = runCatching { bluetoothClient.sendCommand("ATDPN", timeoutMs = 1500L) }.getOrElse { "ERR" }
          val v = runCatching { bluetoothClient.sendCommand("ATRV", timeoutMs = 1500L) }.getOrElse { "ERR" }
          if (!dp.startsWith("ERR") && dp.isNotBlank()) {
            resolvedProtocol = dp
            _supportedPids.update { it.copy(protocolName = dp) }
          }
          traceLines.add("ELM: ATI=$ati | AT@1=$at1 | ATDP=$dp | ATDPN=$dpn | ATRV=$v")

          val atstRes = runCatching { bluetoothClient.sendCommand("ATST20", timeoutMs = 1200L) }.getOrElse { "ERR" }
          val ath1Res = runCatching { bluetoothClient.sendCommand("ATH1", timeoutMs = 1200L) }.getOrElse { "ERR" }
          val cmRes = runCatching { bluetoothClient.sendCommand("ATCM700", timeoutMs = 1200L) }.getOrElse { "ERR" }
          val cfRes = runCatching { bluetoothClient.sendCommand("ATCF700", timeoutMs = 1200L) }.getOrElse { "ERR" }
          traceLines.add("CFG: ATST20=$atstRes | ATH1=$ath1Res | ATCM700=$cmRes | ATCF700=$cfRes")

          var consecutiveTimeouts = 0

          suspend fun probeRawHeadered(
            txHeader: String,
            cmd: String,
            categoryHint: String,
            is29Bit: Boolean = false,
            recordAsResponsive: Boolean = true,
            traceTag: String? = null,
          ): Boolean {
            _bcmScanState.update {
              it.copy(
                currentStepLabel = traceTag ?: "CAN ${if (is29Bit) "29b" else "11b"} · $txHeader -> $cmd",
                completedProbes = completedCount,
                progressFraction = (completedCount.toFloat() / totalSteps.coerceAtLeast(1)).coerceIn(0f, 0.96f),
                protocolName = resolvedProtocol,
              )
            }
            completedCount++
            return try {
              if (txHeader != activeTxHeader) {
                bluetoothClient.sendCommand("ATSH$txHeader", timeoutMs = 1400L)
                activeTxHeader = txHeader
              }
              val raw = bluetoothClient.sendCommand(cmd, timeoutMs = 1500L)
              consecutiveTimeouts = 0
              val cleanRaw = raw.replace("\n", " | ").trim()
              if (cleanRaw.contains("NO DATA", ignoreCase = true) || cleanRaw.isEmpty()) {
                false
              } else {
                val (rxHeader, bytes) =
                  D16aaPidCatalog.parseHeaderedElmResponse(raw, cmd, is29Bit = is29Bit)
                if (rxHeader.isNotBlank() && recordAsResponsive && !is29Bit) {
                  responsiveTxHeaders.putIfAbsent(txHeader, rxHeader)
                }
                if (bytes.isNotEmpty()) {
                  val tpmsCandidate =
                    if (txHeader != "7E0" && txHeader != "18DA10F1") D16aaPidCatalog.detectTpmsCandidate(bytes)
                    else null
                  val effectiveRx = if (!is29Bit && rxHeader.length == 3) rxHeader else ""
                  foundEntries.add(
                    BcmScanEntry(
                      txHeader = txHeader,
                      rxFilter = effectiveRx,
                      command = cmd,
                      rawResponse = raw,
                      parsedBytes = bytes,
                      categoryHint = categoryHint,
                      tpmsCandidate = tpmsCandidate,
                    )
                  )
                  val hex = bytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }
                  val prefix = traceTag?.let { "[$it] " } ?: ""
                  traceLines.add("[OK] $prefix$txHeader (RX=$rxHeader) $cmd -> $hex")
                  _bcmScanState.update { it.copy(foundEntries = foundEntries.toList()) }
                } else {
                  val prefix = traceTag?.let { "[$it] " } ?: ""
                  traceLines.add("[ECU!] $prefix$txHeader (RX=$rxHeader) $cmd -> $cleanRaw")
                }
                true
              }
            } catch (e: Exception) {
              consecutiveTimeouts++
              traceLines.add("[ERR] $txHeader $cmd -> ${e.javaClass.simpleName}: ${e.message ?: "timeout"}")
              if (consecutiveTimeouts >= 3) {
                abortedError =
                  "L'adattatore ELM327 non risponde (3 timeout consecutivi). Controlla che il quadro sia acceso."
              }
              false
            }
          }

          // Stage 0: Sanity Check + Engine ECU 7E0 (con 0902 e sessione estesa 1003)
          probeRawHeadered("7E0", "010C", "Sanity Check 7E0", recordAsResponsive = false)
          if (abortedError == null) {
            val engineCmds =
              listOf("0902", "1003", "22F190", "22F187", "221001", "22115B", "222004", "2101", "2110", "1001")
            for (cmd in engineCmds) {
              if (abortedError != null) break
              probeRawHeadered("7E0", cmd, "ECU Motore D16AA", recordAsResponsive = false)
            }
          }

          // Stage 1: Functional Broadcast 7DF (riceve eventuali risposte da QUALSIASI centralina 7xx)
          if (abortedError == null) {
            val broadcastCmds = listOf("1003", "1001", "2101", "22F190", "3E00", "0902")
            for (cmd in broadcastCmds) {
              if (abortedError != null) break
              probeRawHeadered("7DF", cmd, "Broadcast 7DF", recordAsResponsive = false)
            }
          }

          // Stage 2: Sweep fisico 11-bit su tutti gli header Suzuki/FCA (inclusi 7E1..7E7 e 7D1..7D7)
          for ((txHeader, labelHint) in stage1Headers) {
            if (abortedError != null) break
            var gotAny = false
            if (txHeader in PRIORITY_SUZUKI_HEADERS) {
              if (probeRawHeadered(txHeader, "1003", labelHint)) gotAny = true
              if (abortedError != null) break
            }
            if (probeRawHeadered(txHeader, "2101", labelHint)) gotAny = true
            if (abortedError != null) break
            if (probeRawHeadered(txHeader, "22F190", labelHint)) gotAny = true
            if (!gotAny) {
              noDataHeaders.add(txHeader)
            }
          }

          // Stage 3: Scansione approfondita sui soli moduli 11-bit che hanno risposto nello Stage 2
          if (abortedError == null && responsiveTxHeaders.isNotEmpty()) {
            val deepCmds =
              listOf(
                "1001", "1003", "1081",
                "2101", "2102", "2103", "2104", "2105", "2106", "2110", "2120",
                "221001", "222000", "22C000", "22F187", "22F190",
              )
            for ((txHeader, rxHeader) in responsiveTxHeaders) {
              for (cmd in deepCmds) {
                if (abortedError != null) break
                probeRawHeadered(txHeader, cmd, "Modulo $txHeader (RX $rxHeader)", recordAsResponsive = false)
              }
            }
          }

          // Stage 4: Probe ATCEA (ISO 15765-2 CAN Extended Addressing per moduli Suzuki)
          if (abortedError == null) {
            val ceaTest = runCatching { bluetoothClient.sendCommand("ATCEA01", timeoutMs = 1200L) }.getOrElse { "ERR" }
            if (ceaTest.contains("OK", ignoreCase = true)) {
              var ceaResponsive = 0
              val broadcastCeaAddrs = listOf("01", "10", "11", "20", "28", "40", "50", "51", "60", "F1")
              for (addr in broadcastCeaAddrs) {
                if (abortedError != null) break
                runCatching { bluetoothClient.sendCommand("ATCEA$addr", timeoutMs = 1000L) }
                if (probeRawHeadered("7DF", "2101", "ATCEA $addr (7DF)", recordAsResponsive = false, traceTag = "ATCEA:$addr")) {
                  ceaResponsive++
                }
              }
              for (addr in listOf("01", "40", "51", "F1")) {
                if (abortedError != null) break
                runCatching { bluetoothClient.sendCommand("ATCEA$addr", timeoutMs = 1000L) }
                if (probeRawHeadered("751", "2101", "ATCEA $addr (751)", recordAsResponsive = false, traceTag = "ATCEA:$addr")) {
                  ceaResponsive++
                }
              }
              runCatching { bluetoothClient.sendCommand("ATCEA", timeoutMs = 1000L) }
              traceLines.add("ATCEA: probe completati (14 target) -> risposte=$ceaResponsive")
            } else {
              runCatching { bluetoothClient.sendCommand("ATCEA", timeoutMs = 1000L) }
              traceLines.add("ATCEA: non supportato ($ceaTest)")
            }
          }

          // Stage 5: Verifica rapida 29-bit CAN 500kbps (ATSP7) per centraline FCA/Suzuki estese
          if (abortedError == null) {
            runCatching { bluetoothClient.sendCommand("ATSP7", timeoutMs = 1200L) }
            runCatching { bluetoothClient.sendCommand("ATAR", timeoutMs = 1200L) }
            activeRxFilter = ""
            val probes29 =
              listOf(
                Triple("18DB33F1", "Broadcast 29-bit", "0100"),
                Triple("18DB33F1", "Broadcast 29-bit", "3E00"),
                Triple("18DA10F1", "EDC17 29-bit", "1001"),
                Triple("18DA10F1", "EDC17 29-bit", "22F190"),
                Triple("18DA40F1", "BCM 29-bit", "1001"),
                Triple("18DA40F1", "BCM 29-bit", "22F190"),
                Triple("18DA40F1", "BCM 29-bit", "2101"),
              )
            for ((tx29, hint29, cmd29) in probes29) {
              if (abortedError != null) break
              probeRawHeadered(tx29, cmd29, hint29, is29Bit = true, recordAsResponsive = false)
            }
          }

          // Stage 6: Sniff CAN passivo breve (ATMA 220ms, filtro 000 aperto) per distinguere bus aperto vs OBD Gateway
          if (abortedError == null) {
            _bcmScanState.update {
              it.copy(
                currentStepLabel = "Ascolto passivo bus CAN (220ms)...",
                completedProbes = completedCount,
                progressFraction = 0.97f,
              )
            }
            completedCount++
            runCatching { bluetoothClient.sendCommand("ATSP6", timeoutMs = 1200L) }
            runCatching { bluetoothClient.sendCommand("ATH1", timeoutMs = 1000L) }
            runCatching { bluetoothClient.sendCommand("ATCM000", timeoutMs = 1000L) }
            runCatching { bluetoothClient.sendCommand("ATCF000", timeoutMs = 1000L) }
            val capture =
              runCatching { bluetoothClient.monitorCanBriefly(durationMs = 220L, maxLines = 35) }
                .getOrDefault(BluetoothSppClient.CanMonitorCapture(emptyList(), false, false))
            val passiveLines = capture.lines
            val summaries = D16aaPidCatalog.summarizePassiveCanFrames(passiveLines)
            val bufNote = if (capture.hitBufferFull) " [BUFFER FULL]" else ""
            if (summaries.isEmpty()) {
              val rawSample = passiveLines.take(3).joinToString(" | ")
              traceLines.add(
                if (rawSample.isBlank()) "[CAN_PASSIVE] 0 frame passivi$bufNote (presa OBD isolata dietro gateway o solo diagnostica)"
                else "[CAN_PASSIVE] ${passiveLines.size} righe non-11b$bufNote: $rawSample"
              )
            } else {
              val summaryText =
                summaries.take(18).joinToString(" | ") { s ->
                  val varTag = if (s.distinctPayloadsCount > 1) ",v=${s.distinctPayloadsCount}" else ""
                  "${s.canId}(${s.frameCount}$varTag)=${s.sampleHex}"
                }
              traceLines.add("[CAN_PASSIVE] ${passiveLines.size} frame$bufNote, ${summaries.size} ID: $summaryText")
              summaries.filter { it.tpmsCandidate != null }.forEach { s ->
                val cand = s.tpmsCandidate!!
                traceLines.add("[TPMS_PASSIVE?] ID=${s.canId} -> ${s.sampleHex} (${cand.scaleLabel})")
                foundEntries.add(
                  BcmScanEntry(
                    txHeader = s.canId,
                    rxFilter = s.canId,
                    command = "PASSIVE",
                    rawResponse = s.sampleHex,
                    parsedBytes = s.sampleBytes,
                    categoryHint = "Broadcast CAN passivo ${s.canId}",
                    tpmsCandidate = cand,
                  )
                )
              }
            }
          }
        }
      } catch (e: Exception) {
        abortedError = e.message ?: "Errore durante la scansione CAN"
      } finally {
        if (noDataHeaders.isNotEmpty()) {
          traceLines.add("[NO DATA 11b]: ${noDataHeaders.joinToString(",")}")
        }
        runCatching { bluetoothClient.sendCommand("ATCEA", timeoutMs = 1000L) }
        runCatching { bluetoothClient.sendCommand("ATSP0", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATH0", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATAR", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATST32", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATSH$DEFAULT_OBD_HEADER", timeoutMs = 1200L) }
        activeTxHeader = DEFAULT_OBD_HEADER
        activeRxFilter = ""
        isExclusiveOperation.value = false
        _bcmScanState.update {
          it.copy(
            isScanning = false,
            currentStepLabel =
              abortedError
                ?: if (foundEntries.isEmpty()) "Scansione completata · nessuna risposta positiva"
                else "Scansione completata · ${foundEntries.size} risposte trovate",
            progressFraction = 1f,
            completedProbes = completedCount,
            totalProbes = completedCount.coerceAtLeast(1),
            protocolName = resolvedProtocol,
            foundEntries = foundEntries.toList(),
            scanTrace = traceLines.toList(),
            errorMessage = abortedError,
            hasRun = true,
          )
        }
      }
    }

  suspend fun sniffTpmsMenuTraffic() =
    withContext(Dispatchers.IO) {
      val conn = _connectionState.value
      val snapshot = _supportedPids.value
      if (conn is ElmConnectionState.DemoMode) {
        _bcmScanState.value =
          BcmScanState(
            isScanning = false,
            hasRun = true,
            ecuInfo = snapshot.ecuName,
            protocolName = snapshot.protocolName,
            errorMessage = "Modalità Demo attiva: collegati all'ELM327 in auto per intercettare il menu TPMS.",
          )
        return@withContext
      }
      if (conn !is ElmConnectionState.Connected) {
        _bcmScanState.value =
          BcmScanState(
            isScanning = false,
            hasRun = true,
            errorMessage = "Collega prima l'adattatore ELM327 via Bluetooth.",
          )
        return@withContext
      }

      val foundEntries = mutableListOf<BcmScanEntry>()
      val traceLines = mutableListOf<String>()
      var resolvedProtocol = snapshot.protocolName
      val totalPhases = 28
      var completedSteps = 0

      _bcmScanState.value =
        BcmScanState(
          isScanning = true,
          currentStepLabel = "Preparazione intercettazione menu TPMS...",
          progressFraction = 0f,
          totalProbes = totalPhases,
          completedProbes = 0,
          ecuInfo = snapshot.ecuName,
          protocolName = snapshot.protocolName,
          foundEntries = emptyList(),
          scanTrace = emptyList(),
          errorMessage = null,
          hasRun = true,
        )

      isExclusiveOperation.value = true
      var abortedError: String? = null
      try {
        exclusiveOperationMutex.withLock {
          val ati = runCatching { bluetoothClient.sendCommand("ATI", timeoutMs = 1200L) }.getOrElse { "ERR" }.replace("\n", " ").trim()
          val at1 = runCatching { bluetoothClient.sendCommand("AT@1", timeoutMs = 1200L) }.getOrElse { "ERR" }.replace("\n", " ").trim()
          val dp = runCatching { bluetoothClient.sendCommand("ATDP", timeoutMs = 1500L) }.getOrElse { "ERR" }
          val dpn = runCatching { bluetoothClient.sendCommand("ATDPN", timeoutMs = 1500L) }.getOrElse { "ERR" }
          val v = runCatching { bluetoothClient.sendCommand("ATRV", timeoutMs = 1500L) }.getOrElse { "ERR" }
          if (!dp.startsWith("ERR") && dp.isNotBlank()) {
            resolvedProtocol = dp
            _supportedPids.update { it.copy(protocolName = dp) }
          }
          traceLines.add("SNIFF_MODE: Intercettazione Menu TPMS (Auto ferma)")
          traceLines.add("ELM: ATI=$ati | AT@1=$at1 | ATDP=$dp | ATDPN=$dpn | ATRV=$v")

          runCatching { bluetoothClient.sendCommand("ATSP6", timeoutMs = 1200L) }
          runCatching { bluetoothClient.sendCommand("ATH1", timeoutMs = 1000L) }

          // Fase 1 (3.5s): ascolta eventuale dialogo diagnostico 700..7FF mentre l'utente apre il menu TPMS
          _bcmScanState.update {
            it.copy(
              currentStepLabel = "Fase 1/4: Apri ora il menu TPMS con l'astina (ascolto 700..7FF, 3.5s)...",
              completedProbes = 1,
              progressFraction = 0.15f,
            )
          }
          runCatching { bluetoothClient.sendCommand("ATCM700", timeoutMs = 1000L) }
          runCatching { bluetoothClient.sendCommand("ATCF700", timeoutMs = 1000L) }
          val cap7xx =
            runCatching { bluetoothClient.monitorCanBriefly(durationMs = 3500L, maxLines = 150, maxBytes = 12000) }
              .getOrDefault(BluetoothSppClient.CanMonitorCapture(emptyList(), false, false))
          val sum7xx = D16aaPidCatalog.summarizePassiveCanFrames(cap7xx.lines)
          traceLines.add(
            "[SNIFF_7XX] ${cap7xx.lines.size} frame (bufFull=${cap7xx.hitBufferFull}, timeout=${cap7xx.timedOutPrompt}), ${sum7xx.size} ID"
          )
          if (cap7xx.lines.isNotEmpty()) {
            traceLines.add("[SNIFF_7XX_RAW] ${cap7xx.lines.take(25).joinToString(" | ")}")
          }
          sum7xx.forEach { s ->
            if (s.tpmsCandidate != null) {
              traceLines.add("[TPMS_7XX?] ID=${s.canId} -> ${s.sampleHex} (${s.tpmsCandidate.scaleLabel})")
            }
            foundEntries.add(
              BcmScanEntry(
                txHeader = s.canId,
                rxFilter = s.canId,
                command = "PASSIVE",
                rawResponse = s.sampleHex,
                parsedBytes = s.sampleBytes,
                categoryHint = "Sniff 7xx (${s.frameCount}x)",
                tpmsCandidate = s.tpmsCandidate,
              )
            )
          }

          // Fase 2 (2.0s): ascolta ID body/cluster/TPMS 400..7FF (filtra 0xx..3xx ad alta frequenza)
          _bcmScanState.update {
            it.copy(
              currentStepLabel = "Fase 2/4: Tieni aperto TPMS (ascolto body 400..7FF, 2s)...",
              completedProbes = 2,
              progressFraction = 0.40f,
            )
          }
          runCatching { bluetoothClient.sendCommand("ATCM400", timeoutMs = 1000L) }
          runCatching { bluetoothClient.sendCommand("ATCF400", timeoutMs = 1000L) }
          val cap4xx =
            runCatching { bluetoothClient.monitorCanBriefly(durationMs = 2000L, maxLines = 120, maxBytes = 10000) }
              .getOrDefault(BluetoothSppClient.CanMonitorCapture(emptyList(), false, false))
          val sum4xx = D16aaPidCatalog.summarizePassiveCanFrames(cap4xx.lines)
          val text4xx =
            sum4xx.take(18).joinToString(" | ") { s ->
              val varTag = if (s.distinctPayloadsCount > 1) ",v=${s.distinctPayloadsCount}" else ""
              "${s.canId}(${s.frameCount}$varTag)=${s.sampleHex}"
            }
          traceLines.add(
            "[SNIFF_4XX] ${cap4xx.lines.size} frame (bufFull=${cap4xx.hitBufferFull}), ${sum4xx.size} ID" +
              if (text4xx.isNotEmpty()) ": $text4xx" else ""
          )
          sum4xx.filter { it.tpmsCandidate != null }.forEach { s ->
            val cand = s.tpmsCandidate!!
            traceLines.add("[TPMS_4XX?] ID=${s.canId} -> ${s.sampleHex} (${cand.scaleLabel})")
            foundEntries.add(
              BcmScanEntry(
                txHeader = s.canId,
                rxFilter = s.canId,
                command = "PASSIVE",
                rawResponse = s.sampleHex,
                parsedBytes = s.sampleBytes,
                categoryHint = "Sniff 400..7FF ${s.canId}",
                tpmsCandidate = cand,
              )
            )
          }

          // Fase 3 (250ms): raffica bus completo 000..7FF (cattura anche 1xx/2xx/3xx)
          _bcmScanState.update {
            it.copy(
              currentStepLabel = "Fase 3/4: Raffica bus completo 000..7FF (250ms)...",
              completedProbes = 3,
              progressFraction = 0.58f,
            )
          }
          runCatching { bluetoothClient.sendCommand("ATCM000", timeoutMs = 1000L) }
          runCatching { bluetoothClient.sendCommand("ATCF000", timeoutMs = 1000L) }
          val capAll =
            runCatching { bluetoothClient.monitorCanBriefly(durationMs = 250L, maxLines = 60, maxBytes = 6000) }
              .getOrDefault(BluetoothSppClient.CanMonitorCapture(emptyList(), false, false))
          val sumAll = D16aaPidCatalog.summarizePassiveCanFrames(capAll.lines)
          val textAll =
            sumAll.take(18).joinToString(" | ") { s ->
              val varTag = if (s.distinctPayloadsCount > 1) ",v=${s.distinctPayloadsCount}" else ""
              "${s.canId}(${s.frameCount}$varTag)=${s.sampleHex}"
            }
          traceLines.add(
            "[SNIFF_ALL] ${capAll.lines.size} frame (bufFull=${capAll.hitBufferFull}), ${sumAll.size} ID" +
              if (textAll.isNotEmpty()) ": $textAll" else ""
          )
          sumAll.filter { it.tpmsCandidate != null }.forEach { s ->
            val cand = s.tpmsCandidate!!
            traceLines.add("[TPMS_ALL?] ID=${s.canId} -> ${s.sampleHex} (${cand.scaleLabel})")
            if (foundEntries.none { it.txHeader == s.canId && it.command == "PASSIVE" }) {
              foundEntries.add(
                BcmScanEntry(
                  txHeader = s.canId,
                  rxFilter = s.canId,
                  command = "PASSIVE",
                  rawResponse = s.sampleHex,
                  parsedBytes = s.sampleBytes,
                  categoryHint = "Sniff CAN ${s.canId}",
                  tpmsCandidate = cand,
                )
              )
            }
          }

          // Fase 4: probe attivi sui moduli principali mentre il menu TPMS e ancora aperto sul cruscotto
          completedSteps = 4
          runCatching { bluetoothClient.sendCommand("ATST20", timeoutMs = 1000L) }
          runCatching { bluetoothClient.sendCommand("ATCM700", timeoutMs = 1000L) }
          runCatching { bluetoothClient.sendCommand("ATCF700", timeoutMs = 1000L) }
          val awakeHeaders = listOf("7DF", "751", "7A0", "754", "7C0", "743", "7E0", "7E1")
          val awakeCmds = listOf("1003", "2101", "22F190")
          for (hdr in awakeHeaders) {
            if (abortedError != null) break
            runCatching {
              if (hdr != activeTxHeader) {
                bluetoothClient.sendCommand("ATSH$hdr", timeoutMs = 1200L)
                activeTxHeader = hdr
              }
            }
            for (cmd in awakeCmds) {
              if (abortedError != null) break
              _bcmScanState.update {
                it.copy(
                  currentStepLabel = "Fase 4/4: Test modulo attivo $hdr -> $cmd",
                  completedProbes = completedSteps,
                  progressFraction = (0.60f + (completedSteps - 4).toFloat() / 24f * 0.36f).coerceIn(0.6f, 0.96f),
                )
              }
              completedSteps++
              try {
                val raw = bluetoothClient.sendCommand(cmd, timeoutMs = 1400L)
                val cleanRaw = raw.replace("\n", " | ").trim()
                if (!cleanRaw.contains("NO DATA", ignoreCase = true) && cleanRaw.isNotEmpty()) {
                  val (rxHeader, bytes) = D16aaPidCatalog.parseHeaderedElmResponse(raw, cmd, is29Bit = false)
                  if (bytes.isNotEmpty()) {
                    val tpmsCand = if (hdr != "7E0") D16aaPidCatalog.detectTpmsCandidate(bytes) else null
                    foundEntries.add(
                      BcmScanEntry(
                        txHeader = hdr,
                        rxFilter = rxHeader.takeIf { it.length == 3 }.orEmpty(),
                        command = cmd,
                        rawResponse = raw,
                        parsedBytes = bytes,
                        categoryHint = "Probe menu TPMS ($hdr)",
                        tpmsCandidate = tpmsCand,
                      )
                    )
                    val hex = bytes.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }
                    traceLines.add("[AWAKE_OK] $hdr (RX=$rxHeader) $cmd -> $hex")
                  } else {
                    traceLines.add("[AWAKE_ECU!] $hdr (RX=$rxHeader) $cmd -> $cleanRaw")
                  }
                }
              } catch (e: Exception) {
                traceLines.add("[AWAKE_ERR] $hdr $cmd -> ${e.message ?: "timeout"}")
              }
            }
          }
          _bcmScanState.update { it.copy(foundEntries = foundEntries.toList()) }
        }
      } catch (e: Exception) {
        abortedError = e.message ?: "Errore durante l'intercettazione menu TPMS"
      } finally {
        runCatching { bluetoothClient.sendCommand("ATCEA", timeoutMs = 1000L) }
        runCatching { bluetoothClient.sendCommand("ATSP0", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATH0", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATAR", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATST32", timeoutMs = 1200L) }
        runCatching { bluetoothClient.sendCommand("ATSH$DEFAULT_OBD_HEADER", timeoutMs = 1200L) }
        activeTxHeader = DEFAULT_OBD_HEADER
        activeRxFilter = ""
        isExclusiveOperation.value = false
        _bcmScanState.update {
          it.copy(
            isScanning = false,
            currentStepLabel =
              abortedError
                ?: if (foundEntries.isEmpty()) "Intercettazione completata · 0 pacchetti TPMS visti"
                else "Intercettazione completata · ${foundEntries.size} rilevazioni",
            progressFraction = 1f,
            completedProbes = completedSteps.coerceAtLeast(4),
            totalProbes = totalPhases,
            protocolName = resolvedProtocol,
            foundEntries = foundEntries.toList(),
            scanTrace = traceLines.toList(),
            errorMessage = abortedError,
            hasRun = true,
          )
        }
      }
    }

  private fun buildStage1Headers(): List<Pair<String, String>> =
    listOf(
      "751" to "BCM / TPMS (751)",
      "7A0" to "TPMS / Telaio (7A0)",
      "743" to "Modulo Suzuki (743)",
      "750" to "BCM Alt (750)",
      "752" to "Modulo Suzuki (752)",
      "753" to "Modulo Suzuki (753)",
      "754" to "TPMS / Body (754)",
      "755" to "Modulo Suzuki (755)",
      "756" to "Modulo Suzuki (756)",
      "757" to "Modulo Suzuki (757)",
      "760" to "ABS / Chassis (760)",
      "761" to "Modulo 761",
      "762" to "Modulo 762",
      "763" to "Modulo 763",
      "764" to "Modulo 764",
      "765" to "Modulo 765",
      "740" to "Modulo 740",
      "741" to "Modulo 741",
      "742" to "Modulo 742",
      "744" to "Modulo 744",
      "745" to "Modulo 745",
      "746" to "Modulo 746",
      "747" to "Modulo 747",
      "7A1" to "Modulo 7A1",
      "7A2" to "Modulo 7A2",
      "7A3" to "Modulo 7A3",
      "7A4" to "Modulo 7A4",
      "7A5" to "Modulo 7A5",
      "7B0" to "ABS / ESP (7B0)",
      "7B1" to "Modulo 7B1",
      "7B2" to "Modulo 7B2",
      "7C0" to "Quadro Strumenti (7C0)",
      "7C1" to "Modulo 7C1",
      "7C2" to "Modulo 7C2",
      "7C3" to "Modulo 7C3",
      "7C4" to "Modulo 7C4",
      "7C5" to "Modulo 7C5",
      "7D0" to "Modulo 7D0",
      "7D1" to "Modulo 7D1",
      "7D2" to "Modulo 7D2",
      "7D3" to "Modulo 7D3",
      "7D4" to "Modulo 7D4",
      "7D5" to "Modulo 7D5",
      "7D6" to "Modulo 7D6",
      "7D7" to "Modulo 7D7",
      "7E1" to "TCM / Gateway (7E1)",
      "7E2" to "Modulo 7E2",
      "7E3" to "Modulo 7E3",
      "7E4" to "Modulo 7E4",
      "7E5" to "Modulo 7E5",
      "7E6" to "Modulo 7E6",
      "7E7" to "Modulo 7E7",
      "710" to "Modulo 710",
      "720" to "Modulo 720",
      "726" to "BCM Alt (726)",
      "730" to "Modulo 730",
      "770" to "Modulo 770",
      "772" to "Modulo 772",
      "780" to "Modulo 780",
      "790" to "Modulo 790",
    )

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

    val resolvedProtocol =
      runCatching { bluetoothClient.sendCommand("ATDP", timeoutMs = 1500L) }
        .getOrDefault("")
        .ifBlank { "ISO 15765-4 CAN" }

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
      protocolName = resolvedProtocol,
      discoveryCompleted = true,
    )
  }

  private suspend fun runLivePollingLoop() {
    var cycle = 0
    while (scope.isActive && _connectionState.value is ElmConnectionState.Connected) {
      if (isExclusiveOperation.value) {
        delay(150L)
        continue
      }

      val targets = activeDefinitions.toList()
      val supportedSet = _supportedPids.value.supportedCommands

      if (targets.isEmpty()) {
        delay(500L)
        continue
      }

      for (def in targets) {
        if (!scope.isActive || _connectionState.value !is ElmConnectionState.Connected) break
        if (isExclusiveOperation.value) break

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
          exclusiveOperationMutex.withLock {
            if (isExclusiveOperation.value) return@withLock
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

  private suspend fun switchHeaderIfNeeded(targetHeaderSpec: String) {
    val normalized = targetHeaderSpec.uppercase(Locale.ROOT).trim().ifBlank { DEFAULT_OBD_HEADER }
    val (txHeaderRaw, rxFilter) = D16aaPidCatalog.parseHeaderSpec(normalized)
    val txHeader = txHeaderRaw.ifBlank { DEFAULT_OBD_HEADER }

    if (txHeader != activeTxHeader) {
      bluetoothClient.sendCommand("ATSH$txHeader", timeoutMs = 1500L)
      activeTxHeader = txHeader
    }
    if (rxFilter != activeRxFilter) {
      if (rxFilter.isNotBlank()) {
        bluetoothClient.sendCommand("ATCRA$rxFilter", timeoutMs = 1500L)
      } else {
        bluetoothClient.sendCommand("ATAR", timeoutMs = 1500L)
      }
      activeRxFilter = rxFilter
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
    val PRIORITY_SUZUKI_HEADERS =
      setOf("751", "7A0", "743", "750", "752", "753", "754", "760", "7B0", "7C0", "726", "7E1", "7E2")
  }
}
