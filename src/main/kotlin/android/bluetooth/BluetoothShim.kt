package android.bluetooth

class BluetoothClass(val deviceClass: Int = 0)

class BluetoothDevice(
    val address: String = "",
    val name: String = "",
    val bluetoothClass: BluetoothClass? = null
)

class BluetoothAdapter {
    val bondedDevices: Set<BluetoothDevice> = emptySet()
}

class BluetoothManager {
    val adapter: BluetoothAdapter? = BluetoothAdapter()
}
