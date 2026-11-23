package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class WidgetStyle(val labelIt: String) {
  NUMERIC("Valore"),
  BAR_GAUGE("Barra"),
  SPARKLINE("Grafico"),
  BATTERY_STATUS("Batteria"),
  TPMS_QUAD("TPMS 4 Ruote"),
}

enum class PollingPriority {
  HIGH,
  MEDIUM,
  LOW,
}

enum class ReadingStatus {
  NORMAL,
  WARNING,
  CRITICAL,
}

enum class AvailabilityState {
  ACTIVE,
  UNSUPPORTED,
  WAITING,
  NEEDS_BCM_CONFIG,
}

enum class WheelPosition(val code: String, val labelIt: String) {
  FL("FL", "Ant. Sx"),
  FR("FR", "Ant. Dx"),
  RL("RL", "Post. Sx"),
  RR("RR", "Post. Dx"),
}

@IgnoreExtraProperties
data class DashboardWidgetConfig(
  val id: String = "",
  val pidKey: String = "",
  val style: String = WidgetStyle.NUMERIC.name,
  val spanColumns: Long = 1L,
  val order: Long = 0L,
  val warningThreshold: Double? = null,
  val criticalThreshold: Double? = null,
) {
  fun resolvedStyle(): WidgetStyle =
    WidgetStyle.entries.firstOrNull { it.name == style } ?: WidgetStyle.NUMERIC
}

