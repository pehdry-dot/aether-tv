package com.aether.tv.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.core.content.ContextCompat
import com.aether.tv.core.model.DeviceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class BluetoothPeer(val name: String, val address: String, val kind: DeviceKind, val connected: Boolean)
data class Controller(val name: String, val descriptor: String, val vendorId: Int, val productId: Int)

data class PeripheralSnapshot(
    val bluetoothAvailable: Boolean,
    val bluetoothPermission: Boolean,
    val bluetoothEnabled: Boolean,
    val peers: List<BluetoothPeer>,
    val controllers: List<Controller>,
)

/**
 * Discovers peripherals without scanning: only already-bonded Bluetooth devices and attached input devices are
 * reported. No network or Bluetooth discovery scans are performed.
 */
@Singleton
class DeviceScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val btManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val connectedAudio = ConcurrentHashMap.newKeySet<String>()

    fun bluetoothPermissionNeeded(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasBluetoothPermission(): Boolean {
        val p = bluetoothPermissionNeeded() ?: return true
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    fun controllers(): List<Controller> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val d = InputDevice.getDevice(id) ?: return@mapNotNull null
        val s = d.sources
        val gamepad = s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        // Remotes may report gamepad buttons; real controllers also expose joystick axes.
        if (d.isVirtual || !gamepad || !joystick) null else Controller(d.name, d.descriptor, d.vendorId, d.productId)
    }.distinctBy { it.descriptor }

    @SuppressLint("MissingPermission")
    fun snapshot(): PeripheralSnapshot {
        val a = adapter
        val permitted = hasBluetoothPermission()
        val enabled = runCatching { a?.isEnabled == true }.getOrDefault(false)
        val controllers = controllers()
        val peers = if (a != null && permitted && enabled) runCatching {
            a.bondedDevices.orEmpty().map { d ->
                val name = runCatching { d.name }.getOrNull() ?: d.address
                val kind = classify(d)
                val connected = d.address in connectedAudio ||
                    (kind == DeviceKind.Controller && controllers.any { it.name.equals(name, true) })
                BluetoothPeer(name, d.address, kind, connected)
            }
        }.getOrDefault(emptyList()) else emptyList()
        return PeripheralSnapshot(a != null, permitted, enabled, peers.sortedBy { it.name }, controllers)
    }

    @SuppressLint("MissingPermission")
    private fun classify(d: BluetoothDevice): DeviceKind {
        val cls = runCatching { d.bluetoothClass }.getOrNull() ?: return DeviceKind.Other
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> DeviceKind.Headphones
                else -> DeviceKind.Speaker
            }
            BluetoothClass.Device.Major.PERIPHERAL -> DeviceKind.Controller
            BluetoothClass.Device.Major.COMPUTER -> DeviceKind.Pc
            else -> DeviceKind.Other
        }
    }

    /** Re-emits a snapshot whenever Bluetooth or input devices change. */
    @SuppressLint("MissingPermission")
    fun observe(): Flow<PeripheralSnapshot> = callbackFlow {
        val main = Handler(Looper.getMainLooper())
        fun emit() { trySend(snapshot()) }

        // Active audio connections come from profile proxies (public API).
        val proxies = mutableListOf<Pair<Int, BluetoothProfile>>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies += profile to proxy
                refreshAudio(proxies); emit()
            }
            override fun onServiceDisconnected(profile: Int) { proxies.removeAll { it.first == profile } }
        }
        if (hasBluetoothPermission()) {
            runCatching { adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
            runCatching { adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refreshAudio(proxies); emit() }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val inputListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = emit()
            override fun onInputDeviceRemoved(deviceId: Int) = emit()
            override fun onInputDeviceChanged(deviceId: Int) = emit()
        }
        im.registerInputDeviceListener(inputListener, main)
        emit()
        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            im.unregisterInputDeviceListener(inputListener)
            proxies.forEach { (p, proxy) -> runCatching { adapter?.closeProfileProxy(p, proxy) } }
        }
    }.conflate()

    @SuppressLint("MissingPermission")
    private fun refreshAudio(proxies: List<Pair<Int, BluetoothProfile>>) {
        if (!hasBluetoothPermission()) return
        val now = proxies.flatMap { (_, p) -> runCatching { p.connectedDevices.map { it.address } }.getOrDefault(emptyList()) }
        connectedAudio.clear(); connectedAudio.addAll(now)
    }
}
