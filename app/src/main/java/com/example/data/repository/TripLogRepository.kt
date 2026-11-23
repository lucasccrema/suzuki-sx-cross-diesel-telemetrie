package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firestore.OperationType
import com.example.data.firestore.handleFirestoreError
import com.example.data.model.TripLogEntry
import com.example.data.model.TripTimelinePoint
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TripLogRepository(private val db: FirebaseFirestore) {

  constructor(
    context: Context
  ) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  fun observeTrips(userId: String): Flow<List<TripLogEntry>> {
    val path = "users/$userId/trip_logs"
    return db
      .collection("users")
      .document(userId)
      .collection("trip_logs")
      .snapshots()
      .map { snapshot ->
        snapshot
          .toObjects(
            TripLogEntry::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
          .sortedByDescending { it.startedAtMillis }
      }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null) {
          handleFirestoreError(exceptionToLog, OperationType.LIST, path)
        }
        throw error
      }
  }

  suspend fun createTrip(userId: String, trip: TripLogEntry): Result<String> {
    val collection = db.collection("users").document(userId).collection("trip_logs")
    val docRef = if (trip.id.isNotBlank()) collection.document(trip.id) else collection.document()
    return try {
      docRef.set(createPayload(userId, trip)).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateTripCheckpoint(userId: String, trip: TripLogEntry): Result<Unit> {
    require(trip.id.isNotBlank()) { "Trip ID must not be blank for update" }
    val docRef = db.collection("users").document(userId).collection("trip_logs").document(trip.id)
    return try {
      docRef.update(mutableUpdatePayload(trip)).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun renameTrip(userId: String, tripId: String, newTitle: String): Result<Unit> {
    val cleanTitle = newTitle.trim().take(60)
    if (cleanTitle.isEmpty()) {
      return Result.failure(IllegalArgumentException("Il titolo non può essere vuoto"))
    }
    val docRef = db.collection("users").document(userId).collection("trip_logs").document(tripId)
    return try {
      docRef
        .update(
          mapOf(
            "title" to cleanTitle,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteTrip(userId: String, tripId: String): Result<Unit> {
    val docRef = db.collection("users").document(userId).collection("trip_logs").document(tripId)
    return try {
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  private fun createPayload(userId: String, trip: TripLogEntry): Map<String, Any> =
    mapOf(
      "userId" to userId,
      "title" to trip.title.trim().ifBlank { "Tragitto" }.take(60),
      "startedAtMillis" to trip.startedAtMillis.coerceAtLeast(0L),
      "endedAtMillis" to trip.endedAtMillis.coerceAtLeast(0L),
      "durationSec" to trip.durationSec.coerceAtLeast(0L),
      "sampleCount" to trip.sampleCount.coerceAtLeast(0L),
      "distanceKm" to trip.distanceKm.coerceAtLeast(0.0),
      "avgSpeedKmh" to trip.avgSpeedKmh.coerceAtLeast(0.0),
      "maxSpeedKmh" to trip.maxSpeedKmh.coerceAtLeast(0.0),
      "maxRpm" to trip.maxRpm.coerceAtLeast(0.0),
      "maxCoolantC" to trip.maxCoolantC,
      "maxDpfTempC" to trip.maxDpfTempC,
      "minBatteryV" to trip.minBatteryV.coerceAtLeast(0.0),
      "maxBatteryV" to trip.maxBatteryV.coerceAtLeast(0.0),
      "avgBatteryV" to trip.avgBatteryV.coerceAtLeast(0.0),
      "maxTurboKpa" to trip.maxTurboKpa.coerceAtLeast(0.0),
      "estimatedFuelLiters" to trip.estimatedFuelLiters.coerceAtLeast(0.0),
      "avgLitersPer100Km" to trip.avgLitersPer100Km.coerceAtLeast(0.0),
      "avgLitersPerHour" to trip.avgLitersPerHour.coerceAtLeast(0.0),
      "trafficSeconds" to trip.trafficSeconds.coerceAtLeast(0L),
      "dpfRegenDetected" to trip.dpfRegenDetected,
      "dpfRegenSeconds" to trip.dpfRegenSeconds.coerceAtLeast(0L),
      "csvFileName" to trip.csvFileName.trim().ifBlank { "trip.csv" }.take(80),
      "status" to sanitizeStatus(trip.status),
      "demoMode" to trip.demoMode,
      "timelinePoints" to trip.timelinePoints.take(60).map(::timelinePointToMap),
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )

  private fun mutableUpdatePayload(trip: TripLogEntry): Map<String, Any> =
    mapOf(
      "title" to trip.title.trim().ifBlank { "Tragitto" }.take(60),
      "endedAtMillis" to trip.endedAtMillis.coerceAtLeast(0L),
      "durationSec" to trip.durationSec.coerceAtLeast(0L),
      "sampleCount" to trip.sampleCount.coerceAtLeast(0L),
      "distanceKm" to trip.distanceKm.coerceAtLeast(0.0),
      "avgSpeedKmh" to trip.avgSpeedKmh.coerceAtLeast(0.0),
      "maxSpeedKmh" to trip.maxSpeedKmh.coerceAtLeast(0.0),
      "maxRpm" to trip.maxRpm.coerceAtLeast(0.0),
      "maxCoolantC" to trip.maxCoolantC,
      "maxDpfTempC" to trip.maxDpfTempC,
      "minBatteryV" to trip.minBatteryV.coerceAtLeast(0.0),
      "maxBatteryV" to trip.maxBatteryV.coerceAtLeast(0.0),
      "avgBatteryV" to trip.avgBatteryV.coerceAtLeast(0.0),
      "maxTurboKpa" to trip.maxTurboKpa.coerceAtLeast(0.0),
      "estimatedFuelLiters" to trip.estimatedFuelLiters.coerceAtLeast(0.0),
      "avgLitersPer100Km" to trip.avgLitersPer100Km.coerceAtLeast(0.0),
      "avgLitersPerHour" to trip.avgLitersPerHour.coerceAtLeast(0.0),
      "trafficSeconds" to trip.trafficSeconds.coerceAtLeast(0L),
      "dpfRegenDetected" to trip.dpfRegenDetected,
      "dpfRegenSeconds" to trip.dpfRegenSeconds.coerceAtLeast(0L),
      "status" to sanitizeStatus(trip.status),
      "timelinePoints" to trip.timelinePoints.take(60).map(::timelinePointToMap),
      "updatedAt" to FieldValue.serverTimestamp(),
    )

  private fun timelinePointToMap(point: TripTimelinePoint): Map<String, Any> =
    mapOf(
      "elapsedSec" to point.elapsedSec.coerceAtLeast(0L),
      "speedKmh" to point.speedKmh,
      "rpm" to point.rpm,
      "coolantC" to point.coolantC,
      "dpfTempC" to point.dpfTempC,
      "batteryV" to point.batteryV,
      "fuelLph" to point.fuelLph,
    )

  private fun sanitizeStatus(raw: String): String =
    if (raw == "RECORDING" || raw == "COMPLETED") raw else "COMPLETED"
}
