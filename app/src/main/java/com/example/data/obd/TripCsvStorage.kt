package com.example.data.obd

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.TripLogEntry
import com.example.data.model.TripTimelinePoint
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TripCsvStorage {

  private const val DIR_TRIP_LOGS = "trip_logs"

  fun csvHeader(): String =
    "timestamp_iso,elapsed_sec,speed_kmh,rpm,coolant_c,turbo_kpa,dpf_temp_c,rail_bar,load_pct,maf_gs,iat_c,battery_v,o2_lambda,fuel_lh,fuel_l100km"

  fun formatCsvRow(
    timestampMillis: Long,
    elapsedSec: Long,
    speedKmh: Double?,
    rpm: Double?,
    coolantC: Double?,
    turboKpa: Double?,
    dpfTempC: Double?,
    railBar: Double?,
    loadPct: Double?,
    mafGs: Double?,
    iatC: Double?,
    batteryV: Double?,
    o2Lambda: Double? = null,
    fuelLph: Double? = null,
    fuelL100Km: Double? = null,
  ): String {
    val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    val iso = isoFmt.format(Date(timestampMillis))
    return buildString {
      append(iso).append(',')
      append(elapsedSec).append(',')
      append(fmtNum(speedKmh, 1)).append(',')
      append(fmtNum(rpm, 0)).append(',')
      append(fmtNum(coolantC, 1)).append(',')
      append(fmtNum(turboKpa, 1)).append(',')
      append(fmtNum(dpfTempC, 1)).append(',')
      append(fmtNum(railBar, 0)).append(',')
      append(fmtNum(loadPct, 1)).append(',')
      append(fmtNum(mafGs, 1)).append(',')
      append(fmtNum(iatC, 1)).append(',')
      append(fmtNum(batteryV, 2)).append(',')
      append(fmtNum(o2Lambda, 2)).append(',')
      append(fmtNum(fuelLph, 2)).append(',')
      append(fmtNum(fuelL100Km, 1))
    }
  }

  fun downsampleTimeline(
    points: List<TripTimelinePoint>,
    maxPoints: Int = 60,
  ): List<TripTimelinePoint> {
    if (maxPoints <= 0) return emptyList()
    if (points.size <= maxPoints) return points
    if (maxPoints == 1) return listOf(points.first())

    val result = ArrayList<TripTimelinePoint>(maxPoints)
    val step = (points.size - 1).toDouble() / (maxPoints - 1).toDouble()
    var lastIdx = -1
    for (i in 0 until maxPoints) {
      val idx = kotlin.math.round(i * step).toInt().coerceIn(0, points.lastIndex)
      if (idx != lastIdx) {
        result.add(points[idx])
        lastIdx = idx
      }
    }
    if (result.last() != points.last() && result.size < maxPoints) {
      result.add(points.last())
    }
    return result
  }

  fun defaultCommuteTitle(startedAtMillis: Long, demoMode: Boolean): String {
    val cal = Calendar.getInstance().apply { timeInMillis = startedAtMillis }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val base =
      when (hour) {
        in 5..11 -> "Casa -> Lavoro"
        in 12..22 -> "Lavoro -> Casa"
        else -> "Tragitto Notturno"
      }
    val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)
    val raw =
      if (demoMode) "$base (${dateFmt.format(Date(startedAtMillis))}) [Demo]"
      else "$base (${dateFmt.format(Date(startedAtMillis))})"
    return raw.take(60)
  }

  fun buildCsvFromSummary(entry: TripLogEntry): String {
    val sb = StringBuilder()
    sb.appendLine(csvHeader())
    entry.timelinePoints.forEach { pt ->
      val ts = entry.startedAtMillis + pt.elapsedSec * 1000L
      val l100 = if (pt.speedKmh > 5.0 && pt.fuelLph > 0.0) (pt.fuelLph / pt.speedKmh) * 100.0 else null
      sb.appendLine(
        formatCsvRow(
          timestampMillis = ts,
          elapsedSec = pt.elapsedSec,
          speedKmh = pt.speedKmh,
          rpm = pt.rpm,
          coolantC = pt.coolantC,
          turboKpa = null,
          dpfTempC = pt.dpfTempC,
          railBar = null,
          loadPct = null,
          mafGs = null,
          iatC = null,
          batteryV = pt.batteryV,
          o2Lambda = null,
          fuelLph = pt.fuelLph,
          fuelL100Km = l100,
        )
      )
    }
    return sb.toString()
  }

  fun newCsvFileName(startedAtMillis: Long): String {
    val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
    return "trip_${fmt.format(Date(startedAtMillis))}.csv"
  }

  fun createCsvFile(context: Context, fileName: String): File {
    val dir = logsDir(context)
    val file = File(dir, sanitizeFileName(fileName))
    FileWriter(file, false).use { writer ->
      writer.appendLine(csvHeader())
    }
    return file
  }

  fun appendCsvRow(file: File, row: String) {
    FileWriter(file, true).use { writer ->
      writer.appendLine(row)
    }
  }

  fun localCsvExists(context: Context, csvFileName: String): Boolean {
    if (csvFileName.isBlank()) return false
    val file = File(logsDir(context), sanitizeFileName(csvFileName))
    return file.exists() && file.length() > 0L
  }

  fun resolveShareableCsvUri(context: Context, entry: TripLogEntry): Uri {
    val dir = logsDir(context)
    val file = File(dir, sanitizeFileName(entry.csvFileName.ifBlank { "trip_${entry.id}.csv" }))
    if (!file.exists() || file.length() == 0L) {
      FileWriter(file, false).use { writer ->
        writer.write(buildCsvFromSummary(entry))
      }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }

  fun deleteCsvFile(context: Context, csvFileName: String) {
    if (csvFileName.isBlank()) return
    val file = File(logsDir(context), sanitizeFileName(csvFileName))
    if (file.exists()) {
      file.delete()
    }
  }

  private fun logsDir(context: Context): File =
    File(context.applicationContext.filesDir, DIR_TRIP_LOGS).apply {
      if (!exists()) mkdirs()
    }

  private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").ifBlank { "trip.csv" }

  private fun fmtNum(value: Double?, decimals: Int): String {
    if (value == null || value.isNaN() || value.isInfinite()) return ""
    return String.format(Locale.ROOT, "%.${decimals}f", value)
  }
}
