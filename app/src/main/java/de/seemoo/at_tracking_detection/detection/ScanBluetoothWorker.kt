package de.seemoo.at_tracking_detection.detection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.seemoo.at_tracking_detection.BuildConfig
import de.seemoo.at_tracking_detection.database.models.Beacon
import de.seemoo.at_tracking_detection.database.models.Scan
import de.seemoo.at_tracking_detection.database.models.Location as LocationModel
import de.seemoo.at_tracking_detection.database.models.device.BaseDevice
import de.seemoo.at_tracking_detection.database.models.device.DeviceManager
import de.seemoo.at_tracking_detection.database.repository.BeaconRepository
import de.seemoo.at_tracking_detection.database.repository.DeviceRepository
import de.seemoo.at_tracking_detection.database.repository.ScanRepository
import de.seemoo.at_tracking_detection.database.repository.LocationRepository
import de.seemoo.at_tracking_detection.notifications.NotificationService
import de.seemoo.at_tracking_detection.util.SharedPrefs
import de.seemoo.at_tracking_detection.util.Util
import de.seemoo.at_tracking_detection.util.ble.BLEScanCallback
import de.seemoo.at_tracking_detection.worker.BackgroundWorkScheduler
import de.seemoo.at_tracking_detection.detection.TrackingDetectorWorker.Companion.getLocation
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.LocalDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@HiltWorker
class ScanBluetoothWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val beaconRepository: BeaconRepository,
    private val deviceRepository: DeviceRepository,
    private val scanRepository: ScanRepository,
    private val locationRepository: LocationRepository,
    private val locationProvider: LocationProvider,
    private val notificationService: NotificationService,
    var backgroundWorkScheduler: BackgroundWorkScheduler
) :
    CoroutineWorker(appContext, workerParams) {

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var scanResultDictionary: HashMap<String, DiscoveredDevice> = HashMap()

    var location: Location? = null
        set(value) {
            field = value
            if (value != null) {
                locationRetrievedCallback?.let { it() }
            }
        }

    private var locationRetrievedCallback: (() -> Unit)? = null

    override suspend fun doWork(): Result {
        Timber.d("Bluetooth scanning worker started!")
        val scanMode = getScanMode()
        val scanId = scanRepository.insert(Scan(startDate = LocalDateTime.now(), isManual = false, scanMode = scanMode))

        if (!Util.checkBluetoothPermission()) {
            Timber.d("Permission to perform bluetooth scan missing")
            return Result.retry()
        }
        try {
            val bluetoothManager =
                applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
        } catch (e: Throwable) {
            Timber.e("BluetoothAdapter not found!")
            return Result.retry()
        }

        scanResultDictionary = HashMap()

        val useLocation = SharedPrefs.useLocationInTrackingDetection
        // TODO: this can possibly result in lots of useless null locations
        if (useLocation) {
            val lastLocation = locationProvider.getLastLocation()

            if (lastLocation != null) {
                // Location matches the requirement. No need to request a new one
                location = lastLocation
                Timber.d("Using last location")
            }else {
                //Getting the most accurate location here
                locationProvider.getCurrentLocation { loc ->
                    this.location = loc
                    Timber.d("Updated to current location")
                }
            }
        }

        //Starting BLE Scan
        Timber.d("Start Scanning for bluetooth le devices...")
        val scanSettings =
            ScanSettings.Builder().setScanMode(scanMode).build()

        SharedPrefs.isScanningInBackground = true
        BLEScanCallback.startScanning(bluetoothAdapter.bluetoothLeScanner, DeviceManager.scanFilter, scanSettings, leScanCallback)

        val scanDuration: Long = getScanDuration()
        delay(scanDuration)
        BLEScanCallback.stopScanning(bluetoothAdapter.bluetoothLeScanner)
        Timber.d("Scanning for bluetooth le devices stopped!. Discovered ${scanResultDictionary.size} devices")

        //Waiting for updated location to come in
        val fetchedLocation = waitForRequestedLocation()
        Timber.d("Fetched location? $fetchedLocation")

        //Adding all scan results to the database after the scan has finished
        scanResultDictionary.forEach { (_, discoveredDevice) ->
            insertScanResult(
                discoveredDevice.scanResult,
                location?.latitude,
                location?.longitude,
                location?.accuracy,
                discoveredDevice.discoveryDate,
                beaconRepository,
                deviceRepository,
                locationRepository,
            )
        }

        SharedPrefs.lastScanDate = LocalDateTime.now()
        SharedPrefs.isScanningInBackground = false
        val scan = scanRepository.scanWithId(scanId.toInt())
        if (scan != null) {
            scan.endDate = LocalDateTime.now()
            scan.duration = scanDuration.toInt() / 1000
            scan.noDevicesFound = scanResultDictionary.size
            scanRepository.update(scan)
        }

        Timber.d("Scheduling tracking detector worker")
        backgroundWorkScheduler.scheduleTrackingDetector()
        BackgroundWorkScheduler.scheduleAlarmWakeupIfScansFail()

        return Result.success(
            Data.Builder()
                .putLong("duration", scanDuration)
                .putInt("mode", scanMode)
                .putInt("devicesFound", scanResultDictionary.size)
                .build()
        )
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
            super.onScanResult(callbackType, scanResult)
            //Checks if the device has been found already
            if (!scanResultDictionary.containsKey(scanResult.device.address)) {
                Timber.d("Found ${scanResult.device.address} at ${LocalDateTime.now()}")
                scanResultDictionary[scanResult.device.address] =
                    DiscoveredDevice(scanResult, LocalDateTime.now())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Timber.e("Bluetooth scan failed $errorCode")
            if (BuildConfig.DEBUG) {
                notificationService.sendBLEErrorNotification()
            }
        }
    }

    private fun getScanMode(): Int {
        val useLowPower = SharedPrefs.useLowPowerBLEScan
        return if (useLowPower) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        }
    }

    private fun getScanDuration(): Long {
        val useLowPower = SharedPrefs.useLowPowerBLEScan
        return if (useLowPower) {
            15000L
        } else {
            8000L
        }
    }

    private suspend fun waitForRequestedLocation(): Boolean {
        if (location != null || !SharedPrefs.useLocationInTrackingDetection) {
            //Location already there. Just return
            return true
        }

        return suspendCoroutine { cont ->
            var coroutineFinished = false

            val handler = Handler(Looper.getMainLooper())
            val runnable = Runnable {
                if (!coroutineFinished) {
                    coroutineFinished = true
                    locationRetrievedCallback = null
                    Timber.d("Could not get location update in time.")
                    cont.resume(false)
                }
            }

            locationRetrievedCallback = {
                if (!coroutineFinished) {
                    handler.removeCallbacks(runnable)
                    coroutineFinished = true
                    cont.resume(true)
                }
            }

            // Fallback if no location is fetched in time
            handler.postDelayed(runnable, 8000)

        }
    }

    class DiscoveredDevice(var scanResult: ScanResult, var discoveryDate: LocalDateTime) {}

    companion object {
        const val MAX_DISTANCE_UNTIL_NEW_LOCATION: Float = 150f // in meters

        suspend fun insertScanResult(
            scanResult: ScanResult,
            latitude: Double?,
            longitude: Double?,
            accuracy: Float?,
            discoveryDate: LocalDateTime,
            beaconRepository: BeaconRepository,
            deviceRepository: DeviceRepository,
            locationRepository: LocationRepository,
        ) {
            saveDevice(deviceRepository, scanResult, discoveryDate)

            // set locationId to null if gps location could not be retrieved
            val locId: Int? = saveLocation(locationRepository, latitude, longitude, discoveryDate, accuracy)?.locationId

            saveBeacon(beaconRepository, scanResult, discoveryDate, locId)
        }

        private suspend fun saveBeacon(
            beaconRepository: BeaconRepository,
            scanResult: ScanResult,
            discoveryDate: LocalDateTime,
            locId: Int?
        ): Beacon {
            val uuids = scanResult.scanRecord?.serviceUuids?.map { it.toString() }?.toList()
            val beacon =
                if (BuildConfig.DEBUG) { // TODO: maybe change this, because we need service data for samsung
                    // Save the manufacturer data to the beacon
                    Beacon(
                        /* discoveryDate, scanResult.rssi, scanResult.device.address, locationId,
                    scanResult.scanRecord?.bytes, uuids */
                        discoveryDate, scanResult.rssi, scanResult.device.address, locId,
                        scanResult.scanRecord?.bytes, uuids
                    )
                } else {
                    Beacon(
                        /* discoveryDate, scanResult.rssi, scanResult.device.address, locationId,
                    null, uuids*/
                        discoveryDate, scanResult.rssi, scanResult.device.address, locId,
                        null, uuids
                    )
                }

            beaconRepository.insert(beacon)
            return beacon
        }

        private suspend fun saveDevice(
            deviceRepository: DeviceRepository,
            scanResult: ScanResult,
            discoveryDate: LocalDateTime
        ): BaseDevice {
            var device = deviceRepository.getDevice(scanResult.device.address)
            if (device == null) {
                Timber.d("Add new Device to the database!")
                device = BaseDevice(scanResult)
                deviceRepository.insert(device)
            } else {
                Timber.d("Device already in the database... Updating the last seen date!")
                device.lastSeen = discoveryDate
                deviceRepository.update(device)
            }

            Timber.d("Device: $device")
            return device
        }

        private suspend fun saveLocation(
            locationRepository: LocationRepository,
            latitude: Double?,
            longitude: Double?,
            discoveryDate: LocalDateTime,
            accuracy: Float?
        ): LocationModel? {
            // set location to null if gps location could not be retrieved
            var location: LocationModel? = null

            if (latitude != null && longitude != null) {
                // Get closest location from database
                location = locationRepository.closestLocation(latitude, longitude)

                var distanceBetweenLocations: Float = Float.MAX_VALUE

                if (location != null) {
                    val locationA = getLocation(latitude, longitude)
                    val locationB = getLocation(location.latitude, location.longitude)
                    distanceBetweenLocations = locationA.distanceTo(locationB)
                }

                if (location == null || distanceBetweenLocations > MAX_DISTANCE_UNTIL_NEW_LOCATION) {
                    // Create new location entry
                    Timber.d("Add new Location to the database!")
                    location = LocationModel(discoveryDate, longitude, latitude, accuracy)
                    locationRepository.insert(location)
                } else {
                    // If location is within the set limit, just use that location and update lastSeen
                    Timber.d("Location already in the database... Updating the last seen date!")
                    location.lastSeen = discoveryDate
                    locationRepository.update(location)

                }

                Timber.d("Location: $location")
            }
            return location
        }
    }
}