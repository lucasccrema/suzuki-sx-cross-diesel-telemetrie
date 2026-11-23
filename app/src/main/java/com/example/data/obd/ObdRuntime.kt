package com.example.data.obd

import android.content.Context

class ObdRuntime private constructor(context: Context) {
  private val appContext = context.applicationContext

  val bluetoothClient: BluetoothSppClient = BluetoothSppClient(appContext)
  val telemetryRepository: Elm327TelemetryRepository = Elm327TelemetryRepository(bluetoothClient)
  val tripLogManager: TripLogManager = TripLogManager(appContext, telemetryRepository)

  companion object {
    @Volatile private var instance: ObdRuntime? = null

    fun get(context: Context): ObdRuntime {
      return instance
        ?: synchronized(this) {
          instance ?: ObdRuntime(context.applicationContext).also { instance = it }
        }
    }
  }
}