@IgnoreExtraProperties
data class DashboardProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val name: String = "",
  val isDefault: Boolean = false,
  val widgets: List<DashboardWidgetConfig> = emptyList(),
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class CustomPidConfig(
  @DocumentId val id: String = "",
  val userId: String = "",
  val pidKey: String = "",
  val label: String = "",
  val command: String = "",
  val canHeader: String = "",
  val unit: String = "",
  val formula: String = "A",
  val minValue: Double = 0.0,
  val maxValue: Double = 100.0,
  val targetWheel: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class ObdPidDefinition(
  val key: String,
  val labelIt: String,
  val shortLabel: String,
  val command: String,
  val canHeader: String = "",
  val unit: String,
  val minValue: Double,
  val maxValue: Double,
  val decimals: Int = 0,
  val priority: PollingPriority = PollingPriority.MEDIUM,
  val formula: String = "A",
  val category: String = "Motore",
  val defaultWarningThreshold: Double? = null,
  val defaultCriticalThreshold: Double? = null,
  val isCustom: Boolean = false,
  val targetWheel: String = "",
)

data class TelemetryReading(
  val pidKey: String,
  val value: Double? = null,
  val availability: AvailabilityState = AvailabilityState.WAITING,
  val status: ReadingStatus = ReadingStatus.NORMAL,
  val history: List<Double> = emptyList(),
  val updatedAtMillis: Long = 0L,
)

data class BatteryStatusSummary(
  val voltage: Double? = null,
  val sourceLabel: String = "ATRV / 0142",
  val stateLabel: String = "In attesa",
  val severity: ReadingStatus = ReadingStatus.NORMAL,
)

data class TpmsWheelReading(
  val wheel: WheelPosition,
  val pressureBar: Double? = null,
  val configuredCommand: String? = null,
  val configuredHeader: String? = null,
  val status: ReadingStatus = ReadingStatus.NORMAL,
  val availability: AvailabilityState = AvailabilityState.NEEDS_BCM_CONFIG,
)

data class SupportedPidSnapshot(
  val supportedCommands: Set<String> = emptySet(),
  val unsupportedCommands: Set<String> = emptySet(),
  val ecuName: String = "",
  val protocolName: String = "",
  val discoveryCompleted: Boolean = false,
)

data class BluetoothDeviceItem(
  val name: String,
  val address: String,
  val isBonded: Boolean,
)

data class ElmLogEntry(
  val timestampMillis: Long,
  val direction: String,
  val message: String,
)

sealed interface ElmConnectionState {
  data object Disconnected : ElmConnectionState

  data class Connecting(val deviceName: String) : ElmConnectionState

  data class InitializingElm(val deviceName: String, val step: String) : ElmConnectionState

  data class DiscoveringPids(val deviceName: String) : ElmConnectionState

  data class Connected(
    val deviceName: String,
    val deviceAddress: String,
    val protocol: String,
    val ecuName: String,
  ) : ElmConnectionState

  data object DemoMode : ElmConnectionState

  data class Error(val message: String) : ElmConnectionState
}

data class PidProbeResult(
  val command: String,
  val canHeader: String,
  val rawResponse: String,
  val parsedBytes: List<Int>,
  val decodedValue: Double?,
  val errorMessage: String? = null,
)

data class TpmsByteCandidate(
  val formulaPerWheel: List<String>,
  val samplePressuresBar: List<Double>,
  val scaleLabel: String,
)

data class BcmScanEntry(
  val txHeader: String,
  val rxFilter: String,
  val command: String,
  val rawResponse: String,
  val parsedBytes: List<Int>,
  val categoryHint: String,
  val tpmsCandidate: TpmsByteCandidate? = null,
) {
  val fullHeaderSpec: String
    get() = if (rxFilter.isNotBlank()) "$txHeader:$rxFilter" else txHeader
}

data class BcmScanState(
  val isScanning: Boolean = false,
  val currentStepLabel: String = "",
  val progressFraction: Float = 0f,
  val totalProbes: Int = 0,
  val completedProbes: Int = 0,
  val ecuInfo: String = "",
  val protocolName: String = "",
  val foundEntries: List<BcmScanEntry> = emptyList(),
  val scanTrace: List<String> = emptyList(),
  val errorMessage: String? = null,
  val hasRun: Boolean = false,
)

@Keep
@IgnoreExtraProperties
data class TripTimelinePoint(
  val elapsedSec: Long = 0L,
  val speedKmh: Double = 0.0,
  val rpm: Double = 0.0,
  val coolantC: Double = 0.0,
  val dpfTempC: Double = 0.0,
  val batteryV: Double = 0.0,
  val fuelLph: Double = 0.0,
)

@Keep
@IgnoreExtraProperties
data class TripLogEntry(
  @DocumentId val id: String = "",
  val userId: String = "",
  val title: String = "",
  val startedAtMillis: Long = 0L,
  val endedAtMillis: Long = 0L,
  val durationSec: Long = 0L,
  val sampleCount: Long = 0L,
  val distanceKm: Double = 0.0,
  val avgSpeedKmh: Double = 0.0,
  val maxSpeedKmh: Double = 0.0,
  val maxRpm: Double = 0.0,
  val maxCoolantC: Double = 0.0,
  val maxDpfTempC: Double = 0.0,
  val minBatteryV: Double = 0.0,
  val maxBatteryV: Double = 0.0,
  val avgBatteryV: Double = 0.0,
  val maxTurboKpa: Double = 0.0,
  val estimatedFuelLiters: Double = 0.0,
  val avgLitersPer100Km: Double = 0.0,
  val avgLitersPerHour: Double = 0.0,
  val trafficSeconds: Long = 0L,
  val dpfRegenDetected: Boolean = false,
  val dpfRegenSeconds: Long = 0L,
  val csvFileName: String = "",
  val status: String = "COMPLETED",
  val demoMode: Boolean = false,
  val timelinePoints: List<TripTimelinePoint> = emptyList(),
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class ActiveTripUiState(
  val isRecording: Boolean = false,
  val tripId: String? = null,
  val title: String = "",
  val startedAtMillis: Long = 0L,
  val elapsedSec: Long = 0L,
  val sampleCount: Long = 0L,
  val distanceKm: Double = 0.0,
  val maxDpfTempC: Double = 0.0,
  val maxCoolantC: Double = 0.0,
  val minBatteryV: Double = 0.0,
  val currentFuelLph: Double = 0.0,
  val estimatedFuelLiters: Double = 0.0,
  val avgLitersPer100Km: Double = 0.0,
  val trafficSeconds: Long = 0L,
  val dpfRegenDetected: Boolean = false,
  val demoMode: Boolean = false,
  val csvFileName: String = "",
)

