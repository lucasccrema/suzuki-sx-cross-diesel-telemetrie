package com.example.data.obd

import com.example.data.model.BatteryStatusSummary
import com.example.data.model.CustomPidConfig
import com.example.data.model.DashboardProfile
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.ObdPidDefinition
import com.example.data.model.PollingPriority
import com.example.data.model.ReadingStatus
import com.example.data.model.WidgetStyle
import java.util.Locale

object D16aaPidCatalog {

  const val PID_BATTERY_SMART = "BATTERY_SMART"
  const val PID_TPMS_QUAD = "TPMS_QUAD"

  val standardPids: List<ObdPidDefinition> =
    listOf(
      ObdPidDefinition(
        key = "RPM",
        labelIt = "Giri Motore",
        shortLabel = "RPM",
        command = "010C",
        unit = "rpm",
        minValue = 0.0,
        maxValue = 5000.0,
        decimals = 0,
        priority = PollingPriority.HIGH,
        formula = "(A*256+B)/4",
        category = "Motore",
        defaultWarningThreshold = 4000.0,
        defaultCriticalThreshold = 4500.0,
      ),
      ObdPidDefinition(
        key = "SPEED",
        labelIt = "Velocità Veicolo",
        shortLabel = "VEL",
        command = "010D",
        unit = "km/h",
        minValue = 0.0,
        maxValue = 210.0,
        decimals = 0,
        priority = PollingPriority.HIGH,
        formula = "A",
        category = "Motore",
      ),
      ObdPidDefinition(
        key = "TURBO_MAP",
        labelIt = "Pressione Turbo / MAP",
        shortLabel = "TURBO",
        command = "010B",
        unit = "kPa",
        minValue = 80.0,
        maxValue = 260.0,
        decimals = 0,
        priority = PollingPriority.HIGH,
        formula = "A",
        category = "Turbo & Aria",
        defaultWarningThreshold = 235.0,
        defaultCriticalThreshold = 250.0,
      ),
      ObdPidDefinition(
        key = "COOLANT_TEMP",
        labelIt = "Temp. Refrigerante",
        shortLabel = "ACQUA",
        command = "0105",
        unit = "°C",
        minValue = 0.0,
        maxValue = 120.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "A-40",
        category = "Temperature",
        defaultWarningThreshold = 98.0,
        defaultCriticalThreshold = 106.0,
      ),
      ObdPidDefinition(
        key = "FUEL_RAIL",
        labelIt = "Pressione Rail Gasolio",
        shortLabel = "RAIL",
        command = "0123",
        unit = "bar",
        minValue = 0.0,
        maxValue = 1800.0,
        decimals = 0,
        priority = PollingPriority.MEDIUM,
        formula = "(A*256+B)/10",
        category = "Iniezione",
      ),
      ObdPidDefinition(
        key = "ENGINE_LOAD",
        labelIt = "Carico Motore",
        shortLabel = "CARICO",
        command = "0104",
        unit = "%",
        minValue = 0.0,
        maxValue = 100.0,
        decimals = 0,
        priority = PollingPriority.MEDIUM,
        formula = "A*100/255",
        category = "Motore",
      ),
      ObdPidDefinition(
        key = "MAF",
        labelIt = "Portata Aria MAF",
        shortLabel = "MAF",
        command = "0110",
        unit = "g/s",
        minValue = 0.0,
        maxValue = 180.0,
        decimals = 1,
        priority = PollingPriority.MEDIUM,
        formula = "(A*256+B)/100",
        category = "Turbo & Aria",
      ),
      ObdPidDefinition(
        key = "INTAKE_TEMP",
        labelIt = "Temp. Aria Aspirazione",
        shortLabel = "IAT",
        command = "010F",
        unit = "°C",
        minValue = -20.0,
        maxValue = 90.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "A-40",
        category = "Temperature",
        defaultWarningThreshold = 60.0,
        defaultCriticalThreshold = 75.0,
      ),
      ObdPidDefinition(
        key = "THROTTLE",
        labelIt = "Posizione Acceleratore",
        shortLabel = "PEDALE",
        command = "0111",
        unit = "%",
        minValue = 0.0,
        maxValue = 100.0,
        decimals = 0,
        priority = PollingPriority.MEDIUM,
        formula = "A*100/255",
        category = "Motore",
      ),
      ObdPidDefinition(
        key = "BATTERY_ECU",
        labelIt = "Tensione Modulo ECU",
        shortLabel = "ECU V",
        command = "0142",
        unit = "V",
        minValue = 10.0,
        maxValue = 16.0,
        decimals = 2,
        priority = PollingPriority.LOW,
        formula = "(A*256+B)/1000",
        category = "Elettronica",
        defaultWarningThreshold = 12.1,
        defaultCriticalThreshold = 11.7,
      ),
      ObdPidDefinition(
        key = "BATTERY_ATRV",
        labelIt = "Tensione Presa OBD (ATRV)",
        shortLabel = "OBD V",
        command = "ATRV",
        unit = "V",
        minValue = 10.0,
        maxValue = 16.0,
        decimals = 1,
        priority = PollingPriority.LOW,
        formula = "ATRV",
        category = "Elettronica",
      ),
      ObdPidDefinition(
        key = "EXHAUST_DPF_TEMP",
        labelIt = "Temp. Scarico / DPF (017C)",
        shortLabel = "SCARICO",
        command = "017C",
        unit = "°C",
        minValue = 100.0,
        maxValue = 750.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "(B*256+C)/10-40",
        category = "Scarico & DPF",
        defaultWarningThreshold = 520.0,
        defaultCriticalThreshold = 660.0,
      ),
      ObdPidDefinition(
        key = "EGT_BANK1",
        labelIt = "Temp. Gas Scarico B1 (0178)",
        shortLabel = "EGT B1",
        command = "0178",
        unit = "°C",
        minValue = 100.0,
        maxValue = 750.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "(B*256+C)/10-40",
        category = "Scarico & DPF",
        defaultWarningThreshold = 550.0,
        defaultCriticalThreshold = 700.0,
      ),
      ObdPidDefinition(
        key = "BARO_PRESSURE",
        labelIt = "Pressione Barometrica",
        shortLabel = "BARO",
        command = "0133",
        unit = "kPa",
        minValue = 70.0,
        maxValue = 110.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "A",
        category = "Turbo & Aria",
      ),
      ObdPidDefinition(
        key = "OIL_TEMP",
        labelIt = "Temperatura Olio Motore",
        shortLabel = "OLIO",
        command = "015C",
        unit = "°C",
        minValue = 0.0,
        maxValue = 140.0,
        decimals = 0,
        priority = PollingPriority.LOW,
        formula = "A-40",
        category = "Temperature",
        defaultWarningThreshold = 118.0,
        defaultCriticalThreshold = 130.0,
      ),
      ObdPidDefinition(
        key = PID_BATTERY_SMART,
        labelIt = "Stato Batteria & Alternatore",
        shortLabel = "BATTERIA",
        command = "0142",
        unit = "V",
        minValue = 10.5,
        maxValue = 15.5,
        decimals = 2,
        priority = PollingPriority.LOW,
        formula = "(A*256+B)/1000",
        category = "Elettronica",
      ),
      ObdPidDefinition(
        key = "O2_LAMBDA",
        labelIt = "Rapporto Lambda O₂ (0134)",
        shortLabel = "LAMBDA",
        command = "0134",
        unit = "λ",
        minValue = 0.8,
        maxValue = 2.0,
        decimals = 2,
        priority = PollingPriority.MEDIUM,
        formula = "(A*256+B)/32768",
        category = "Iniezione",
      ),
      ObdPidDefinition(
        key = PID_TPMS_QUAD,
        labelIt = "Pressione Pneumatici (TPMS)",
        shortLabel = "TPMS",
        command = "",
        unit = "bar",
        minValue = 1.5,
        maxValue = 3.2,
        decimals = 2,
        priority = PollingPriority.LOW,
        formula = "A",
        category = "Pneumatici & BCM",
      ),
    )

