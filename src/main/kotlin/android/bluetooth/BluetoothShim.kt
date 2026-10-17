package android.bluetooth

class BluetoothClass(val deviceClass: Int = 0)

class BluetoothDevice(
    val address: String = "",
    val name: String = "",
    val bluetoothClass: BluetoothClass? = null
)

class BluetoothAdapter {
    /**
     * The Bluetooth headsets Windows exposes as capture devices, so watch/headset filtering in the app can see
     * them. Previously this was always empty, which hid every paired device.
     */
    val bondedDevices: Set<BluetoothDevice> by lazy {
        runCatching {
            android.media.AudioDevices.inputDevices()
                .filter { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                .map { BluetoothDevice(address = it.address, name = it.productName.toString(), bluetoothClass = null) }
                .toSet()
        }.getOrDefault(emptySet())
    }
}

class BluetoothManager {
    val adapter: BluetoothAdapter? = BluetoothAdapter()
}
