package com.example.data.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.model.BluetoothDeviceItem
import com.example.data.model.ElmLogEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BluetoothSppClient(context: Context) {

  private val appContext = context.applicationContext
  private val bluetoothManager: BluetoothManager? =
    appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

  private val commandMutex = Mutex()
  private var socket: BluetoothSocket? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null

  private val _logs = MutableStateFlow<List<ElmLogEntry>>(emptyList())
  val logs: StateFlow<List<ElmLogEntry>> = _logs.asStateFlow()

  fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

  fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

  fun hasConnectPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH) ==
        PackageManager.PERMISSION_GRANTED
    }
  }

  fun hasScanPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    }
  }

  fun requiredRuntimePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
  }

  @SuppressLint("MissingPermission")
  fun getBondedDevices(): List<BluetoothDeviceItem> {
    val adapter = bluetoothAdapter ?: return emptyList()
    if (!hasConnectPermission()) return emptyList()
    return try {
      adapter.bondedDevices
        ?.map { device ->
          BluetoothDeviceItem(
            name = device.name?.takeIf { it.isNotBlank() } ?: "Dispositivo BT",
            address = device.address,
            isBonded = true,
          )
        }
        ?.sortedWith(
          compareByDescending<BluetoothDeviceItem> {
            it.name.contains("OBD", ignoreCase = true) ||
              it.name.contains("ELM", ignoreCase = true) ||
              it.name.contains("Vgate", ignoreCase = true) ||
              it.name.contains("KONNWEI", ignoreCase = true)
          }.thenBy { it.name }
        ) ?: emptyList()
    } catch (_: SecurityException) {
      emptyList()
    }
  }

  @SuppressLint("MissingPermission")
  fun scanClassicDevices(): Flow<BluetoothDeviceItem> = callbackFlow {
    val adapter = bluetoothAdapter
    if (adapter == null || !adapter.isEnabled || !hasScanPermission() || !hasConnectPermission()) {
      close()
      return@callbackFlow
    }

    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          when (intent?.action) {
            BluetoothDevice.ACTION_FOUND -> {
              val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                  @Suppress("DEPRECATION")
                  intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
              device?.let {
                try {
                  trySend(
                    BluetoothDeviceItem(
                      name = it.name?.takeIf { n -> n.isNotBlank() } ?: "OBD / Bluetooth",
                      address = it.address,
                      isBonded = it.bondState == BluetoothDevice.BOND_BONDED,
                    )
                  )
                } catch (_: SecurityException) {}
              }
            }
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
              close()
            }
          }
        }
      }

    val filter =
      IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
      }
    ContextCompat.registerReceiver(
      appContext,
      receiver,
      filter,
      ContextCompat.RECEIVER_EXPORTED,
    )

    try {
      if (adapter.isDiscovering) adapter.cancelDiscovery()
      adapter.startDiscovery()
    } catch (_: SecurityException) {
      close()
    }

    awaitClose {
      try {
        appContext.unregisterReceiver(receiver)
      } catch (_: Exception) {}
      try {
        if (hasScanPermission() && adapter.isDiscovering) {
          adapter.cancelDiscovery()
        }
      } catch (_: SecurityException) {}
    }
  }

  @SuppressLint("MissingPermission")
  suspend fun connect(address: String): Result<String> =
    withContext(Dispatchers.IO) {
      commandMutex.withLock {
        closeSocketInternal()
        val adapter =
          bluetoothAdapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth non supportato"))
        if (!adapter.isEnabled) {
          return@withContext Result.failure(IllegalStateException("Bluetooth disattivato"))
        }
        if (!hasConnectPermission()) {
          return@withContext Result.failure(SecurityException("Permesso BLUETOOTH_CONNECT mancante"))
        }

        try {
          if (hasScanPermission() && adapter.isDiscovering) {
            adapter.cancelDiscovery()
          }
          val device = adapter.getRemoteDevice(address)
          val deviceName = device.name?.takeIf { it.isNotBlank() } ?: address
          appendLog("SYS", "Connessione SPP a $deviceName ($address)...")

          val newSocket =
            try {
              val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
              secure.connect()
              secure
            } catch (primaryErr: IOException) {
              appendLog("SYS", "Fallback socket insicuro RFCOMM...")
              val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
              insecure.connect()
              insecure
            }

          socket = newSocket
          inputStream = newSocket.inputStream
          outputStream = newSocket.outputStream
          appendLog("SYS", "Socket RFCOMM connesso")
          Result.success(deviceName)
        } catch (e: Exception) {
          closeSocketInternal()
          appendLog("ERR", e.message ?: "Errore apertura socket Bluetooth")
          Result.failure(e)
        }
      }
    }

  suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      commandMutex.withLock {
        closeSocketInternal()
        appendLog("SYS", "Disconnesso")
      }
    }
  }

  suspend fun sendCommand(command: String, timeoutMs: Long = 2200L): String =
    withContext(Dispatchers.IO) {
      commandMutex.withLock {
        val out = outputStream ?: throw IOException("Socket non connesso")
        val inp = inputStream ?: throw IOException("Stream ingresso non disponibile")

        // Drain any stale bytes before writing
        while (inp.available() > 0) {
          inp.read()
        }

        val cleanCmd = command.trim()
        appendLog("TX", cleanCmd)
        out.write((cleanCmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val response =
          withTimeout(timeoutMs) {
            val builder = StringBuilder()
            val buffer = ByteArray(128)
            while (true) {
              if (inp.available() > 0) {
                val count = inp.read(buffer)
                if (count < 0) throw IOException("Connessione chiusa dall'adattatore")
                val chunk = String(buffer, 0, count, Charsets.US_ASCII)
                builder.append(chunk)
                if (chunk.contains('>')) break
              } else {
                delay(15L)
              }
            }
            builder.toString().replace(">", "").replace("\r", "\n").trim()
          }
        appendLog("RX", response.replace("\n", " | ").take(120))
        response
      }
    }

  fun clearLogs() {
    _logs.value = emptyList()
  }

  private fun closeSocketInternal() {
    try {
      inputStream?.close()
    } catch (_: Exception) {}
    try {
      outputStream?.close()
    } catch (_: Exception) {}
    try {
      socket?.close()
    } catch (_: Exception) {}
    inputStream = null
    outputStream = null
    socket = null
  }

  fun appendLog(direction: String, message: String) {
    val entry = ElmLogEntry(System.currentTimeMillis(), direction, message)
    _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
  }

  companion object {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val MAX_LOG_ENTRIES = 80
  }
}