  fun findDefinition(
    pidKey: String,
    customPids: List<CustomPidConfig> = emptyList(),
  ): ObdPidDefinition? {
    standardPids.firstOrNull { it.key == pidKey }?.let { return it }
    return customPids.firstOrNull { it.pidKey == pidKey }?.toDefinition()
  }

  fun allDefinitions(customPids: List<CustomPidConfig>): List<ObdPidDefinition> {
    val customDefs = customPids.map { it.toDefinition() }
    return standardPids + customDefs
  }

  fun CustomPidConfig.toDefinition(): ObdPidDefinition =
    ObdPidDefinition(
      key = pidKey,
      labelIt = label,
      shortLabel = label.take(10).uppercase(Locale.ROOT),
      command = command.uppercase(Locale.ROOT).replace(" ", ""),
      canHeader = canHeader.uppercase(Locale.ROOT).trim(),
      unit = unit,
      minValue = minValue,
      maxValue = maxValue,
      decimals = if (maxValue <= 20.0) 2 else 1,
      priority = PollingPriority.LOW,
      formula = formula,
      category = if (targetWheel.isNotBlank()) "Pneumatici & BCM" else "PID Personalizzati",
      isCustom = true,
      targetWheel = targetWheel,
    )

  fun defaultProfilesForUser(userId: String): List<DashboardProfile> =
    listOf(
      DashboardProfile(
        id = "guida_d16aa",
        userId = userId,
        name = "Guida D16AA",
        isDefault = true,
        widgets =
          listOf(
            DashboardWidgetConfig("w_rpm", "RPM", WidgetStyle.BAR_GAUGE.name, 1L, 0L, 4000.0, 4500.0),
            DashboardWidgetConfig("w_spd", "SPEED", WidgetStyle.NUMERIC.name, 1L, 1L),
            DashboardWidgetConfig("w_turbo", "TURBO_MAP", WidgetStyle.SPARKLINE.name, 2L, 2L, 235.0, 250.0),
            DashboardWidgetConfig("w_coolant", "COOLANT_TEMP", WidgetStyle.BAR_GAUGE.name, 1L, 3L, 98.0, 106.0),
            DashboardWidgetConfig("w_rail", "FUEL_RAIL", WidgetStyle.NUMERIC.name, 1L, 4L),
            DashboardWidgetConfig("w_bat", PID_BATTERY_SMART, WidgetStyle.BATTERY_STATUS.name, 2L, 5L),
            DashboardWidgetConfig("w_dpf_t", "EXHAUST_DPF_TEMP", WidgetStyle.NUMERIC.name, 1L, 6L, 520.0, 660.0),
            DashboardWidgetConfig("w_load", "ENGINE_LOAD", WidgetStyle.BAR_GAUGE.name, 1L, 7L),
          ),
      ),
      DashboardProfile(
        id = "turbo_iniezione",
        userId = userId,
        name = "Turbo & Iniezione",
        isDefault = false,
        widgets =
          listOf(
            DashboardWidgetConfig("ti_turbo", "TURBO_MAP", WidgetStyle.SPARKLINE.name, 2L, 0L, 235.0, 250.0),
            DashboardWidgetConfig("ti_rail", "FUEL_RAIL", WidgetStyle.SPARKLINE.name, 2L, 1L),
            DashboardWidgetConfig("ti_maf", "MAF", WidgetStyle.NUMERIC.name, 1L, 2L),
            DashboardWidgetConfig("ti_iat", "INTAKE_TEMP", WidgetStyle.BAR_GAUGE.name, 1L, 3L, 60.0, 75.0),
            DashboardWidgetConfig("ti_rpm", "RPM", WidgetStyle.NUMERIC.name, 1L, 4L),
            DashboardWidgetConfig("ti_load", "ENGINE_LOAD", WidgetStyle.BAR_GAUGE.name, 1L, 5L),
          ),
      ),
      DashboardProfile(
        id = "elettronica_tpms",
        userId = userId,
        name = "Batteria & Pneumatici",
        isDefault = false,
        widgets =
          listOf(
            DashboardWidgetConfig("ep_bat", PID_BATTERY_SMART, WidgetStyle.BATTERY_STATUS.name, 2L, 0L),
            DashboardWidgetConfig("ep_tpms", PID_TPMS_QUAD, WidgetStyle.TPMS_QUAD.name, 2L, 1L),
            DashboardWidgetConfig("ep_atrv", "BATTERY_ATRV", WidgetStyle.SPARKLINE.name, 2L, 2L),
            DashboardWidgetConfig("ep_coolant", "COOLANT_TEMP", WidgetStyle.NUMERIC.name, 1L, 3L),
            DashboardWidgetConfig("ep_iat", "INTAKE_TEMP", WidgetStyle.NUMERIC.name, 1L, 4L),
          ),
      ),
    )

