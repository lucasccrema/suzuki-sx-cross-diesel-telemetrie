package com.example.data.obd

import com.example.data.model.ReadingStatus
import org.junit.Assert.assertEquals
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
}
