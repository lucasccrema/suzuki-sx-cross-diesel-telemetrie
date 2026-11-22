package com.example.data.obd

import com.example.data.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class D16aaPidCatalogTest {

  @Test
  fun decodeBitmaskPids_decodesStandardDieselPids() {
    // Bit 31 (0101), Bit 28 (0104), Bit 27 (0105), Bit 21 (010B), Bit 20 (010C), Bit 19 (010D)
    // 1001 1000 = 0x98, 0011 1000 = 0x38
    val pids = D16aaPidCatalog.decodeBitmaskPids(0x00, listOf(0x98, 0x38, 0x00, 0x01))
    assertTrue("0101" in pids)
    assertTrue("0104" in pids)
    assertTrue("0105" in pids)
    assertTrue("010B" in pids)
    assertTrue("010C" in pids)
    assertTrue("010D" in pids)
    assertTrue("0120" in pids)
  }

  @Test
  fun parseElmHexBytes_andEvalFormula_computesRpmAndTemp() {
    // 010C -> 41 0C 0B B8 -> (11*256 + 184)/4 = 3000/4 = 750 rpm
    val rpmBytes = D16aaPidCatalog.parseElmHexBytes("41 0C 0B B8", "010C")
    assertEquals(listOf(0x0B, 0xB8), rpmBytes)
    assertEquals(750.0, D16aaPidCatalog.evalPidFormula("(A*256+B)/4", rpmBytes)!!, 0.01)

    // 0105 -> 41 05 82 -> 130 - 40 = 90 C
    val tempBytes = D16aaPidCatalog.parseElmHexBytes("410582", "0105")
    assertEquals(90.0, D16aaPidCatalog.evalPidFormula("A-40", tempBytes)!!, 0.01)

    // NO DATA returns empty
    assertTrue(D16aaPidCatalog.parseElmHexBytes("NO DATA", "017C").isEmpty())
  }

  @Test
  fun parseAtrv_andClassifyBattery_handlesChargingAndLowVoltage() {
    assertEquals(14.3, D16aaPidCatalog.parseAtrvVoltage("14.3V")!!, 0.01)
    assertEquals(
      ReadingStatus.NORMAL,
      D16aaPidCatalog.classifyBattery(14.3, 850.0).severity,
    )
    assertEquals(
      ReadingStatus.CRITICAL,
      D16aaPidCatalog.classifyBattery(11.5, 0.0).severity,
    )
  }

  @Test
  fun parseHeaderSpec_andEvalExtendedBytes_handlesTxRxAndBytesEFGH() {
    assertEquals("751" to "7A0", D16aaPidCatalog.parseHeaderSpec(" 751:7a0 "))
    assertEquals("751" to "", D16aaPidCatalog.parseHeaderSpec("751"))

    val bytes = listOf(10, 20, 120, 121, 115, 116, 110, 105)
    assertEquals(2.30, D16aaPidCatalog.evalPidFormula("E*0.02", bytes)!!, 0.001)
    assertEquals(2.32, D16aaPidCatalog.evalPidFormula("F*0.02", bytes)!!, 0.001)
    assertEquals(2.20, D16aaPidCatalog.evalPidFormula("G*0.02", bytes)!!, 0.001)
    assertEquals(2.10, D16aaPidCatalog.evalPidFormula("H*0.02", bytes)!!, 0.001)
  }

  @Test
  fun detectTpmsCandidate_findsFourTirePressuresAtOffset() {
    val bytes = listOf(0x00, 0x05, 120, 121, 115, 116)
    val candidate = D16aaPidCatalog.detectTpmsCandidate(bytes)
    assertNotNull(candidate)
    assertEquals(listOf("C*0.02", "D*0.02", "E*0.02", "F*0.02"), candidate!!.formulaPerWheel)
    assertEquals(2.40, candidate.samplePressuresBar[0], 0.01)
    assertEquals(2.32, candidate.samplePressuresBar[3], 0.01)

    assertNull(D16aaPidCatalog.detectTpmsCandidate(listOf(0x00, 0x01, 0x02, 0x03)))
  }

  @Test
  fun parseHeaderedElmResponse_extractsRxHeaderAndIsoTpPayload() {
    val (rx1, bytes1) = D16aaPidCatalog.parseHeaderedElmResponse("7E804410C0D50", "010C")
    assertEquals("7E8", rx1)
    assertEquals(listOf(0x0D, 0x50), bytes1)

    val multi = "759100A62100178797A\n759217B000000"
    val (rx2, bytes2) = D16aaPidCatalog.parseHeaderedElmResponse(multi, "221001")
    assertEquals("759", rx2)
    assertEquals(listOf(0x78, 0x79, 0x7A, 0x7B, 0x00, 0x00, 0x00), bytes2)

    val (rx3, bytes3) = D16aaPidCatalog.parseHeaderedElmResponse("763037F2111", "2101")
    assertEquals("763", rx3)
    assertTrue(bytes3.isEmpty())
  }

  @Test
  fun summarizePassiveCanFrames_groupsIdsAndDetectsPassiveTpms() {
    val frames =
      listOf(
        "1230102030405060708",
        "1230102030405060709",
        "3B00000000000000000",
        "3B07879737400000000",
        "STOPPED",
        "<DATA ERROR",
      )
    val summaries = D16aaPidCatalog.summarizePassiveCanFrames(frames)
    assertEquals(2, summaries.size)
    assertEquals("123", summaries[0].canId)
    assertEquals(2, summaries[0].frameCount)
    assertEquals(2, summaries[0].distinctPayloadsCount)
    assertNull(summaries[0].tpmsCandidate)

    assertEquals("3B0", summaries[1].canId)
    assertEquals(2, summaries[1].frameCount)
    assertEquals(2, summaries[1].distinctPayloadsCount)
    assertNotNull(summaries[1].tpmsCandidate)
    assertEquals(2.40, summaries[1].tpmsCandidate!!.samplePressuresBar[0], 0.01)
  }
}
