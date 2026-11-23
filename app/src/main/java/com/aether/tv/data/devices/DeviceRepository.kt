package com.aether.tv.data.devices

import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceLaunchAction
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.TvInput
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.BluetoothPeer
import com.aether.tv.platform.DeviceScanner
import com.aether.tv.platform.InputSwitchResult
import com.aether.tv.platform.LaunchResult
import com.aether.tv.platform.PeripheralSnapshot
import com.aether.tv.platform.TvInputController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the device UI needs: configured shortcuts with verified status, plus discovered hardware. */
data class DeviceOverview(
    val devices: List<ConnectedDevice>,
    val inputs: List<TvInput>,
    val peripherals: PeripheralSnapshot,
    val inputFrameworkAvailable: Boolean,
)

sealed interface DeviceActionResult {
    data class Done(val message: String?) : DeviceActionResult
    data class NeedsSetup(val deviceId: String) : DeviceActionResult
    data class Info(val message: String) : DeviceActionResult
}

@Singleton
class DeviceRepository @Inject constructor(
    private val db: AetherDatabase,
    private val inputs: TvInputController,
    private val scanner: DeviceScanner,
    private val launcher: AppLauncher,
) {
    fun observe(): Flow<DeviceOverview> = combine(db.devices().observe(), inputs.observe(), scanner.observe()) { configured, tvInputs, peripherals ->
        DeviceOverview(resolve(configured, tvInputs, peripherals, inputs.frameworkAvailable), tvInputs, peripherals, inputs.frameworkAvailable)
    }.flowOn(Dispatchers.Default)

    suspend fun get(id: String) = db.devices().get(id)
    suspend fun save(d: DeviceEntity) = db.devices().upsert(d)
    suspend fun delete(id: String) = db.devices().delete(id)
    suspend fun all() = db.devices().all()

    fun newId() = UUID.randomUUID().toString()

    /** Seeds the featured Xbox Series X shortcut once; it starts unconfigured (no input assigned). */
    suspend fun seedDefaults() {
        if (db.devices().all().isEmpty()) {
            db.devices().upsert(DeviceEntity(id = XBOX_ID, name = "Xbox Series X", kind = DeviceKind.Xbox.name, artworkUri = "builtin:xbox_series_x", launchAction = DeviceLaunchAction.SwitchInput.name, sortOrder = 0))
        }
    }

    suspend fun activate(device: ConnectedDevice): DeviceActionResult = withContext(Dispatchers.Main) {
        when (device.launchAction) {
            DeviceLaunchAction.SwitchInput -> {
                val id = device.inputId
                if (id == null) {
                    if (device.configured) DeviceActionResult.NeedsSetup(device.id)
                    else DeviceActionResult.Info("${device.name} is a ${device.detail} device. Use the system Bluetooth settings to manage it.")
                } else when (val r = inputs.switchTo(id)) {
                    is InputSwitchResult.Requested -> DeviceActionResult.Done(null)
                    is InputSwitchResult.OpenedFallback -> DeviceActionResult.Info("This TV doesn't let apps switch inputs directly, so ${r.screen} was opened. ${r.hint}")
                    is InputSwitchResult.Failed -> DeviceActionResult.Info(r.reason)
                }
            }
            DeviceLaunchAction.OpenApp -> {
                val pkg = device.launchPackage ?: return@withContext DeviceActionResult.NeedsSetup(device.id)
                when (val r = launcher.launchPackage(pkg)) {
                    is LaunchResult.Opened -> DeviceActionResult.Done(null)
                    is LaunchResult.Unavailable -> DeviceActionResult.Info(r.reason)
                }
            }
            DeviceLaunchAction.None -> if (device.configured) DeviceActionResult.NeedsSetup(device.id) else DeviceActionResult.Info("${device.name}: ${device.status.label}.")
        }
    }

    /** One-off TCP reachability check of a user-entered host — never a scan. */
    suspend fun probe(host: String, port: Int = 80, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }; true }.getOrDefault(false)
    }

    companion object {
        const val XBOX_ID = "xbox-series-x"

        /** Pure status resolution — unit tested. */
        fun resolve(configured: List<DeviceEntity>, tvInputs: List<TvInput>, p: PeripheralSnapshot, frameworkAvailable: Boolean): List<ConnectedDevice> {
            val inputsById = tvInputs.associateBy { it.id }
            val peersByAddr = p.peers.associateBy { it.address }
            val usedInputs = mutableSetOf<String>(); val usedPeers = mutableSetOf<String>()
            val result = configured.map { e ->
                val kind = runCatching { DeviceKind.valueOf(e.kind) }.getOrDefault(DeviceKind.Other)
                val action = runCatching { DeviceLaunchAction.valueOf(e.launchAction) }.getOrDefault(DeviceLaunchAction.None)
                var status = DeviceStatus.Shortcut
                var detail = e.hdmiPort?.let { "HDMI $it" } ?: "Not set up"
                e.inputId?.let { id ->
                    usedInputs += id
                    val input = inputsById[id]
                    when {
                        input != null -> { status = input.state; detail = input.hdmiPort?.let { "HDMI $it" } ?: input.label }
                        frameworkAvailable && tvInputs.isNotEmpty() -> status = DeviceStatus.Unavailable
                        else -> { status = DeviceStatus.Shortcut; detail = e.hdmiPort?.let { "HDMI $it" } ?: "Input shortcut" }
                    }
                }
                e.bluetoothAddress?.let { addr ->
                    usedPeers += addr
                    val peer = peersByAddr[addr]
                    status = when {
                        !p.bluetoothPermission || !p.bluetoothEnabled -> DeviceStatus.Shortcut
                        peer == null -> DeviceStatus.Unavailable
                        peer.connected -> DeviceStatus.Connected
                        else -> DeviceStatus.Paired
                    }
                    detail = "Bluetooth"
                }
                if (action == DeviceLaunchAction.OpenApp && e.inputId == null && e.bluetoothAddress == null) detail = "App shortcut"
                ConnectedDevice(
                    id = e.id, name = e.name, kind = kind, status = status, detail = detail, inputId = e.inputId, hdmiPort = e.hdmiPort,
                    bluetoothAddress = e.bluetoothAddress, artworkUri = e.artworkUri, launchAction = action, launchPackage = e.launchPackage,
                    networkHost = e.networkHost, configured = true, showOnHome = e.showOnHome,
                )
            }
            // Discovered Bluetooth audio devices and attached controllers that the user hasn't configured.
            val discoveredPeers = p.peers.filter { it.address !in usedPeers && it.kind in setOf(DeviceKind.Headphones, DeviceKind.Speaker, DeviceKind.Controller) }
                .map { it.toDevice() }
            val peerNames = p.peers.map { it.name.lowercase() }.toSet()
            val controllers = p.controllers.filter { it.name.lowercase() !in peerNames }.map {
                ConnectedDevice("ctrl:${it.descriptor}", it.name, DeviceKind.Controller, DeviceStatus.Connected, "Controller", configured = false)
            }
            return result + discoveredPeers + controllers
        }

        private fun BluetoothPeer.toDevice() = ConnectedDevice(
            id = "bt:$address", name = name, kind = kind,
            status = if (connected) DeviceStatus.Connected else DeviceStatus.Paired,
            detail = "Bluetooth", bluetoothAddress = address, configured = false,
        )
    }
}
