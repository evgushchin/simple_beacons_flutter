package com.umair.beacons_plugin

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import timber.log.Timber


/** BeaconsPlugin */
class BeaconsPlugin : FlutterPlugin, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private var context: Context? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Timber.i("onAttachedToEngine")
        messenger = flutterPluginBinding.binaryMessenger
        setUpPluginMethods(
            flutterPluginBinding.applicationContext,
            flutterPluginBinding.binaryMessenger
        )
        context = flutterPluginBinding.applicationContext
        beaconHelper = BeaconHelper(flutterPluginBinding.applicationContext)
        context?.let {
            BeaconPreferences.init(it)
            stopBackgroundService(it)
        }
    }

    companion object {
        private var REQUEST_LOCATION_PERMISSIONS = 1890
        private var PERMISSION_REQUEST_BACKGROUND_LOCATION = 1891
        private var channel: MethodChannel? = null
        private var event_channel: EventChannel? = null
        private var currentActivity: Activity? = null
        private var beaconHelper: BeaconHelper? = null

        private var defaultPermissionDialogTitle = "This app needs background location access"
        private var defaultPermissionDialogMessage =
            "Please grant location access so this app can detect beacons in the background."

        @JvmStatic
        internal var messenger: BinaryMessenger? = null

        @JvmStatic
        fun registerWith(registrar: PluginRegistry.Registrar) {
            BeaconPreferences.init(registrar.context())
            if (beaconHelper == null) {
                this.beaconHelper = BeaconHelper(registrar.context())
            }
            val instance = BeaconsPlugin()
            registrar.addRequestPermissionsResultListener(instance)
            //requestPermission()
            setUpPluginMethods(registrar.activity(), registrar.messenger())
        }

        @JvmStatic
        fun registerWith(messenger: BinaryMessenger, context: Context) {
            BeaconPreferences.init(context)
            if (beaconHelper == null) {
                this.beaconHelper = BeaconHelper(context)
            }
            val instance = BeaconsPlugin()
            //requestPermission()
            setUpPluginMethods(context, messenger)
        }

        @JvmStatic
        fun registerWith(messenger: BinaryMessenger, beaconHelper: BeaconHelper, context: Context) {
            BeaconPreferences.init(context)
            this.beaconHelper = beaconHelper
            val instance = BeaconsPlugin()
            //requestPermission()
            setUpPluginMethods(context, messenger)
        }

        @JvmStatic
        private fun setUpPluginMethods(context: Context, messenger: BinaryMessenger) {
            Timber.plant(Timber.DebugTree())

            channel = MethodChannel(messenger, "beacons_plugin")
            notifyIfPermissionsGranted(context)
            channel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "startMonitoring" -> {
                        stopService = false
                        callBack?.startScanning()
                        result.success("Started scanning Beacons.")
                    }

                    "stopMonitoring" -> {
                        if (runInBackground) {
                            stopService = true
                            stopBackgroundService(context)
                        }

                        callBack?.stopMonitoringBeacons()
                        result.success("Stopped scanning Beacons.")
                    }

                    "addRegion" -> {
                        callBack?.addRegion(call, result)
                    }

                    "clearRegions" -> {
                        callBack?.clearRegions(call, result)
                    }

                    "runInBackground" -> {
                        call.argument<Boolean>("background")?.let {
                            runInBackground = it
                        }
                        result.success("App will run in background? $runInBackground")
                    }

                    "clearDisclosureDialogShowFlag" -> {
                        call.argument<Boolean>("clearFlag")?.let {
                            if (it) {
                                clearPermissionDialogShownFlag()
                                result.success("clearDisclosureDialogShowFlag: Flag cleared!")
                            } else {
                                setPermissionDialogShown()
                                result.success("clearDisclosureDialogShowFlag: Flag Set!")
                            }
                        }
                    }

                    "setDisclosureDialogMessage" -> {
                        call.argument<String>("title")?.let {
                            defaultPermissionDialogTitle = it
                        }
                        call.argument<String>("message")?.let {
                            defaultPermissionDialogMessage = it
                        }
                        requestPermission()
                        result.success("Disclosure message Set: $defaultPermissionDialogMessage")
                    }

                    "addBeaconLayoutForAndroid" -> {
                        call.argument<String>("layout")?.let {
                            callBack?.addBeaconLayout(it)
                            result.success("Beacon layout added: $it")
                        }
                    }

                    "setForegroundScanPeriodForAndroid" -> {
                        var foregroundScanPeriod = 1100L
                        var foregroundBetweenScanPeriod = 0L
                        call.argument<Int>("foregroundScanPeriod")?.let {
                            if (it > foregroundScanPeriod) {
                                foregroundScanPeriod = it.toLong()
                            }
                        }
                        call.argument<Int>("foregroundBetweenScanPeriod")?.let {
                            if (it > foregroundBetweenScanPeriod) {
                                foregroundBetweenScanPeriod = it.toLong()
                            }
                        }
                        callBack?.setForegroundScanPeriod(
                                foregroundScanPeriod = foregroundScanPeriod,
                                foregroundBetweenScanPeriod = foregroundBetweenScanPeriod
                        )
                        result.success("setForegroundScanPeriod updated.")
                    }

                    "setBackgroundScanPeriodForAndroid" -> {
                        var backgroundScanPeriod = 1100L
                        var backgroundBetweenScanPeriod = 0L
                        call.argument<Int>("backgroundScanPeriod")?.let {
                            if (it > backgroundScanPeriod) {
                                backgroundScanPeriod = it.toLong()
                            }
                        }
                        call.argument<Int>("backgroundBetweenScanPeriod")?.let {
                            if (it > backgroundBetweenScanPeriod) {
                                backgroundBetweenScanPeriod = it.toLong()
                            }
                        }
                        callBack?.setBackgroundScanPeriod(
                                backgroundScanPeriod = backgroundScanPeriod,
                                backgroundBetweenScanPeriod = backgroundBetweenScanPeriod
                        )
                        result.success("setBackgroundScanPeriod updated.")
                    }
                    else -> result.notImplemented()
                }
            }

            event_channel = EventChannel(messenger, "beacons_plugin_stream")
            event_channel?.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    callBack?.setEventSink(events)
                }

                override fun onCancel(arguments: Any?) {

                }
            })
        }

        @JvmStatic
        private fun notifyIfPermissionsGranted(context: Context) {
            if (permissionsGranted(context)) {
                doIfPermissionsGranted()
            }
        }

        @JvmStatic
        fun permissionsGranted(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        }

        @JvmStatic
        private fun doIfPermissionsGranted() {
            Timber.i("doIfPermissionsGranted")

            if (beaconHelper == null) {
                return
            }

            this.callBack = beaconHelper

            sendBLEScannerReadyCallback()
        }


        @JvmStatic
        private fun requestPermission() {
            if (areBackgroundScanPermissionsGranted()) {
                requestLocationPermissions()
            } else {
                requestBackgroundPermission()
            }
        }

        private fun requestLocationPermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (!isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                            currentActivity?.let {
                                ActivityCompat.requestPermissions(
                                        it,
                                        arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                        ),
                                        REQUEST_LOCATION_PERMISSIONS
                                )
                            }
                        }
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        currentActivity?.let {
                            ActivityCompat.requestPermissions(
                                    it,
                                    arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                    ),
                                    REQUEST_LOCATION_PERMISSIONS
                            )
                        }
                    } else {
                        currentActivity?.let {
                            ActivityCompat.requestPermissions(
                                    it,
                                    arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                    ),
                                    REQUEST_LOCATION_PERMISSIONS
                            )
                        }
                    }
                }
            }
            doIfPermissionsGranted();
        }

        @JvmStatic
        private fun requestBackgroundPermission() {
            if (!isPermissionDialogShown()) {
                currentActivity?.let {
                    //if (it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    val builder: AlertDialog.Builder =
                            AlertDialog.Builder(it)
                    builder.setTitle(defaultPermissionDialogTitle)
                    builder.setMessage(defaultPermissionDialogMessage)
                    builder.setPositiveButton("Ok", null)
                    builder.setOnDismissListener {
                        setPermissionDialogShown()
                        requestLocationPermissions()
                        channel?.invokeMethod("isPermissionDialogShown", "true")
                    }
                    builder.show()
                    //}
                }
            } else {
                requestLocationPermissions()
            }
        }

        /**
         * Checks is the specified permission is granted
         *
         * @param permission A manifest permission
         */
        private fun isGranted(permission: String): Boolean {
            currentActivity?.let {
                return ContextCompat.checkSelfPermission(
                        it,
                        permission
                ) == PackageManager.PERMISSION_GRANTED
            }
            return false
        }

        @JvmStatic
        private fun arePermissionsGranted(): Boolean {
            return isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
                    && isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        @JvmStatic
        private fun areBackgroundScanPermissionsGranted(): Boolean {
            currentActivity?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    return true
                }
            }
            return true
        }

        @JvmStatic
        var runInBackground = false

        @JvmStatic
        var stopService = false

        interface PluginImpl {
            fun startScanning()
            fun stopMonitoringBeacons()
            fun addRegion(call: MethodCall, result: MethodChannel.Result)
            fun clearRegions(call: MethodCall, result: MethodChannel.Result)
            fun setEventSink(events: EventChannel.EventSink?)
            fun addBeaconLayout(layout: String)
            fun setForegroundScanPeriod(
                foregroundScanPeriod: Long,
                foregroundBetweenScanPeriod: Long
            )

            fun setBackgroundScanPeriod(
                backgroundScanPeriod: Long,
                backgroundBetweenScanPeriod: Long
            )
        }

        private var callBack: PluginImpl? = null

        fun sendBLEScannerReadyCallback() {
            channel?.invokeMethod("scannerReady", "")
        }

        fun startBackgroundService(context: Context) {
            if (runInBackground && !stopService) {
                val serviceIntent1 = Intent(context, BeaconsDiscoveryService::class.java)
                context.startService(serviceIntent1)
            }
        }

        fun stopBackgroundService(context: Context) {
            if (runInBackground && !stopService) {
                val serviceIntent = Intent(context, BeaconsDiscoveryService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        currentActivity = null
        channel?.setMethodCallHandler(null)
        event_channel?.setStreamHandler(null)

        if (!runInBackground)
            beaconHelper?.stopMonitoringBeacons()

        context?.let {
            stopBackgroundService(it)
        }

        context = null
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        currentActivity = activityPluginBinding.activity
        BeaconPreferences.init(currentActivity)
        activityPluginBinding.addRequestPermissionsResultListener(this)
        //requestPermission()

        if (arePermissionsGranted()) {
            sendBLEScannerReadyCallback()
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Timber.i("onDetachedFromActivityForConfigChanges")
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        Timber.i("onReattachedToActivityForConfigChanges")
        currentActivity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        Timber.i("onDetachedFromActivity")
        currentActivity = null

        context?.let {
            startBackgroundService(it)
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ): Boolean {
        if (requestCode == REQUEST_LOCATION_PERMISSIONS && grantResults?.isNotEmpty()!! && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doIfPermissionsGranted()
            return true
        }
        if (requestCode == PERMISSION_REQUEST_BACKGROUND_LOCATION && grantResults?.isNotEmpty()!! && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            //setPermissionDialogShown()
            //requestPermission()
            return true
        }
        return false
    }
}