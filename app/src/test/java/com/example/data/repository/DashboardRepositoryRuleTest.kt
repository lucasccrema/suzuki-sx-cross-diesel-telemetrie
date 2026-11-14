package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CustomPidConfig
import com.example.data.model.DashboardProfile
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.WidgetStyle
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DashboardRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun createAndObserveDashboard_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = DashboardRepository(firestore)
    val dashId = "dash_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    val profile =
      DashboardProfile(
        id = dashId,
        userId = uid,
        name = "Guida D16AA",
        isDefault = true,
        widgets =
          listOf(
            DashboardWidgetConfig("w1", "RPM", WidgetStyle.NUMERIC.name, 1L, 0L),
          ),
      )

    withTimeout(DEFAULT_TIMEOUT_MS) { repo.createDashboard(uid, profile) }

    val list =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeDashboards(uid).first { profiles -> profiles.any { it.id == dashId } }
      }
    val saved = list.first { it.id == dashId }
    assertEquals("Guida D16AA", saved.name)
    assertNotNull(saved.createdAt)

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateDashboard(uid, saved.copy(name = "Monitoraggio D16AA"))
    }
    val updated =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeDashboards(uid).first { profiles ->
          profiles.any { it.id == dashId && it.name == "Monitoraggio D16AA" }
        }
      }
    assertTrue(updated.isNotEmpty())
  }

  @Test
  fun saveCustomPid_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = DashboardRepository(firestore)
    val pidId = "pid_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    val customPid =
      CustomPidConfig(
        id = pidId,
        userId = uid,
        pidKey = "TPMS_FL_TEST",
        label = "TPMS Ant Sx",
        command = "221001",
        canHeader = "751",
        unit = "bar",
        formula = "A*0.025",
        minValue = 0.0,
        maxValue = 4.0,
        targetWheel = "FL",
      )

    withTimeout(DEFAULT_TIMEOUT_MS) { repo.saveCustomPid(uid, customPid) }
    val pids =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeCustomPids(uid).first { items -> items.any { it.id == pidId } }
      }
    assertEquals("751", pids.first { it.id == pidId }.canHeader)
  }

  @Test
  fun crossUserAccess_deniedWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = DashboardRepository(firestore)
    val dashId = "alice_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.createDashboard(
        aliceUid,
        DashboardProfile(id = dashId, userId = aliceUid, name = "Privata", isDefault = true),
      )
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = DashboardRepository(firestore)
    try {
      withTimeout(FLOW_TIMEOUT_MS) { bobRepo.observeDashboards(aliceUid).first() }
      fail("Expected PERMISSION_DENIED for cross-user read")
    } catch (e: Exception) {
      val firestoreEx = e.unwrapFirestoreException()
      assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreEx)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx!!.code)
    }
  }

  @Test
  fun unauthenticatedObserver_deniedWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = DashboardRepository(firestore)
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeDashboards("some_user_123").first() }
      fail("Expected PERMISSION_DENIED for unauthenticated read")
    } catch (e: Exception) {
      val firestoreEx = e.unwrapFirestoreException()
      assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreEx)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx!!.code)
    }
  }

  private companion object {
    const val ALICE_EMAIL = "alice_scrossobd@test.com"
    const val BOB_EMAIL = "bob_scrossobd@test.com"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}
