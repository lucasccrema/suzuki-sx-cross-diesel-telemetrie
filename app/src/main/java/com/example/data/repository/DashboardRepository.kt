package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firestore.OperationType
import com.example.data.firestore.handleFirestoreError
import com.example.data.model.CustomPidConfig
import com.example.data.model.DashboardProfile
import com.example.data.model.DashboardWidgetConfig
import com.example.data.obd.D16aaPidCatalog
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

class DashboardRepository(private val db: FirebaseFirestore) {

  constructor(
    context: Context
  ) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  fun observeDashboards(userId: String): Flow<List<DashboardProfile>> {
    val path = "users/$userId/dashboards"
    return db
      .collection("users")
      .document(userId)
      .collection("dashboards")
      .snapshots()
      .map { snapshot ->
        snapshot
          .toObjects(
            DashboardProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
          .sortedWith(compareByDescending<DashboardProfile> { it.isDefault }.thenBy { it.name })
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

  fun observeCustomPids(userId: String): Flow<List<CustomPidConfig>> {
    val path = "users/$userId/custom_pids"
    return db
      .collection("users")
      .document(userId)
      .collection("custom_pids")
      .snapshots()
      .map { snapshot ->
        snapshot
          .toObjects(
            CustomPidConfig::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
          .sortedBy { it.label }
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

  suspend fun ensureDefaultDashboards(userId: String) {
    val collection = db.collection("users").document(userId).collection("dashboards")
    val path = "users/$userId/dashboards"
    try {
      val existing = collection.limit(1).get().await()
      if (!existing.isEmpty) return

      val defaults = D16aaPidCatalog.defaultProfilesForUser(userId)
      for (profile in defaults) {
        val docRef = collection.document(profile.id)
        docRef.set(dashboardCreatePayload(userId, profile)).await()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      throw e
    }
  }

  suspend fun createDashboard(userId: String, profile: DashboardProfile): String {
    val collection = db.collection("users").document(userId).collection("dashboards")
    val docRef =
      if (profile.id.isNotBlank()) collection.document(profile.id) else collection.document()
    return try {
      docRef.set(dashboardCreatePayload(userId, profile)).await()
      docRef.id
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun updateDashboard(userId: String, profile: DashboardProfile) {
    require(profile.id.isNotBlank()) { "Dashboard ID must not be blank for update" }
    requireNotNull(profile.createdAt) { "Dashboard createdAt must not be null for update" }
    val docRef =
      db.collection("users").document(userId).collection("dashboards").document(profile.id)
    val sanitizedWidgets =
      profile.widgets.take(24).mapIndexed { index, widget ->
        widgetToMap(widget.copy(order = index.toLong()))
      }
    val payload =
      mapOf(
        "name" to profile.name.take(50),
        "isDefault" to profile.isDefault,
        "widgets" to sanitizedWidgets,
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    try {
      docRef.update(payload).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun deleteDashboard(userId: String, dashboardId: String) {
    val docRef =
      db.collection("users").document(userId).collection("dashboards").document(dashboardId)
    try {
      docRef.delete().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      throw e
    }
  }

  suspend fun saveCustomPid(userId: String, config: CustomPidConfig): String {
    val collection = db.collection("users").document(userId).collection("custom_pids")
    val docRef =
      if (config.id.isNotBlank()) collection.document(config.id) else collection.document()
    return try {
      if (config.id.isNotBlank() && config.createdAt != null) {
        val updateMap =
          mapOf(
            "pidKey" to config.pidKey.take(40),
            "label" to config.label.take(50),
            "command" to config.command.uppercase().replace(" ", "").take(16),
            "canHeader" to config.canHeader.uppercase().trim().take(8),
            "unit" to config.unit.take(16),
            "formula" to config.formula.take(80),
            "minValue" to config.minValue,
            "maxValue" to config.maxValue.coerceAtLeast(config.minValue),
            "targetWheel" to config.targetWheel,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        docRef.update(updateMap).await()
      } else {
        val createMap =
          mapOf(
            "userId" to userId,
            "pidKey" to config.pidKey.take(40),
            "label" to config.label.take(50),
            "command" to config.command.uppercase().replace(" ", "").take(16),
            "canHeader" to config.canHeader.uppercase().trim().take(8),
            "unit" to config.unit.take(16),
            "formula" to config.formula.take(80),
            "minValue" to config.minValue,
            "maxValue" to config.maxValue.coerceAtLeast(config.minValue),
            "targetWheel" to config.targetWheel,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        docRef.set(createMap).await()
      }
      docRef.id
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docRef.path)
      throw e
    }
  }

  suspend fun deleteCustomPid(userId: String, pidId: String) {
    val docRef =
      db.collection("users").document(userId).collection("custom_pids").document(pidId)
    try {
      docRef.delete().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      throw e
    }
  }

  private fun dashboardCreatePayload(userId: String, profile: DashboardProfile): Map<String, Any> =
    mapOf(
      "userId" to userId,
      "name" to profile.name.take(50),
      "isDefault" to profile.isDefault,
      "widgets" to profile.widgets.take(24).mapIndexed { idx, w -> widgetToMap(w.copy(order = idx.toLong())) },
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )

  private fun widgetToMap(widget: DashboardWidgetConfig): Map<String, Any> =
    buildMap {
      put("id", widget.id)
      put("pidKey", widget.pidKey)
      put("style", widget.style)
      put("spanColumns", widget.spanColumns)
      put("order", widget.order)
      widget.warningThreshold?.let { put("warningThreshold", it) }
      widget.criticalThreshold?.let { put("criticalThreshold", it) }
    }
}
