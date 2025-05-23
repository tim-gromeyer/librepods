/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
 *
 * Copyright (C) 2025 LibrePods Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.AirPodsNotifications
import me.kavishdevar.librepods.utils.Battery
import me.kavishdevar.librepods.utils.BatteryComponent
import me.kavishdevar.librepods.utils.BatteryStatus
import me.kavishdevar.librepods.utils.CrossDevice
import me.kavishdevar.librepods.utils.CrossDevicePackets
import me.kavishdevar.librepods.utils.Enums
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.IslandType
import me.kavishdevar.librepods.utils.IslandWindow
import me.kavishdevar.librepods.utils.LongPressPackets
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.PopupWindow
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.isHeadTrackingData
import me.kavishdevar.librepods.widgets.BatteryWidget
import me.kavishdevar.librepods.widgets.NoiseControlWidget
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ServiceManager {
    private var service: AirPodsService? = null

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Synchronized
    fun restartService(context: Context) {
        service?.stopSelf()
        Log.d("ServiceManager", "Restarting service, service is null: ${service == null}")
        val intent = Intent(context, AirPodsService::class.java)
        context.stopService(intent)
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            context.startService(intent)
            context.startActivity(Intent(context, MainActivity::class.java))
            service?.clearLogs()
        }
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var personalizedVolume: Boolean = false,
        var longPressNC: Boolean = true,
        var offListeningMode: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var singleANC: Boolean = true,
        var longPressTransparency: Boolean = true,
        var conversationalAwareness: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var longPressAdaptive: Boolean = true,
        var loudSoundReduction: Boolean = true,
        var longPressOff: Boolean = false,
        var volumeControl: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var adaptiveStrength: Int = 51,
        var toneVolume: Int = 75,
        var conversationalAwarenessVolume: Int = 43,
        var textColor: Long = -1L,
        var qsClickBehavior: String = "cycle"
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet())
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        initializeConfig()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false),
            personalizedVolume = sharedPreferences.getBoolean("personalized_volume", false),
            longPressNC = sharedPreferences.getBoolean("long_press_nc", true),
            offListeningMode = sharedPreferences.getBoolean("off_listening_mode", false),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean("show_phone_battery_in_widget", true),
            singleANC = sharedPreferences.getBoolean("single_anc", true),
            longPressTransparency = sharedPreferences.getBoolean("long_press_transparency", true),
            conversationalAwareness = sharedPreferences.getBoolean("conversational_awareness", true),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", true),
            longPressAdaptive = sharedPreferences.getBoolean("long_press_adaptive", true),
            loudSoundReduction = sharedPreferences.getBoolean("loud_sound_reduction", true),
            longPressOff = sharedPreferences.getBoolean("long_press_off", false),
            volumeControl = sharedPreferences.getBoolean("volume_control", true),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean("disconnect_when_not_wearing", false),
            adaptiveStrength = sharedPreferences.getInt("adaptive_strength", 51),
            toneVolume = sharedPreferences.getInt("tone_volume", 75),
            conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", 43),
            textColor = sharedPreferences.getLong("textColor", -1L),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle"
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when(key) {
            "name" -> config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
            "automatic_ear_detection" -> config.earDetectionEnabled = preferences.getBoolean(key, true)
            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic = preferences.getBoolean(key, false)
            "personalized_volume" -> config.personalizedVolume = preferences.getBoolean(key, false)
            "long_press_nc" -> config.longPressNC = preferences.getBoolean(key, true)
            "off_listening_mode" -> {
                config.offListeningMode = preferences.getBoolean(key, false)
                updateNoiseControlWidget()
            }
            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updateBattery()
            }
            "single_anc" -> config.singleANC = preferences.getBoolean(key, true)
            "long_press_transparency" -> config.longPressTransparency = preferences.getBoolean(key, true)
            "conversational_awareness" -> config.conversationalAwareness = preferences.getBoolean(key, true)
            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume = preferences.getBoolean(key, true)
            "long_press_adaptive" -> config.longPressAdaptive = preferences.getBoolean(key, true)
            "loud_sound_reduction" -> config.loudSoundReduction = preferences.getBoolean(key, true)
            "long_press_off" -> config.longPressOff = preferences.getBoolean(key, false)
            "volume_control" -> config.volumeControl = preferences.getBoolean(key, true)
            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing = preferences.getBoolean(key, false)
            "adaptive_strength" -> config.adaptiveStrength = preferences.getInt(key, 51)
            "tone_volume" -> config.toneVolume = preferences.getInt(key, 75)
            "conversational_awareness_volume" -> config.conversationalAwarenessVolume = preferences.getInt(key, 43)
            "textColor" -> config.textColor = preferences.getLong(key, -1L)
            "qs_click_behavior" -> config.qsClickBehavior = preferences.getString(key, "cycle") ?: "cycle"
        }

        if (key == "mac_address") {
            macAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next()?.let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs = sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    fun getPacketLogs(): Set<String> {
        return inMemoryLogs.toSet()
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false

    fun showPopup(service: Service, name: String) {
        if (!Settings.canDrawOverlays(service)) {
            Log.d("AirPodsService", "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupWindow.open(name, batteryNotification)
        popupShown = true
    }
    var islandOpen = false
    var islandWindow: IslandWindow? = null
    @SuppressLint("MissingPermission")
    fun showIsland(service: Service, batteryPercentage: Int, type: IslandType = IslandType.CONNECTED) {
        Log.d("AirPodsService", "Showing island window")
        if (!Settings.canDrawOverlays(service)) {
            Log.d("AirPodsService", "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(sharedPreferences.getString("name", "AirPods Pro").toString(), batteryPercentage, this@AirPodsService, type)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    @Suppress("ClassName")
    private object bluetoothReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        "android.bluetooth.device.extra.DEVICE",
                        BluetoothDevice::class.java
                    )
                } else {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
                }
            val action = intent.action
            val context = context?.applicationContext
            val name = context?.getSharedPreferences("settings", MODE_PRIVATE)
                ?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && action != null && !action.isEmpty()) {
                Log.d("AirPodsService", "Received bluetooth connection broadcast")
                if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    if (ServiceManager.getService()?.isConnectedLocally == true) {
                        ServiceManager.getService()?.manuallyCheckForAudioSource()
                        return
                    }
                    val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
                    bluetoothDevice.fetchUuidsWithSdp()
                    if (bluetoothDevice.uuids != null) {
                        if (bluetoothDevice.uuids.contains(uuid)) {
                            val intent =
                                Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                            intent.putExtra("name", name)
                            intent.putExtra("device", bluetoothDevice)
                            context?.sendBroadcast(intent)
                        }
                    }
                }
            }
        }
    }

    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    var widgetMobileBatteryEnabled = false

    object BatteryChangedIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                ServiceManager.getService()?.updateBattery()
            } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                try {
                    context?.unregisterReceiver(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun setPhoneBatteryInWidget(enabled: Boolean) {
        widgetMobileBatteryEnabled = enabled
        updateBattery()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_LOW
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods Socket Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = android.graphics.Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
        }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. " +
                         "Error: $errorMessage"))
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
        })
    }

    fun sendBatteryBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        // Update widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also {
            val openActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(
                R.id.left_battery_widget,
                leftBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.left_battery_progress,
                100,
                leftBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(
                R.id.right_battery_widget,
                rightBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.right_battery_progress,
                100,
                rightBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(
                R.id.case_battery_widget,
                caseBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.case_battery_progress,
                100,
                caseBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService<BatteryManager>(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget,
                    "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon,
                    if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress,
                    100,
                    batteryLevel,
                    false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)

        // set metadata
        device?.let {
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.CASE}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
        }

        // broadcast
//        broadcastBatteryInformation()
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.noise_control_widget).also {
            val ancStatus = ancNotification.status
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (config.offListeningMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (config.offListeningMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button,
                if (config.offListeningMode) View.VISIBLE else View.GONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (config.offListeningMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (config.offListeningMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean,
        airpodsName: String? = null,
        batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        var updatedNotification: Notification? = null

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (connected && socket.isConnected) {
            updatedNotification = NotificationCompat.Builder(this, "airpods_connection_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle(airpodsName ?: config.deviceName)
                .setContentText(
                    """${
                        batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    }""")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            notificationManager.notify(2, updatedNotification)

            notificationManager.cancel(1)
        } else if (!connected) {
            updatedNotification = NotificationCompat.Builder(this, "background_service_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle("AirPods not connected")
                .setContentText("Tap to open app")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            notificationManager.notify(1, updatedNotification)
            notificationManager.cancel(2)
        } else if (!socket.isConnected && isConnectedLocally) {
            Log.d("AirPodsService", "<LogCollector:Complete:Failed> Socket not connected")
            showSocketConnectionFailureNotification("Socket created, but not connected. Is the Bluetooth process hooked?")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                } else {
                    rejectCall()
                }
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresApi(Build.VERSION_CODES.Q)

    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) {
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
    }
    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall()
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod = telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }
    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                }
            } else {
                val telephonyService = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        gestureDetector?.processHeadOrientation(horizontal, vertical)
    }

    private lateinit var connectionReceiver: BroadcastReceiver
    private lateinit var disconnectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId))
                .build()
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1
    private val APPLE = 0x004C
    private val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @Suppress("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100,
            rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        val isChargingMain = isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV)
            putExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, android.bluetooth.BluetoothHeadset.AT_CMD_TYPE_SET)
            putExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${android.bluetooth.BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e("AirPodsService", "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e("AirPodsService", "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e("AirPodsService", "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d("AirPodsService", "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        d.let{ device ->
            val metadataSet = SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MAIN_ICON,
                    resToUri(R.drawable.pro_2).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MODEL_NAME,
                    "AirPods Pro (2 Gen.)".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(R.drawable.pro_2_case).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(R.drawable.pro_2_right).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(R.drawable.pro_2_left).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    "Apple".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_COMPANION_APP,
                    "me.kavisdevar.librepods".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
            Log.d("AirPodsService", "Metadata set: $metadataSet")
        }
    }
    val ancModeFilter = IntentFilter("me.kavishdevar.librepods.SET_ANC_MODE")
    var ancModeReceiver: BroadcastReceiver? = null
    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AirPodsService", "Service started")
        ServiceManager.setService(this)
        startForegroundNotification()
        initGestureDetector()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            val editor = edit()

            if (!contains("conversational_awareness_pause_music")) editor.putBoolean("conversational_awareness_pause_music", false)
            if (!contains("personalized_volume")) editor.putBoolean("personalized_volume", false)
            if (!contains("automatic_ear_detection")) editor.putBoolean("automatic_ear_detection", true)
            if (!contains("long_press_nc")) editor.putBoolean("long_press_nc", true)
            if (!contains("off_listening_mode")) editor.putBoolean("off_listening_mode", false)
            if (!contains("show_phone_battery_in_widget")) editor.putBoolean("show_phone_battery_in_widget", true)
            if (!contains("single_anc")) editor.putBoolean("single_anc", true)
            if (!contains("long_press_transparency")) editor.putBoolean("long_press_transparency", true)
            if (!contains("conversational_awareness")) editor.putBoolean("conversational_awareness", true)
            if (!contains("relative_conversational_awareness_volume")) editor.putBoolean("relative_conversational_awareness_volume", true)
            if (!contains("long_press_adaptive")) editor.putBoolean("long_press_adaptive", true)
            if (!contains("loud_sound_reduction")) editor.putBoolean("loud_sound_reduction", true)
            if (!contains("long_press_off")) editor.putBoolean("long_press_off", false)
            if (!contains("volume_control")) editor.putBoolean("volume_control", true)
            if (!contains("head_gestures")) editor.putBoolean("head_gestures", true)
            if (!contains("disconnect_when_not_wearing")) editor.putBoolean("disconnect_when_not_wearing", false)

            if (!contains("adaptive_strength")) editor.putInt("adaptive_strength", 51)
            if (!contains("tone_volume")) editor.putInt("tone_volume", 75)
            if (!contains("conversational_awareness_volume")) editor.putInt("conversational_awareness_volume", 43)

            if (!contains("textColor")) editor.putLong("textColor", -1L)

            if (!contains("qs_click_behavior")) editor.putString("qs_click_behavior", "cycle")
            if (!contains("name")) editor.putString("name", "AirPods")

            editor.apply()
        }

        initializeConfig()

        ancModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            setANCMode(mode)
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val offListeningMode = config.offListeningMode

                        val nextMode = if (offListeningMode) {
                            when (currentMode) {
                                1 -> 2
                                2 -> 3
                                3 -> 4
                                4 -> 1
                                else -> 1
                            }
                        } else {
                            when (currentMode) {
                                1 -> 2
                                2 -> 3
                                3 -> 4
                                4 -> 2
                                else -> 2
                            }
                        }

                        setANCMode(nextMode)
                        Log.d("AirPodsService", "Cycling ANC mode from $currentMode to $nextMode (offListeningMode: $offListeningMode)")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ancModeReceiver, ancModeFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(ancModeReceiver, ancModeFilter)
        }
        val audioManager =
            this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager,
            this@AirPodsService.getSharedPreferences(
                "settings",
                MODE_PRIVATE
            )
        )
        Log.d("AirPodsService", "Initializing CrossDevice")
        CoroutineScope(Dispatchers.IO).launch {
            CrossDevice.init(this@AirPodsService)
            Log.d("AirPodsService", "CrossDevice initialized")
        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            @SuppressLint("SwitchIntDef")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        if (CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) CoroutineScope(Dispatchers.IO).launch {
                            takeOver()
                        }
                        if (config.headGestures) {
                            callNumber = phoneNumber
                            handleIncomingCall()
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) CoroutineScope(
                            Dispatchers.IO).launch {
                                takeOver()
                        }
                        isInCall = true
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        callNumber = null
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            val batteryChangedIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            batteryChangedIntentFilter.addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    BatteryChangedIntentReceiver,
                    batteryChangedIntentFilter,
                    RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(BatteryChangedIntentReceiver, batteryChangedIntentFilter)
            }
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)!!
                    } else {
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }

                    if (config.deviceName == "AirPods" && device?.name != null) {
                        config.deviceName = device?.name ?: "AirPods"
                        sharedPreferences.edit { putString("name", config.deviceName) }
                    }

                    Log.d("AirPodsCrossDevice", CrossDevice.isAvailable.toString())
                    if (!CrossDevice.isAvailable) {
                        Log.d("AirPodsService", "${config.deviceName} connected")
                        showPopup(this@AirPodsService, config.deviceName)
                        CoroutineScope(Dispatchers.IO).launch {
                            connectToSocket(device!!)
                        }
                        Log.d("AirPodsService", "Setting metadata")
                        setMetadatas(device!!)
                        isConnectedLocally = true
                        macAddress = device!!.address
                        sharedPreferences.edit {
                            putString("mac_address", macAddress)
                        }
                    }
                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    device = null
                    isConnectedLocally = false
                    popupShown = false
                    updateNotificationContent(false)
                }
            }
        }
         val showIslandReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(this@AirPodsService, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!))
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(showIslandReceiver, showIslandIntentFilter)
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, deviceIntentFilter)
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter.bondedDevices.forEach { device ->
            device.fetchUuidsWithSdp()
            if (device.uuids != null) {
                if (device.uuids.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))) {
                    bluetoothAdapter.getProfileProxy(
                        this,
                        object : BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                if (profile == BluetoothProfile.A2DP) {
                                    val connectedDevices = proxy.connectedDevices
                                    if (connectedDevices.isNotEmpty()) {
                                        if (!CrossDevice.isAvailable) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                connectToSocket(device)
                                            }
                                            setMetadatas(device)
                                            macAddress = device.address
                                            sharedPreferences.edit {
                                                putString("mac_address", macAddress)
                                            }
                                        }
                                        this@AirPodsService.sendBroadcast(
                                            Intent(AirPodsNotifications.AIRPODS_CONNECTED)
                                        )
                                    }
                                }
                                bluetoothAdapter.closeProfileProxy(profile, proxy)
                            }

                            override fun onServiceDisconnected(profile: Int) {}
                        },
                        BluetoothProfile.A2DP
                    )
                }
            }
        }

        if (!isConnectedLocally && !CrossDevice.isAvailable) {
            clearPacketLogs()
        }

        return START_STICKY
    }

    private lateinit var socket: BluetoothSocket

    fun manuallyCheckForAudioSource() {
        if (earDetectionNotification.status[0] != 0.toByte() && earDetectionNotification.status[1] != 0.toByte()) {
            Log.d(
                "AirPodsService",
                "For some reason, Android connected to the audio profile itself even after disconnecting. Disconnecting audio profile again!"
            )
            disconnectAudio(this, device)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
     fun takeOver() {
        Log.d("AirPodsService", "Taking over audio")
        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d("AirPodsService", macAddress)
        CrossDevice.isAvailable = false
        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        device = getSystemService<BluetoothManager>(BluetoothManager::class.java).adapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            connectToSocket(device!!)
            connectAudio(this, device)
        }
        showIsland(this, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!),
            IslandType.TAKING_OVER)

        isConnectedLocally = true
        CrossDevice.isAvailable = false
    }

    private fun createBluetoothSocket(device: BluetoothDevice, uuid: ParcelUuid): BluetoothSocket {
        val type = 3 // L2CAP
        val constructorSpecs = listOf(
            arrayOf(device, type, true, true, 0x1001, uuid),
            arrayOf(device, type, 1, true, true, 0x1001, uuid),
            arrayOf(type, 1, true, true, device, 0x1001, uuid),
            arrayOf(type, true, true, device, 0x1001, uuid)
        )

        val constructors = BluetoothSocket::class.java.declaredConstructors
        Log.d("AirPodsService", "BluetoothSocket has ${constructors.size} constructors:")

        constructors.forEachIndexed { index, constructor ->
            val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d("AirPodsService", "Constructor $index: ($params)")
        }

        var lastException: Exception? = null
        var attemptedConstructors = 0

        for ((index, params) in constructorSpecs.withIndex()) {
            try {
                Log.d("AirPodsService", "Trying constructor signature #${index + 1}")
                attemptedConstructors++
                return HiddenApiBypass.newInstance(BluetoothSocket::class.java, *params) as BluetoothSocket
            } catch (e: Exception) {
                Log.e("AirPodsService", "Constructor signature #${index + 1} failed: ${e.message}")
                lastException = e
            }
        }

        val errorMessage = "Failed to create BluetoothSocket after trying $attemptedConstructors constructor signatures"
        Log.e("AirPodsService", errorMessage)
        showSocketConnectionFailureNotification(errorMessage)
        throw lastException ?: IllegalStateException(errorMessage)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(device: BluetoothDevice) {
        Log.d("AirPodsService", "<LogCollector:Start> Connecting to socket")
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        if (isConnectedLocally != true && !CrossDevice.isAvailable) {
            socket = try {
                createBluetoothSocket(device, uuid)
            } catch (e: Exception) {
                Log.e("AirPodsService", "Failed to create BluetoothSocket: ${e.message}")
                showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.message}")
                return
            }

            try {
                runBlocking {
                    withTimeout(5000L) {
                        try {
                            socket.connect()
                            isConnectedLocally = true
                            this@AirPodsService.device = device
                            updateNotificationContent(
                                true,
                                config.deviceName,
                                batteryNotification.getBattery()
                            )
                            Log.d("AirPodsService", "<LogCollector:Complete:Success> Socket connected")
                        } catch (e: Exception) {
                            Log.d("AirPodsService", "<LogCollector:Complete:Failed> Socket not connected")
                            showSocketConnectionFailureNotification("Socket created, but not connected. Is the Bluetooth process hooked?")
                            throw e
                        }
                    }
                    if (!socket.isConnected) {
                        Log.d("AirPodsService", "<LogCollector:Complete:Failed> Socket not connected")
                        showSocketConnectionFailureNotification("Socket created, but not connected. Is the Bluetooth process hooked?")
                    }
                }
                this@AirPodsService.device = device
                socket.let { it ->
                    it.outputStream.write(Enums.HANDSHAKE.value)
                    it.outputStream.flush()
                    it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                    it.outputStream.flush()
                    it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                    it.outputStream.flush()
                    CoroutineScope(Dispatchers.IO).launch {
                        it.outputStream.write(Enums.HANDSHAKE.value)
                        it.outputStream.flush()
                        delay(200)
                        it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                        it.outputStream.flush()
                        delay(200)
                        it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                        it.outputStream.flush()
                        delay(200)
                        it.outputStream.write(Enums.START_HEAD_TRACKING.value)
                        it.outputStream.flush()
                        Handler(Looper.getMainLooper()).postDelayed({
                            it.outputStream.write(Enums.HANDSHAKE.value)
                            it.outputStream.flush()
                            it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                            it.outputStream.flush()
                            it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                            it.outputStream.flush()
                            it.outputStream.write(Enums.STOP_HEAD_TRACKING.value)
                            it.outputStream.flush()
                        }, 5000)
                        sendBroadcast(
                            Intent(AirPodsNotifications.AIRPODS_CONNECTED)
                                .putExtra("device", device)
                        )
                        while (socket.isConnected == true) {
                            socket.let {
                                val buffer = ByteArray(1024)
                                val bytesRead = it.inputStream.read(buffer)
                                var data: ByteArray = byteArrayOf()
                                if (bytesRead > 0) {
                                    data = buffer.copyOfRange(0, bytesRead)
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                        putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    })
                                    val bytes = buffer.copyOfRange(0, bytesRead)
                                    val formattedHex = bytes.joinToString(" ") { "%02X".format(it) }
                                    CrossDevice.sendReceivedPacket(bytes)
                                    updateNotificationContent(
                                        true,
                                        sharedPreferences.getString("name", device.name),
                                        batteryNotification.getBattery()
                                    )
                                    if (!isHeadTrackingData(data)) {
                                        Log.d("AirPods Data", "Data received: $formattedHex")
                                        logPacket(data, "AirPods")
                                    }
                                } else if (bytesRead == -1) {
                                    Log.d("AirPods Service", "Socket closed (bytesRead = -1)")
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED))
                                    return@launch
                                }
                                var inEar = false
                                var inEarData = listOf<Boolean>()
                                processData(data)
                                if (earDetectionNotification.isEarDetectionData(data)) {
                                    earDetectionNotification.setStatus(data)
                                    sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                                        val list = earDetectionNotification.status
                                        val bytes = ByteArray(2)
                                        bytes[0] = list[0]
                                        bytes[1] = list[1]
                                        putExtra("data", bytes)
                                    })
                                    Log.d(
                                        "AirPods Parser",
                                        "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                                    )
                                    var justEnabledA2dp = false
                                    earReceiver = object : BroadcastReceiver() {
                                        override fun onReceive(context: Context, intent: Intent) {
                                            val data = intent.getByteArrayExtra("data")
                                            if (data != null && config.earDetectionEnabled) {
                                                inEar =
                                                    if (data.find { it == 0x02.toByte() } != null || data.find { it == 0x03.toByte() } != null) {
                                                        data[0] == 0x00.toByte() || data[1] == 0x00.toByte()
                                                    } else {
                                                        data[0] == 0x00.toByte() && data[1] == 0x00.toByte()
                                                    }
                                                val newInEarData = listOf(
                                                    data[0] == 0x00.toByte(),
                                                    data[1] == 0x00.toByte()
                                                )
                                                if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(false, false) && islandWindow?.isVisible != true) {
                                                    showIsland(this@AirPodsService, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!))
                                                }
                                                if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                                                    islandWindow?.close()
                                                }
                                                if (newInEarData.contains(true) && inEarData == listOf(
                                                        false,
                                                        false
                                                    )
                                                ) {
                                                    connectAudio(this@AirPodsService, device)
                                                    justEnabledA2dp = true
                                                    val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
                                                        override fun onReceive(context: Context, intent: Intent) {
                                                            if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                                                                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                                                                val previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)
                                                                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                                                                Log.d("MediaController", "A2DP state changed: $previousState -> $state for device: ${device?.address}")

                                                                if (state == BluetoothProfile.STATE_CONNECTED &&
                                                                    previousState != BluetoothProfile.STATE_CONNECTED &&
                                                                    device?.address == this@AirPodsService.device?.address) {

                                                                    Log.d("MediaController", "A2DP connected, sending play command")
                                                                    MediaController.sendPlay()
                                                                    MediaController.iPausedTheMedia = false

                                                                    context.unregisterReceiver(this)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    val a2dpIntentFilter = IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                        registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
                                                    } else {
                                                        registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
                                                    }
                                                } else if (newInEarData == listOf(false, false)) {
                                                    MediaController.sendPause(force = true)
                                                    if (config.disconnectWhenNotWearing) {
                                                        disconnectAudio(this@AirPodsService, device)
                                                    }
                                                }

                                                if (inEarData.contains(false) && newInEarData == listOf(
                                                        true,
                                                        true
                                                    )
                                                ) {
                                                    Log.d(
                                                        "AirPods Parser",
                                                        "User put in both AirPods from just one."
                                                    )
                                                    MediaController.userPlayedTheMedia = false
                                                }
                                                if (newInEarData.contains(false) && inEarData == listOf(
                                                        true,
                                                        true
                                                    )
                                                ) {
                                                    Log.d(
                                                        "AirPods Parser",
                                                        "User took one of two out."
                                                    )
                                                    MediaController.userPlayedTheMedia = false
                                                }

                                                Log.d(
                                                    "AirPods Parser",
                                                    "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
                                                )
                                                if (newInEarData.sorted() == inEarData.sorted()) {
                                                    Log.d("AirPods Parser", "hi")
                                                    return
                                                }
                                                Log.d(
                                                    "AirPods Parser",
                                                    "this shouldn't be run if the last log was 'hi'."
                                                )

                                                inEarData = newInEarData

                                                if (inEar == true) {
                                                    if (!justEnabledA2dp) {
                                                        justEnabledA2dp = false
                                                        MediaController.sendPlay()
                                                        MediaController.iPausedTheMedia = false
                                                    }
                                                } else {
                                                    MediaController.sendPause()
                                                }
                                            }
                                        }
                                    }

                                    val earIntentFilter =
                                        IntentFilter(AirPodsNotifications.EAR_DETECTION_DATA)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        this@AirPodsService.registerReceiver(
                                            earReceiver, earIntentFilter,
                                            RECEIVER_EXPORTED
                                        )
                                    } else {
                                        this@AirPodsService.registerReceiver(
                                            earReceiver,
                                            earIntentFilter
                                        )
                                    }
                                } else if (ancNotification.isANCData(data)) {
                                    CrossDevice.sendRemotePacket(data)
                                    CrossDevice.ancBytes = data
                                    ancNotification.setStatus(data)
                                    sendANCBroadcast()
                                    updateNoiseControlWidget()
                                    Log.d("AirPods Parser", "ANC: ${ancNotification.status}")
                                } else if (batteryNotification.isBatteryData(data)) {
                                    CrossDevice.sendRemotePacket(data)
                                    CrossDevice.batteryBytes = data
                                    batteryNotification.setBattery(data)
                                    sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                                        putParcelableArrayListExtra(
                                            "data",
                                            ArrayList(batteryNotification.getBattery())
                                        )
                                    })
                                    updateBattery()
                                    updateNotificationContent(
                                        true,
                                        this@AirPodsService.getSharedPreferences(
                                            "settings",
                                            MODE_PRIVATE
                                        ).getString("name", device.name),
                                        batteryNotification.getBattery()
                                    )
                                    for (battery in batteryNotification.getBattery()) {
                                        Log.d(
                                            "AirPods Parser",
                                            "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                                        )
                                    }
                                    if (batteryNotification.getBattery()[0].status == 1 && batteryNotification.getBattery()[1].status == 1) {
                                        disconnectAudio(this@AirPodsService, device)
                                    } else {
                                        connectAudio(this@AirPodsService, device)
                                    }
                                } else if (conversationAwarenessNotification.isConversationalAwarenessData(
                                        data
                                    )
                                ) {
                                    conversationAwarenessNotification.setData(data)
                                    sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                                        putExtra("data", conversationAwarenessNotification.status)
                                    })


                                    if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                                        MediaController.startSpeaking()
                                    } else if (conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                                        MediaController.stopSpeaking()
                                    }

                                    Log.d(
                                        "AirPods Parser",
                                        "Conversation Awareness: ${conversationAwarenessNotification.status}"
                                    )
                                }
                                else if (isHeadTrackingData(data)) {
                                    processHeadTrackingData(data)
                                }
                            }
                        }
                        Log.d("AirPods Service", "Socket closed")
                        isConnectedLocally = false
                        socket.close()
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.d("AirPodsService", "Failed to connect to socket: ${e.message}")
                showSocketConnectionFailureNotification("Failed to establish connection: ${e.message}")
                isConnectedLocally = false
                this@AirPodsService.device = device
                updateNotificationContent(false)
            }
        }
    }

    fun disconnect() {
        if (!this::socket.isInitialized) return
        socket.close()
        MediaController.pausedForCrossDevice = false
        Log.d("AirPodsService", "Disconnected from AirPods, showing island.")
        showIsland(this, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!),
            IslandType.MOVED_TO_REMOTE)
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        isConnectedLocally = false
        CrossDevice.isAvailable = true
    }

    fun sendPacket(packet: String) {
        val fromHex = packet.split(" ").map { it.toInt(16).toByte() }
        try {
            logPacket(fromHex.toByteArray(), "Sent")

            if (!isConnectedLocally && CrossDevice.isAvailable) {
                CrossDevice.sendRemotePacket(CrossDevicePackets.AIRPODS_DATA_HEADER.packet + fromHex.toByteArray())
                return
            }
            if (this::socket.isInitialized && socket.isConnected && socket.outputStream != null) {
                val byteArray = fromHex.toByteArray()
                socket.outputStream?.write(byteArray)
                socket.outputStream?.flush()
            } else {
                Log.d("AirPodsService", "Can't send packet: Socket not initialized or connected")
            }
        } catch (e: Exception) {
            Log.e("AirPodsService", "Error sending packet: ${e.message}")
        }
    }

    fun sendPacket(packet: ByteArray) {
        try {
            logPacket(packet, "Sent")

            if (!isConnectedLocally && CrossDevice.isAvailable) {
                CrossDevice.sendRemotePacket(CrossDevicePackets.AIRPODS_DATA_HEADER.packet + packet)
                return
            }
            if (this::socket.isInitialized && socket.isConnected && socket.outputStream != null && isConnectedLocally) {
                socket.outputStream?.write(packet)
                socket.outputStream?.flush()
            } else {
                Log.d("AirPodsService", "Can't send packet: Socket not initialized or connected")
            }
        } catch (e: Exception) {
            Log.e("AirPodsService", "Error sending packet: ${e.message}")
        }
    }

    fun setANCMode(mode: Int) {
        Log.d("AirPodsService", "setANCMode: $mode")
        when (mode) {
            1 -> {
                sendPacket(Enums.NOISE_CANCELLATION_OFF.value)
            }

            2 -> {
                sendPacket(Enums.NOISE_CANCELLATION_ON.value)
            }

            3 -> {
                sendPacket(Enums.NOISE_CANCELLATION_TRANSPARENCY.value)
            }

            4 -> {
                sendPacket(Enums.NOISE_CANCELLATION_ADAPTIVE.value)
            }
        }
    }

    fun setCAEnabled(enabled: Boolean) {
        sendPacket(if (enabled) Enums.SET_CONVERSATION_AWARENESS_ON.value else Enums.SET_CONVERSATION_AWARENESS_OFF.value)
    }

    fun setOffListeningMode(enabled: Boolean) {
        sendPacket(
            byteArrayOf(
                0x04,
                0x00,
                0x04,
                0x00,
                0x09,
                0x00,
                0x34,
                if (enabled) 0x01 else 0x02,
                0x00,
                0x00,
                0x00
            )
        )

        if (config.offListeningMode != enabled) {
            config.offListeningMode = enabled
            sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        }
        updateNoiseControlWidget()
    }

    fun setAdaptiveStrength(strength: Int) {
        val bytes =
            byteArrayOf(
                0x04,
                0x00,
                0x04,
                0x00,
                0x09,
                0x00,
                0x2E,
                strength.toByte(),
                0x00,
                0x00,
                0x00
            )
        sendPacket(bytes)

        if (config.adaptiveStrength != strength) {
            config.adaptiveStrength = strength
            sharedPreferences.edit { putInt("adaptive_strength", strength) }
        }
    }

    fun setPressSpeed(speed: Int) {
        // 0x00 = default, 0x01 = slower, 0x02 = slowest
        val bytes =
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x17, speed.toByte(), 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setPressAndHoldDuration(speed: Int) {
        // 0 - default, 1 - slower, 2 - slowest
        val bytes =
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x18, speed.toByte(), 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setVolumeSwipeSpeed(speed: Int) {
        // 0 - default, 1 - longer, 2 - longest
        val bytes =
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x23, speed.toByte(), 0x00, 0x00, 0x00)
        Log.d(
            "AirPodsService",
            "Setting volume swipe speed to $speed by packet ${
                bytes.joinToString(" ") {
                    "%02X".format(
                        it
                    )
                }
            }"
        )
        sendPacket(bytes)
    }

    fun setNoiseCancellationWithOnePod(enabled: Boolean) {
        val bytes = byteArrayOf(
            0x04,
            0x00,
            0x04,
            0x00,
            0x09,
            0x00,
            0x1B,
            if (enabled) 0x01 else 0x02,
            0x00,
            0x00,
            0x00
        )
        sendPacket(bytes)
    }

    fun setVolumeControl(enabled: Boolean) {
        val bytes = byteArrayOf(
            0x04,
            0x00,
            0x04,
            0x00,
            0x09,
            0x00,
            0x25,
            if (enabled) 0x01 else 0x02,
            0x00,
            0x00,
            0x00
        )
        sendPacket(bytes)

        if (config.volumeControl != enabled) {
            config.volumeControl = enabled
            sharedPreferences.edit { putBoolean("volume_control", enabled) }
        }
    }

    fun setToneVolume(volume: Int) {
        val bytes =
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x1F, volume.toByte(), 0x50, 0x00, 0x00)
        sendPacket(bytes)

        if (config.toneVolume != volume) {
            config.toneVolume = volume
            sharedPreferences.edit { putInt("tone_volume", volume) }
        }
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            batteryNotification.setBattery(CrossDevice.batteryBytes)
        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            ancNotification.setStatus(CrossDevice.ancBytes)
        }
        return ancNotification.status
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        if (MediaController.pausedForCrossDevice) {
                            MediaController.sendPlay()
                        }
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun setName(name: String) {
        val nameBytes = name.toByteArray()
        val bytes = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x1a, 0x00, 0x01,
            nameBytes.size.toByte(), 0x00
        ) + nameBytes
        sendPacket(bytes)
        val hex = bytes.joinToString(" ") { "%02X".format(it) }

        if (config.deviceName != name) {
            config.deviceName = name
            sharedPreferences.edit { putString("name", name) }
        }

        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d("AirPodsService", "setName: $name, sent packet: $hex")
    }

    fun setPVEnabled(enabled: Boolean) {
        var hex = "04 00 04 00 09 00 26 ${if (enabled) "01" else "02"} 00 00 00"
        var bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)
        hex =
            "04 00 04 00 17 00 00 00 10 00 12 00 08 E${if (enabled) "6" else "5"} 05 10 02 42 0B 08 50 10 02 1A 05 02 ${if (enabled) "32" else "00"} 00 00 00"
        bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)

        if (config.personalizedVolume != enabled) {
            config.personalizedVolume = enabled
            sharedPreferences.edit { putBoolean("personalized_volume", enabled) }
        }
    }

    fun setLoudSoundReduction(enabled: Boolean) {
        val hex = "52 1B 00 0${if (enabled) "1" else "0"}"
        val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)

        if (config.loudSoundReduction != enabled) {
            config.loudSoundReduction = enabled
            sharedPreferences.edit { putBoolean("loud_sound_reduction", enabled) }
        }
    }

    fun findChangedIndex(oldArray: BooleanArray, newArray: BooleanArray): Int {
        for (i in oldArray.indices) {
            if (oldArray[i] != newArray[i]) {
                return i
            }
        }
        throw IllegalArgumentException("No element has changed")
    }

    fun updateLongPress(
        oldLongPressArray: BooleanArray,
        newLongPressArray: BooleanArray,
        offListeningMode: Boolean
    ) {
        if (oldLongPressArray.contentEquals(newLongPressArray)) {
            return
        }
        val oldOffEnabled = oldLongPressArray[0]
        val oldAncEnabled = oldLongPressArray[1]
        val oldTransparencyEnabled = oldLongPressArray[2]
        val oldAdaptiveEnabled = oldLongPressArray[3]

        val newOffEnabled = newLongPressArray[0]
        val newAncEnabled = newLongPressArray[1]
        val newTransparencyEnabled = newLongPressArray[2]
        val newAdaptiveEnabled = newLongPressArray[3]

        val changedIndex = findChangedIndex(oldLongPressArray, newLongPressArray)
        Log.d("AirPodsService", "changedIndex: $changedIndex")
        var packet: ByteArray? = null
        if (offListeningMode) {
            packet = when (changedIndex) {
                0 -> {
                    if (newOffEnabled) {
                        when {
                            oldAncEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ANC.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_ADAPTIVE_AND_ANC.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    } else {
                        when {
                            oldAncEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_EVERYTHING.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ANC.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_ADAPTIVE_AND_ANC.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    }
                }

                1 -> {
                    if (newAncEnabled) {
                        when {
                            oldOffEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ANC_FROM_OFF_AND_TRANSPARENCY.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_ANC_FROM_OFF_AND_ADAPTIVE.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_ANC_FROM_EVERYTHING.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ANC_FROM_OFF_AND_TRANSPARENCY.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_ANC_FROM_OFF_AND_ADAPTIVE.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    }
                }

                2 -> {
                    if (newTransparencyEnabled) {
                        when {
                            oldOffEnabled && oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_OFF_AND_ADAPTIVE.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_ADAPTIVE_AND_ANC.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_OFF_AND_ADAPTIVE.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_ADAPTIVE_AND_ANC.value
                            else -> null
                        }
                    }
                }

                3 -> {
                    if (newAdaptiveEnabled) {
                        when {
                            oldOffEnabled && oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_OFF_AND_TRANSPARENCY.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_TRANSPARENCY_AND_ANC.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_OFF_AND_TRANSPARENCY.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_TRANSPARENCY_AND_ANC.value
                            else -> null
                        }
                    }
                }

                else -> null
            }
        } else {
            when (changedIndex) {
                1 -> {
                    packet = if (newLongPressArray[1]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_ANC_OFF_DISABLED.value
                    }
                }

                2 -> {
                    packet = if (newLongPressArray[2]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_TRANSPARENCY_OFF_DISABLED.value
                    }
                }

                3 -> {
                    packet = if (newLongPressArray[3]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_ADAPTIVE_OFF_DISABLED.value
                    }
                }
            }

        }
        packet?.let {
            Log.d("AirPodsService", "Sending packet: ${it.joinToString(" ") { "%02X".format(it) }}")
            sendPacket(it)
        }
    }

    override fun onDestroy() {
        clearPacketLogs()
        Log.d("AirPodsService", "Service stopped is being destroyed for some reason!")

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(ancModeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(disconnectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        isConnectedLocally = false
        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    var isHeadTrackingActive = false

    fun startHeadTracking() {
        isHeadTrackingActive = true
        socket.outputStream.write(Enums.START_HEAD_TRACKING.value)
        HeadTracking.reset()
    }

    fun stopHeadTracking() {
        socket.outputStream.write(Enums.STOP_HEAD_TRACKING.value)
        isHeadTrackingActive = false
    }

    fun processData(data: ByteArray) {
        if (isHeadTrackingActive && isHeadTrackingData(data)) {
            HeadTracking.processPacket(data)
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}
