package com.example.data.obd

import android.content.Context
import android.util.Log
import com.example.data.model.ActiveTripUiState
import com.example.data.model.AvailabilityState
import com.example.data.model.ElmConnectionState
import com.example.data.model.TripLogEntry
import com.example.data.model.TripTimelinePoint
import com.example.data.repository.TripLogRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TripLogManager(
  context: Context,
  private val telemetryRepository: Elm327TelemetryRepository,
) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sessionMutex = Mutex()

  private val _autoLogEnabled =
    MutableStateFlow(prefs.getBoolean(KEY_AUTO_LOG_ENABLED, true))
  val autoLogEnabled: StateFlow<Boolean> = _autoLogEnabled.asStateFlow()

  private val _activeTripState = MutableStateFlow(ActiveTripUiState())
  val activeTripState: StateFlow<ActiveTripUiState> = _activeTripState.asStateFlow()

  private val _lastFeedbackMessage = MutableStateFlow<String?>(null)
  val lastFeedbackMessage: StateFlow<String?> = _lastFeedbackMessage.asStateFlow()

  private var currentUserId: String? = null
  private var tripLogRepository: TripLogRepository? = null

  private var recordingJob: Job? = null
  private var activeSession: MutableTripSession? = null

  private class MutableTripSession(
    var tripId: String? = null,
    val title: String,
    val startedAtMillis: Long,
    val csvFileName: String,
    val csvFile: File,
    val demoMode: Boolean,
    var lastTickMillis: Long = startedAtMillis,
    var lastSpeedKmh: Double? = null,
    var distanceKm: Double = 0.0,
    var sampleCount: Long = 0L,
    var speedSum: Double = 0.0,
    var speedSamples: Long = 0L,
    var maxSpeedKmh: Double = 0.0,
    var maxRpm: Double = 0.0,
    var maxCoolantC: Double = -999.0,
    var hasCoolantSample: Boolean = false,
    var maxDpfTempC: Double = -999.0,
    var hasDpfSample: Boolean = false,
    var minBatteryV: Double = 99.0,
    var maxBatteryV: Double = 0.0,
    var batterySum: Double = 0.0,
    var batterySamples: Long = 0L,
    var maxTurboKpa: Double = 0.0,
    var estimatedFuelLiters: Double = 0.0,
    var fuelLphSum: Double = 0.0,
    var fuelSamples: Long = 0L,
    var trafficSecondsAcc: Double = 0.0,
    var dpfRegenSecondsAcc: Double = 0.0,
    var dpfRegenDetected: Boolean = false,
    var lastCloudSyncMillis: Long = 0L,
    val timelineBuffer: MutableList<TripTimelinePoint> = mutableListOf(),
  ) {
    fun toEntry(userId: String, status: String, nowMillis: Long): TripLogEntry {
      val elapsedSec = ((nowMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
      val avgSpeed = if (speedSamples > 0L) speedSum / speedSamples.toDouble() else 0.0
      val avgBat = if (batterySamples > 0L) batterySum / batterySamples.toDouble() else 0.0
      val minBat = if (batterySamples > 0L) minBatteryV else 0.0
      val safeDistanceKm = distanceKm.coerceAtLeast(0.0)
      val avgLph = if (fuelSamples > 0L) fuelLphSum / fuelSamples.toDouble() else 0.0
      val avgL100 =
        if (safeDistanceKm >= 0.15 && estimatedFuelLiters > 0.0) {
          ((estimatedFuelLiters / safeDistanceKm) * 100.0).coerceIn(0.0, 40.0)
        } else {
          0.0
        }
      return TripLogEntry(
        id = tripId.orEmpty(),
        userId = userId,
        title = title,
        startedAtMillis = startedAtMillis,
        endedAtMillis = if (status == "COMPLETED") nowMillis else 0L,
        durationSec = elapsedSec,
        sampleCount = sampleCount,
        distanceKm = safeDistanceKm,
        avgSpeedKmh = avgSpeed.coerceAtLeast(0.0),
        maxSpeedKmh = maxSpeedKmh.coerceAtLeast(0.0),
        maxRpm = maxRpm.coerceAtLeast(0.0),
        maxCoolantC = if (hasCoolantSample) maxCoolantC else 0.0,
        maxDpfTempC = if (hasDpfSample) maxDpfTempC else 0.0,
        minBatteryV = minBat.coerceAtLeast(0.0),
        maxBatteryV = maxBatteryV.coerceAtLeast(0.0),
        avgBatteryV = avgBat.coerceAtLeast(0.0),
        maxTurboKpa = maxTurboKpa.coerceAtLeast(0.0),
        estimatedFuelLiters = estimatedFuelLiters.coerceAtLeast(0.0),
        avgLitersPer100Km = avgL100,
        avgLitersPerHour = avgLph.coerceAtLeast(0.0),
        trafficSeconds = trafficSecondsAcc.toLong().coerceAtLeast(0L),
        dpfRegenDetected = dpfRegenDetected,
        dpfRegenSeconds = dpfRegenSecondsAcc.toLong().coerceAtLeast(0L),
        csvFileName = csvFileName,
        status = status,
        demoMode = demoMode,
        timelinePoints = TripCsvStorage.downsampleTimeline(timelineBuffer.toList(), 60),
      )
    }
  }

  init {
    scope.launch {
      var previousState: ElmConnectionState = telemetryRepository.connectionState.value
      telemetryRepository.connectionState.collect { newState ->
        val wasConnected =
          previousState is ElmConnectionState.Connected ||
            previousState is ElmConnectionState.DemoMode
        val isNowRealConnected = newState is ElmConnectionState.Connected
        val isNowStopped =
          newState is ElmConnectionState.Disconnected || newState is ElmConnectionState.Error

        if (isNowRealConnected && previousState !is ElmConnectionState.Connected && _autoLogEnabled.value) {
          startTrip(demoMode = false)
        } else if (wasConnected && isNowStopped && activeSession != null) {
          stopTrip(reasonMessage = "Connessione chiusa: tragitto salvato.")
        }
        previousState = newState
      }
    }
  }

  fun attachSession(userId: String, repository: TripLogRepository) {
    currentUserId = userId
    tripLogRepository = repository
  }

  suspend fun detachAndFlushBeforeSignOut() {
    withContext(NonCancellable) {
      if (activeSession != null) {
        stopTrip(reasonMessage = null)
      }
      currentUserId = null
      tripLogRepository = null
    }
  }

  fun clearFeedbackMessage() {
    _lastFeedbackMessage.value = null
  }

  fun setAutoLogEnabled(enabled: Boolean) {
    _autoLogEnabled.value = enabled
    prefs.edit().putBoolean(KEY_AUTO_LOG_ENABLED, enabled).apply()
  }

  fun startManualTrip() {
    val conn = telemetryRepository.connectionState.value
    when (conn) {
      is ElmConnectionState.Connected -> scope.launch { startTrip(demoMode = false) }
      is ElmConnectionState.DemoMode -> scope.launch { startTrip(demoMode = true) }
      else -> {
        _lastFeedbackMessage.value =
          "Collega l'ELM327 (o avvia il Simulatore Demo) prima di iniziare il log."
      }
    }
  }

  fun stopTripAsync() {
    scope.launch {
      stopTrip(reasonMessage = null)
    }
  }

  suspend fun startTrip(demoMode: Boolean) {
    sessionMutex.withLock {
      if (activeSession != null) return

      val startedAt = System.currentTimeMillis()
      val csvName = TripCsvStorage.newCsvFileName(startedAt)
      val csvFile =
        runCatching { TripCsvStorage.createCsvFile(appContext, csvName) }
          .onFailure { Log.w(TAG, "Failed creating CSV file", it) }
          .getOrNull() ?: return

      val title = TripCsvStorage.defaultCommuteTitle(startedAt, demoMode)
      val session =
        MutableTripSession(
          title = title,
          startedAtMillis = startedAt,
          csvFileName = csvName,
          csvFile = csvFile,
          demoMode = demoMode,
        )
      activeSession = session
      telemetryRepository.setCommuteCorePidsRequired(true)

      _activeTripState.value =
        ActiveTripUiState(
          isRecording = true,
          tripId = null,
          title = title,
          startedAtMillis = startedAt,
          elapsedSec = 0L,
          sampleCount = 0L,
          distanceKm = 0.0,
          demoMode = demoMode,
          csvFileName = csvName,
        )

      if (!demoMode && telemetryRepository.bluetoothClient.hasConnectPermission()) {
        TelemetryLoggingService.startOrUpdate(appContext, 0L, 0.0, 0L)
      }

      recordingJob?.cancel()
      recordingJob =
        scope.launch {
          while (isActive) {
            recordTick(session)
            delay(TICK_INTERVAL_MS)
          }
        }
    }
  }

  suspend fun stopTrip(reasonMessage: String? = null) {
    val finalizedResult =
      sessionMutex.withLock {
        val session = activeSession ?: return@withLock null
        recordingJob?.cancel()
        recordingJob = null
        activeSession = null
        telemetryRepository.setCommuteCorePidsRequired(false)
        TelemetryLoggingService.stop(appContext)
        _activeTripState.value = ActiveTripUiState()

        val now = System.currentTimeMillis()
        val uid = currentUserId
        val repo = tripLogRepository
        if (session.sampleCount == 0L) {
          TripCsvStorage.deleteCsvFile(appContext, session.csvFileName)
          return@withLock null
        }

        val entry = session.toEntry(uid.orEmpty(), "COMPLETED", now)
        if (uid != null && repo != null) {
          if (session.tripId.isNullOrBlank()) {
            repo.createTrip(uid, entry)
          } else {
            repo.updateTripCheckpoint(uid, entry)
          }
        }
        entry
      }

    if (finalizedResult != null) {
      val msg =
        reasonMessage
          ?: if (finalizedResult.avgLitersPer100Km > 0.0) {
            String.format(
              Locale.ITALY,
              "Tragitto salvato: %.1f km · %.1f L/100km · %d campioni CSV.",
              finalizedResult.distanceKm,
              finalizedResult.avgLitersPer100Km,
              finalizedResult.sampleCount,
            )
          } else {
            String.format(
              Locale.ITALY,
              "Tragitto salvato: %.1f km · %d campioni CSV.",
              finalizedResult.distanceKm,
              finalizedResult.sampleCount,
            )
          }
      _lastFeedbackMessage.value = msg
    }
  }

  private suspend fun recordTick(session: MutableTripSession) {
    val now = System.currentTimeMillis()
    val readings = telemetryRepository.readings.value
    val batterySummary = telemetryRepository.batterySummary.value

    fun readActive(key: String): Double? {
      val r = readings[key] ?: return null
      return if (r.availability == AvailabilityState.ACTIVE) r.value else null
    }

    val speed = readActive("SPEED")
    val rpm = readActive("RPM")
    val coolant = readActive("COOLANT_TEMP")
    val turbo = readActive("TURBO_MAP")
    val dpf = readActive("EXHAUST_DPF_TEMP")
    val rail = readActive("FUEL_RAIL")
    val load = readActive("ENGINE_LOAD")
    val maf = readActive("MAF")
    val iat = readActive("INTAKE_TEMP")
    val o2Lambda = readActive("O2_LAMBDA")
    val bat = readActive("BATTERY_ECU") ?: readActive("BATTERY_ATRV") ?: batterySummary.voltage

    val fuelLph =
      D16aaPidCatalog.estimateDieselFuelRateLph(
        mafGs = maf,
        lambdaEq = o2Lambda,
        engineLoadPct = load,
        rpm = rpm,
        speedKmh = speed,
        fuelRailBar = rail,
      )
    val fuelL100 =
      if (fuelLph != null && speed != null && speed > 5.0) {
        ((fuelLph / speed) * 100.0).coerceIn(0.0, 50.0)
      } else {
        null
      }

    val dtSec = ((now - session.lastTickMillis) / 1000.0).coerceIn(0.0, 5.0)
    session.lastTickMillis = now
    if (speed != null && speed >= 0.0) {
      val prevSpeed = session.lastSpeedKmh ?: speed
      val avgSegmentSpeed = (prevSpeed + speed) / 2.0
      if (dtSec > 0.05) {
        session.distanceKm += avgSegmentSpeed * (dtSec / 3600.0)
      }
      session.lastSpeedKmh = speed
      session.speedSum += speed
      session.speedSamples += 1L
      if (speed > session.maxSpeedKmh) session.maxSpeedKmh = speed

      if (dtSec > 0.05 && speed < QUEUE_SPEED_THRESHOLD_KMH && (rpm ?: 0.0) > 350.0) {
        session.trafficSecondsAcc += dtSec
      }
    }

    if (fuelLph != null) {
      if (dtSec > 0.05) {
        session.estimatedFuelLiters += fuelLph * (dtSec / 3600.0)
      }
      session.fuelLphSum += fuelLph
      session.fuelSamples += 1L
    }

    if (rpm != null && rpm > session.maxRpm) session.maxRpm = rpm
    if (coolant != null) {
      if (!session.hasCoolantSample || coolant > session.maxCoolantC) {
        session.maxCoolantC = coolant
        session.hasCoolantSample = true
      }
    }
    if (dpf != null) {
      if (!session.hasDpfSample || dpf > session.maxDpfTempC) {
        session.maxDpfTempC = dpf
        session.hasDpfSample = true
      }
      if (dpf >= DPF_REGEN_TEMP_THRESHOLD_C) {
        session.dpfRegenDetected = true
        if (dtSec > 0.05) session.dpfRegenSecondsAcc += dtSec
      }
    }
    if (turbo != null && turbo > session.maxTurboKpa) session.maxTurboKpa = turbo
    if (bat != null && bat > 0.0) {
      if (bat < session.minBatteryV) session.minBatteryV = bat
      if (bat > session.maxBatteryV) session.maxBatteryV = bat
      session.batterySum += bat
      session.batterySamples += 1L
    }

    val elapsedSec = ((now - session.startedAtMillis) / 1000L).coerceAtLeast(0L)
    session.sampleCount += 1L

    val csvRow =
      TripCsvStorage.formatCsvRow(
        timestampMillis = now,
        elapsedSec = elapsedSec,
        speedKmh = speed,
        rpm = rpm,
        coolantC = coolant,
        turboKpa = turbo,
        dpfTempC = dpf,
        railBar = rail,
        loadPct = load,
        mafGs = maf,
        iatC = iat,
        batteryV = bat,
        o2Lambda = o2Lambda,
        fuelLph = fuelLph,
        fuelL100Km = fuelL100,
      )
    runCatching { TripCsvStorage.appendCsvRow(session.csvFile, csvRow) }
      .onFailure { Log.w(TAG, "CSV append failed", it) }

    session.timelineBuffer.add(
      TripTimelinePoint(
        elapsedSec = elapsedSec,
        speedKmh = speed ?: 0.0,
        rpm = rpm ?: 0.0,
        coolantC = coolant ?: 0.0,
        dpfTempC = dpf ?: 0.0,
        batteryV = bat ?: 0.0,
        fuelLph = fuelLph ?: 0.0,
      )
    )

    // Keep timeline buffer bounded in memory on very long drives (> 12h) without losing detail
    if (session.timelineBuffer.size > 720) {
      val compacted = TripCsvStorage.downsampleTimeline(session.timelineBuffer, 240)
      session.timelineBuffer.clear()
      session.timelineBuffer.addAll(compacted)
    }

    val liveAvgL100 =
      if (session.distanceKm >= 0.15 && session.estimatedFuelLiters > 0.0) {
        ((session.estimatedFuelLiters / session.distanceKm) * 100.0).coerceIn(0.0, 40.0)
      } else {
        0.0
      }

    _activeTripState.value =
      ActiveTripUiState(
        isRecording = true,
        tripId = session.tripId,
        title = session.title,
        startedAtMillis = session.startedAtMillis,
        elapsedSec = elapsedSec,
        sampleCount = session.sampleCount,
        distanceKm = session.distanceKm,
        maxDpfTempC = if (session.hasDpfSample) session.maxDpfTempC else 0.0,
        maxCoolantC = if (session.hasCoolantSample) session.maxCoolantC else 0.0,
        minBatteryV = if (session.batterySamples > 0L) session.minBatteryV else 0.0,
        currentFuelLph = fuelLph ?: 0.0,
        estimatedFuelLiters = session.estimatedFuelLiters,
        avgLitersPer100Km = liveAvgL100,
        trafficSeconds = session.trafficSecondsAcc.toLong().coerceAtLeast(0L),
        dpfRegenDetected = session.dpfRegenDetected,
        demoMode = session.demoMode,
        csvFileName = session.csvFileName,
      )

    if (!session.demoMode && telemetryRepository.bluetoothClient.hasConnectPermission()) {
      TelemetryLoggingService.startOrUpdate(
        context = appContext,
        elapsedSec = elapsedSec,
        distanceKm = session.distanceKm,
        sampleCount = session.sampleCount,
      )
    }

    val shouldSyncCheckpoint =
      (session.tripId == null && session.sampleCount >= 2L) ||
        (now - session.lastCloudSyncMillis >= CLOUD_CHECKPOINT_INTERVAL_MS)
    if (shouldSyncCheckpoint) {
      syncCheckpointToFirestore(session, now)
    }
  }

  private suspend fun syncCheckpointToFirestore(session: MutableTripSession, nowMillis: Long) {
    val uid = currentUserId ?: return
    val repo = tripLogRepository ?: return
    session.lastCloudSyncMillis = nowMillis

    val entry = session.toEntry(uid, "RECORDING", nowMillis)
    if (session.tripId.isNullOrBlank()) {
      repo.createTrip(uid, entry).onSuccess { newId ->
        session.tripId = newId
        _activeTripState.value = _activeTripState.value.copy(tripId = newId)
      }
    } else {
      repo.updateTripCheckpoint(uid, entry)
    }
  }

  private companion object {
    const val TAG = "TripLogManager"
    const val PREFS_NAME = "scross_obd_prefs"
    const val KEY_AUTO_LOG_ENABLED = "auto_log_enabled"
    const val TICK_INTERVAL_MS = 1800L
    const val CLOUD_CHECKPOINT_INTERVAL_MS = 60_000L
    const val QUEUE_SPEED_THRESHOLD_KMH = 10.0
    const val DPF_REGEN_TEMP_THRESHOLD_C = 500.0
  }
}
