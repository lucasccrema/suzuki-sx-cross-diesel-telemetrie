package com.example.data.obd

import com.example.data.model.TripLogEntry
import com.example.data.model.TripTimelinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCsvStorageTest {

  @Test
  fun formatCsvRow_formatsAllColumnsAndHandlesMissingValues() {
    val header = TripCsvStorage.csvHeader()
    assertEquals(15, header.split(",").size)

    val row =
      TripCsvStorage.formatCsvRow(
        timestampMillis = 1732360000000L,
        elapsedSec = 42L,
        speedKmh = 68.5,
        rpm = 2050.0,
        coolantC = 89.0,
        turboKpa = 165.4,
        dpfTempC = 385.2,
        railBar = 820.0,
        loadPct = 54.3,
        mafGs = 38.7,
        iatC = 31.0,
        batteryV = 14.28,
        o2Lambda = 1.65,
        fuelLph = 5.42,
        fuelL100Km = 7.9,
      )
    val cols = row.split(",")
    assertEquals(15, cols.size)
    assertEquals("42", cols[1])
    assertEquals("68.5", cols[2])
    assertEquals("2050", cols[3])
    assertEquals("14.28", cols[11])
    assertEquals("1.65", cols[12])
    assertEquals("5.42", cols[13])
    assertEquals("7.9", cols[14])

    val sparseRow =
      TripCsvStorage.formatCsvRow(
        timestampMillis = 1732360000000L,
        elapsedSec = 0L,
        speedKmh = null,
        rpm = 800.0,
        coolantC = null,
        turboKpa = null,
        dpfTempC = null,
        railBar = null,
        loadPct = null,
        mafGs = null,
        iatC = null,
        batteryV = null,
      )
    val sparseCols = sparseRow.split(",")
    assertEquals(15, sparseCols.size)
    assertEquals("", sparseCols[2])
    assertEquals("800", sparseCols[3])
    assertEquals("", sparseCols[11])
    assertEquals("", sparseCols[14])
  }

  @Test
  fun downsampleTimeline_respectsMaxPointsLimitAndPreservesEndpoints() {
    val raw =
      (0 until 185).map { i ->
        TripTimelinePoint(
          elapsedSec = i * 2L,
          speedKmh = (i % 100).toDouble(),
          rpm = 1800.0 + i,
          coolantC = 88.0,
          dpfTempC = 340.0 + i,
          batteryV = 14.2,
          fuelLph = 4.5,
        )
      }

    val sampled = TripCsvStorage.downsampleTimeline(raw, maxPoints = 60)
    assertTrue(sampled.size <= 60)
    assertTrue(sampled.size >= 50)
    assertEquals(raw.first(), sampled.first())
    assertEquals(raw.last(), sampled.last())

    val small = raw.take(15)
    assertEquals(small, TripCsvStorage.downsampleTimeline(small, maxPoints = 60))
  }

  @Test
  fun buildCsvFromSummary_producesValidCsvForCloudSyncedTrips() {
    val entry =
      TripLogEntry(
        id = "trip_1",
        title = "Casa -> Lavoro",
        startedAtMillis = 1732360000000L,
        timelinePoints =
          listOf(
            TripTimelinePoint(0L, 0.0, 820.0, 45.0, 210.0, 14.2, 0.7),
            TripTimelinePoint(120L, 84.0, 2150.0, 89.0, 395.0, 14.3, 4.2),
          ),
      )

    val csv = TripCsvStorage.buildCsvFromSummary(entry).trim().lines()
    assertEquals(3, csv.size)
    assertTrue(csv[0].startsWith("timestamp_iso,elapsed_sec,speed_kmh"))
    assertTrue(csv[2].contains(",120,84.0,2150,89.0,,395.0,,,,,14.30,,4.20,5.0"))
  }

  @Test
  fun estimateDieselFuelRateLph_handlesCutoffLoadAndIdle() {
    // Overrun deceleration cut-off -> 0.0 L/h
    val cutoff =
      D16aaPidCatalog.estimateDieselFuelRateLph(
        mafGs = 18.0,
        lambdaEq = 1.99,
        engineLoadPct = 0.0,
        rpm = 2100.0,
        speedKmh = 65.0,
        fuelRailBar = 310.0,
      )
    assertEquals(0.0, cutoff!!, 0.001)

    // Acceleration under load using wideband O2 lambda
    val accelLph =
      D16aaPidCatalog.estimateDieselFuelRateLph(
        mafGs = 65.0,
        lambdaEq = 1.40,
        engineLoadPct = 82.0,
        rpm = 2700.0,
        speedKmh = 70.0,
        fuelRailBar = 1350.0,
      )!!
    assertTrue(accelLph in 12.0..15.5)

    // Warm idle when lambda saturates at 1.999 -> realistic ~0.6-1.1 L/h
    val idleLph =
      D16aaPidCatalog.estimateDieselFuelRateLph(
        mafGs = 8.5,
        lambdaEq = 1.99,
        engineLoadPct = 18.0,
        rpm = 800.0,
        speedKmh = 0.0,
        fuelRailBar = 320.0,
      )!!
    assertTrue(idleLph in 0.5..1.1)
  }
}
