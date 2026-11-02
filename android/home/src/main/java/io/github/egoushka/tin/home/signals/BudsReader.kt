package io.github.egoushka.tin.home.signals

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Earbud battery of the device the audio is going to. The per-ear values are Bluetooth metadata keys 10 and 11
 * (`BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY`, hidden), the single value is `BluetoothDevice.getBatteryLevel`
 * (hidden); both are read by reflection and a missing API or a missing grant simply yields null.
 */
object BudsReader {
    private const val LEFT = 10
    private const val RIGHT = 11

    fun read(context: Context): Buds? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val audio = context.getSystemService(AudioManager::class.java)
        val out = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        } ?: return null
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(out.address) }.getOrNull() ?: return null
        val left = metadataPercent(device, LEFT)
        val right = metadataPercent(device, RIGHT)
        val single = percent(runCatching {
            BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
        }.getOrNull())
        return if (left == null && right == null && single == null) null else Buds(left, right, single)
    }

    private fun metadataPercent(device: BluetoothDevice, key: Int): Int? = percent(runCatching {
        val bytes = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType).invoke(device, key) as ByteArray?
        bytes?.toString(Charsets.UTF_8)?.trim()?.toInt()
    }.getOrNull())

    /** The platform reports -1 (and some firmware 0) when it has no reading. */
    fun percent(raw: Int?): Int? = raw?.takeIf { it in 1..100 }
}