  fun classifyBattery(voltage: Double?, engineRpm: Double?): BatteryStatusSummary {
    if (voltage == null) {
      return BatteryStatusSummary(
        voltage = null,
        stateLabel = "In attesa di lettura",
        severity = ReadingStatus.NORMAL,
      )
    }
    val engineRunning = (engineRpm ?: 0.0) > 400.0
    return when {
      voltage > 15.1 ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = "Sovratensione alternatore",
          severity = ReadingStatus.CRITICAL,
        )
      voltage >= 13.4 ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = if (engineRunning) "Alternatore in carica" else "Tensione carica attiva",
          severity = ReadingStatus.NORMAL,
        )
      engineRunning && voltage < 13.0 ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = "Alternatore insufficiente",
          severity = ReadingStatus.WARNING,
        )
      voltage >= 12.3 ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = "Batteria a riposo OK",
          severity = ReadingStatus.NORMAL,
        )
      voltage >= 11.9 ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = "Batteria debole (< 12.3V)",
          severity = ReadingStatus.WARNING,
        )
      else ->
        BatteryStatusSummary(
          voltage = voltage,
          stateLabel = "Batteria scarica (< 11.9V)",
          severity = ReadingStatus.CRITICAL,
        )
    }
  }

  /**
   * Estimates instantaneous diesel fuel rate (L/h) using MAF (0110), wideband O2 lambda (0134),
   * engine load (0104), and fuel cut-off indicators.
   * Diesel stoichiometry ~14.5:1 AFR, EN590 diesel density ~835 g/L.
   */
  fun estimateDieselFuelRateLph(
    mafGs: Double?,
    lambdaEq: Double?,
    engineLoadPct: Double?,
    rpm: Double?,
    speedKmh: Double?,
    fuelRailBar: Double?,
  ): Double? {
    if (rpm != null && rpm < 250.0) return 0.0
    val isOverrunCutoff =
      rpm != null &&
        rpm > 950.0 &&
        speedKmh != null &&
        speedKmh > 15.0 &&
        engineLoadPct != null &&
        engineLoadPct < 6.0 &&
        (fuelRailBar == null || fuelRailBar < 380.0)
    if (isOverrunCutoff) return 0.0
    if (mafGs == null || mafGs <= 0.0) return null

    // SAE J1979 PID 0134 saturates around 1.9999; under low-load/idle diesels run leaner (~2.5-3.8)
    val effectiveLambda =
      if (lambdaEq != null && lambdaEq in 0.85..1.94) {
        lambdaEq
      } else {
        val loadFrac = ((engineLoadPct ?: 30.0) / 100.0).coerceIn(0.0, 1.0)
        1.95 + (1.0 - loadFrac) * 1.9
      }

    val fuelGramsPerSec = mafGs / (effectiveLambda * 14.5)
    return (fuelGramsPerSec * 3600.0 / 835.0).coerceAtLeast(0.0)
  }

  /**
   * Decodes a 4-byte OBD-II bitmask response (e.g. 0100, 0120, 0140, 0160)
   * into standard Mode 01 command strings like "010C", "0142", "017C".
   */
  fun decodeBitmaskPids(basePidOffset: Int, dataBytes: List<Int>): Set<String> {
    if (dataBytes.size < 4) return emptySet()
    val result = mutableSetOf<String>()
    for (byteIndex in 0 until 4) {
      val b = dataBytes[byteIndex]
      for (bit in 7 downTo 0) {
        if ((b and (1 shl bit)) != 0) {
          val pidNumber = basePidOffset + byteIndex * 8 + (8 - bit)
          result.add(String.format(Locale.ROOT, "01%02X", pidNumber))
        }
      }
    }
    return result
  }

  /**
   * Extracts payload data bytes from an ELM327 hex response.
   * Handles single-line, ISO-TP multi-frame ("0: ... 1: ..."), and spaces.
   */
  fun parseElmHexBytes(rawResponse: String, command: String): List<Int> {
    val cleaned = rawResponse.uppercase(Locale.ROOT).trim()
    if (
      cleaned.isEmpty() ||
        cleaned.contains("NO DATA") ||
        cleaned.contains("UNABLE TO CONNECT") ||
        cleaned.contains("ERROR") ||
        cleaned.contains("?") ||
        cleaned.contains("STOPPED")
    ) {
      return emptyList()
    }

    val cleanCmd = command.uppercase(Locale.ROOT).replace(" ", "")
    val expectedMode =
      if (cleanCmd.length >= 2) {
        cleanCmd.substring(0, 2).toIntOrNull(16)?.plus(0x40)?.let {
          String.format(Locale.ROOT, "%02X", it)
        }
      } else null
    val expectedEchoTail = if (cleanCmd.length > 2) cleanCmd.substring(2) else ""

    // Strip ISO-TP frame prefixes ("0:", "1:") and non-hex tokens
    val lines =
      cleaned.lines().map { line ->
        val afterColon =
          if (line.contains(":")) line.substringAfter(":") else line
        afterColon.replace(Regex("[^0-9A-F]"), "")
      }.filter { it.isNotEmpty() }

    val combinedHex =
      if (lines.size > 1 && lines.first().length == 4 && lines.first().startsWith("0")) {
        // Multi-frame ISO-TP total length header (e.g., 0014) followed by payload
        lines.drop(1).joinToString("")
      } else {
        lines.joinToString("")
      }

    if (combinedHex.length < 2) return emptyList()

    // Find response prefix e.g. "410C" for "010C", or "62115B" for "22115B"
    val fullPrefix = if (expectedMode != null) "$expectedMode$expectedEchoTail" else ""
    val payloadHex =
      if (fullPrefix.isNotEmpty()) {
        val idx = combinedHex.indexOf(fullPrefix)
        if (idx >= 0) combinedHex.substring(idx + fullPrefix.length)
        else if (expectedMode != null && combinedHex.contains(expectedMode)) {
          val modeIdx = combinedHex.indexOf(expectedMode)
          combinedHex.substring(modeIdx + 2)
        } else {
          return emptyList()
        }
      } else {
        combinedHex
      }

    val bytes = mutableListOf<Int>()
    var i = 0
    while (i + 1 < payloadHex.length) {
      val b = payloadHex.substring(i, i + 2).toIntOrNull(16) ?: break
      bytes.add(b)
      i += 2
    }
    return bytes
  }

  /**
   * Parses an ELM327 response received with ATH1 (headers ON) and ATS0 (spaces OFF).
   * Returns (respondingCanHeader, parsedPayloadBytes).
   */
  fun parseHeaderedElmResponse(
    rawResponse: String,
    command: String,
    is29Bit: Boolean = false,
  ): Pair<String, List<Int>> {
    val cleaned = rawResponse.uppercase(Locale.ROOT).trim()
    if (
      cleaned.isEmpty() ||
        cleaned.contains("NO DATA") ||
        cleaned.contains("UNABLE TO CONNECT") ||
        cleaned.contains("CAN ERROR") ||
        cleaned.contains("ERROR") ||
        cleaned.contains("?") ||
        cleaned.contains("STOPPED")
    ) {
      return "" to emptyList()
    }

    val headerLen = if (is29Bit) 8 else 3
    val lines =
      cleaned.lines()
        .map { it.replace(Regex("[^0-9A-F]"), "") }
        .filter { it.length > headerLen + 2 }
    if (lines.isEmpty()) return "" to emptyList()

    val rxHeader = lines.first().substring(0, headerLen)
    val strippedFrames =
      lines.mapNotNull { hex ->
        val body = if (hex.length > headerLen) hex.substring(headerLen) else return@mapNotNull null
        when {
          body.startsWith("0") && body.length >= 2 -> body.substring(2)
          body.startsWith("1") && body.length >= 4 -> body.substring(4)
          body.startsWith("2") && body.length >= 2 -> body.substring(2)
          else -> body
        }
      }.joinToString("")

    val payloadBytes = parseElmHexBytes(strippedFrames, command)
    return rxHeader to payloadBytes
  }

  fun parseAtrvVoltage(rawResponse: String): Double? {
    val match = Regex("(\\d+(?:\\.\\d+)?)\\s*V?", RegexOption.IGNORE_CASE).find(rawResponse)
    return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
  }

  /**
   * Evaluates simple safe arithmetic formulas using variables A, B, C, D.
   * Example: "(A*256+B)/4", "A-40", "(B*256+C)/10-40".
   */
  fun evalPidFormula(formula: String, bytes: List<Int>): Double? {
    if (bytes.isEmpty()) return null
    val a = bytes.getOrElse(0) { 0 }.toDouble()
    val b = bytes.getOrElse(1) { 0 }.toDouble()
    val c = bytes.getOrElse(2) { 0 }.toDouble()
    val d = bytes.getOrElse(3) { 0 }.toDouble()
    val e = bytes.getOrElse(4) { 0 }.toDouble()
    val f = bytes.getOrElse(5) { 0 }.toDouble()
    val g = bytes.getOrElse(6) { 0 }.toDouble()
    val h = bytes.getOrElse(7) { 0 }.toDouble()

    return try {
      FormulaParser(formula, a, b, c, d, e, f, g, h).parse()
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Splits a header spec like "751:7A0" or "751" into (txHeader, optionalRxFilter).
   */
  fun parseHeaderSpec(canHeaderSpec: String): Pair<String, String> {
    val clean = canHeaderSpec.uppercase(Locale.ROOT).trim()
    if (!clean.contains(":")) return clean to ""
    val parts = clean.split(":", limit = 2)
    return parts[0].trim() to parts[1].trim()
  }

  /**
   * Checks whether 4 consecutive payload bytes look like realistic tire pressures (1.6 - 3.6 bar)
   * under common TPMS byte scalings (kPa*2 -> 0.02 bar, kPa*1.25 -> 0.0125, kPa -> 0.01, psi/4).
   */
  fun detectTpmsCandidate(bytes: List<Int>): com.example.data.model.TpmsByteCandidate? {
    if (bytes.size < 4) return null
    val scales =
      listOf(
        0.02 to "*0.02 (2 kPa/bit)",
        0.025 to "*0.025 (2.5 kPa/bit)",
        0.01373 to "*0.01373 (0.2 PSI/bit)",
        0.01 to "*0.01 (1 kPa/bit)",
      )
    val varNames = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    val maxOffset = (bytes.size - 4).coerceAtMost(4)
    for (offset in 0..maxOffset) {
      val slice = bytes.subList(offset, offset + 4)
      for ((factor, label) in scales) {
        val pressures = slice.map { it * factor }
        if (pressures.all { it in 1.65..3.55 } && pressures.distinct().size >= 1) {
          val formulas = (0..3).map { idx -> "${varNames[offset + idx]}${factorString(factor)}" }
          return com.example.data.model.TpmsByteCandidate(
            formulaPerWheel = formulas,
            samplePressuresBar = pressures,
            scaleLabel = "Offset +$offset $label",
          )
        }
      }
    }
    return null
  }

  private fun factorString(factor: Double): String =
    when (factor) {
      0.02 -> "*0.02"
      0.025 -> "*0.025"
      0.01373 -> "*0.01373"
      else -> "*0.01"
    }

  data class PassiveCanSummary(
    val canId: String,
    val frameCount: Int,
    val sampleHex: String,
    val sampleBytes: List<Int>,
    val tpmsCandidate: com.example.data.model.TpmsByteCandidate?,
    val distinctPayloadsCount: Int = 1,
    val extraSampleHex: String? = null,
  )

  fun summarizePassiveCanFrames(rawLines: List<String>): List<PassiveCanSummary> {
    data class Acc(
      var count: Int,
      val firstHex: String,
      val firstBytes: List<Int>,
      val distinctHexes: MutableList<String>,
      var tpmsSampleHex: String?,
      var tpmsSampleBytes: List<Int>?,
      var tpmsCandidate: com.example.data.model.TpmsByteCandidate?,
    )
    val grouped = linkedMapOf<String, Acc>()
    for (line in rawLines) {
      val upper = line.uppercase(Locale.ROOT).trim()
      if (
        upper.isEmpty() ||
          upper.contains("ERROR") ||
          upper.contains("STOPPED") ||
          upper.contains("BUFFER") ||
          upper.contains("NO DATA") ||
          upper.contains("?")
      ) {
        continue
      }
      val hex = upper.replace(Regex("[^0-9A-F]"), "")
      // Standard 11-bit CAN frame under ATH1+ATS0: 3 hex CAN ID + 1..8 payload bytes (2..16 hex chars)
      if (hex.length < 5 || hex.length > 19 || (hex.length - 3) % 2 != 0) continue
      val canId = hex.substring(0, 3)
      val payloadHex = hex.substring(3)
      val bytes = mutableListOf<Int>()
      var i = 0
      while (i + 1 < payloadHex.length) {
        bytes.add(payloadHex.substring(i, i + 2).toInt(16))
        i += 2
      }
      val existing = grouped[canId]
      if (existing != null) {
        existing.count += 1
        if (payloadHex !in existing.distinctHexes && existing.distinctHexes.size < 6) {
          existing.distinctHexes.add(payloadHex)
        }
        if (existing.tpmsCandidate == null) {
          val cand = detectTpmsCandidate(bytes)
          if (cand != null) {
            existing.tpmsCandidate = cand
            existing.tpmsSampleHex = payloadHex
            existing.tpmsSampleBytes = bytes
          }
        }
      } else {
        val cand = detectTpmsCandidate(bytes)
        grouped[canId] =
          Acc(
            count = 1,
            firstHex = payloadHex,
            firstBytes = bytes,
            distinctHexes = mutableListOf(payloadHex),
            tpmsSampleHex = if (cand != null) payloadHex else null,
            tpmsSampleBytes = if (cand != null) bytes else null,
            tpmsCandidate = cand,
          )
      }
    }
    return grouped.entries.map { (canId, acc) ->
      PassiveCanSummary(
        canId = canId,
        frameCount = acc.count,
        sampleHex = acc.tpmsSampleHex ?: acc.firstHex,
        sampleBytes = acc.tpmsSampleBytes ?: acc.firstBytes,
        tpmsCandidate = acc.tpmsCandidate,
        distinctPayloadsCount = acc.distinctHexes.size,
        extraSampleHex = acc.distinctHexes.getOrNull(1),
      )
    }
  }

  private class FormulaParser(
    private val input: String,
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double,
    private val g: Double,
    private val h: Double,
  ) {
    private val expr = input.replace(" ", "").uppercase(Locale.ROOT)
    private var pos = 0

    fun parse(): Double {
      val value = parseExpression()
      if (pos < expr.length) throw IllegalArgumentException("Unexpected char at $pos")
      return value
    }

    private fun parseExpression(): Double {
      var x = parseTerm()
      while (pos < expr.length) {
        when (expr[pos]) {
          '+' -> {
            pos++
            x += parseTerm()
          }
          '-' -> {
            pos++
            x -= parseTerm()
          }
          else -> return x
        }
      }
      return x
    }

    private fun parseTerm(): Double {
      var x = parseFactor()
      while (pos < expr.length) {
        when (expr[pos]) {
          '*' -> {
            pos++
            x *= parseFactor()
          }
          '/' -> {
            pos++
            val denom = parseFactor()
            if (denom == 0.0) throw ArithmeticException("Division by zero")
            x /= denom
          }
          else -> return x
        }
      }
      return x
    }

    private fun parseFactor(): Double {
      if (pos < expr.length && expr[pos] == '-') {
        pos++
        return -parseFactor()
      }
      if (pos < expr.length && expr[pos] == '+') {
        pos++
        return parseFactor()
      }
      if (pos < expr.length && expr[pos] == '(') {
        pos++
        val x = parseExpression()
        if (pos < expr.length && expr[pos] == ')') pos++
        return x
      }
      if (pos < expr.length && expr[pos] in listOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')) {
        val ch = expr[pos++]
        return when (ch) {
          'A' -> a
          'B' -> b
          'C' -> c
          'D' -> d
          'E' -> e
          'F' -> f
          'G' -> g
          else -> h
        }
      }
      val start = pos
      while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) {
        pos++
      }
      if (start == pos) throw IllegalArgumentException("Expected number at $pos in $expr")
      return expr.substring(start, pos).toDouble()
    }
  }
}
