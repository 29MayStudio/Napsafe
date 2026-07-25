package com.nap.safe

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.nap.safe.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var destinationMarker: Marker? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    private val preferences by lazy {
        getSharedPreferences("napsafe_prefs", Context.MODE_PRIVATE)
    }

    private val distanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val distance = intent?.getFloatExtra("distance", -1f) ?: -1f
            if (distance >= 0) {
                updateDistanceUi(distance)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocGranted || coarseLocGranted) {
            initMyLocation()
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "Location permission is required for NapSafe to work.", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundLocLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Background location permission helps NapSafe work when your phone is locked.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OSMDroid Configuration
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = "com.nap.safe"

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupListeners()
        restoreJourneyState()

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(distanceReceiver, IntentFilter("com.nap.safe.UPDATE_DISTANCE"), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(distanceReceiver, IntentFilter("com.nap.safe.UPDATE_DISTANCE"))
        }
        val lastDistance = preferences.getFloat("last_distance", -1f)
        if (lastDistance >= 0) {
            updateDistanceUi(lastDistance)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        unregisterReceiver(distanceReceiver)
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        val mapController = binding.mapView.controller
        mapController.setZoom(15.0)

        // Default to a central point (London or similar) until real location is fetched
        val startPoint = GeoPoint(51.5074, -0.1278)
        mapController.setCenter(startPoint)

        // Add Tap listener to place destination marker
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    setDestination(p)
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        }

        val eventsOverlay = MapEventsOverlay(mapEventsReceiver)
        binding.mapView.overlays.add(eventsOverlay)
    }

    private fun initMyLocation() {
        val provider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            initMyLocation()
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundGranted) {
                AlertDialog.Builder(this)
                    .setTitle("Background Location Access")
                    .setMessage("NapSafe needs background location access to monitor your distance while the app is closed or when you are asleep.")
                    .setPositiveButton("Allow") { _, _ ->
                        backgroundLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Deny", null)
                    .show()
            }
        }
    }

    private fun setDestination(point: GeoPoint) {
        if (destinationMarker == null) {
            destinationMarker = Marker(binding.mapView).apply {
                title = "Destination"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(destinationMarker)
        }

        destinationMarker?.position = point
        binding.mapView.invalidate()

        preferences.edit().apply {
            putFloat("dest_lat", point.latitude.toFloat())
            putFloat("dest_lng", point.longitude.toFloat())
            apply()
        }

        binding.tvDestinationStatus.text = "Destination: Selected (${"%.4f".format(point.latitude)}, ${"%.4f".format(point.longitude)})"

        // Calculate initial distance to destination if possible
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val dest = Location("dest").apply {
                        latitude = point.latitude
                        longitude = point.longitude
                    }
                    val distance = loc.distanceTo(dest)
                    updateDistanceUi(distance)
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun updateDistanceUi(distanceInMeters: Float) {
        val formatted = if (distanceInMeters >= 1000) {
            "%.2f km".format(distanceInMeters / 1000f)
        } else {
            "%.0f meters".format(distanceInMeters)
        }
        binding.tvDistanceRemaining.text = "Distance: $formatted"
    }

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            val isJourneyActive = preferences.getBoolean("journey_active", false)
            if (isJourneyActive) {
                stopJourney()
            } else {
                startJourney()
            }
        }

        binding.rgRadius.setOnCheckedChangeListener { _, checkedId ->
            val radius = when (checkedId) {
                R.id.rbRadius500 -> 500f
                R.id.rbRadius1000 -> 1000f
                R.id.rbRadius2000 -> 2000f
                else -> 500f
            }
            preferences.edit().putFloat("alert_radius", radius).apply()
        }
    }

    private fun startJourney() {
        val destLat = preferences.getFloat("dest_lat", Float.NaN)
        val destLng = preferences.getFloat("dest_lng", Float.NaN)

        if (destLat.isNaN() || destLng.isNaN()) {
            Toast.makeText(this, "Please select a destination on the map first.", Toast.LENGTH_SHORT).show()
            return
        }

        // Get and save radius
        val radius = when (binding.rgRadius.checkedRadioButtonId) {
            R.id.rbRadius500 -> 500f
            R.id.rbRadius1000 -> 1000f
            R.id.rbRadius2000 -> 2000f
            else -> 500f
        }

        preferences.edit().apply {
            putBoolean("journey_active", true)
            putFloat("alert_radius", radius)
            apply()
        }

        // Schedule WorkManager check immediately
        val workRequest = OneTimeWorkRequestBuilder<LocationCheckWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "napsafe_periodic_check",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        updateUiState(true)
        Toast.makeText(this, "Journey started! Sleep safe.", Toast.LENGTH_SHORT).show()
    }

    private fun stopJourney() {
        preferences.edit().putBoolean("journey_active", false).apply()
        WorkManager.getInstance(this).cancelUniqueWork("napsafe_periodic_check")
        updateUiState(false)
        Toast.makeText(this, "Journey stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun restoreJourneyState() {
        val isJourneyActive = preferences.getBoolean("journey_active", false)
        val destLat = preferences.getFloat("dest_lat", Float.NaN)
        val destLng = preferences.getFloat("dest_lng", Float.NaN)
        val alertRadius = preferences.getFloat("alert_radius", 500f)

        // Restore destination marker
        if (!destLat.isNaN() && !destLng.isNaN()) {
            val point = GeoPoint(destLat.toDouble(), destLng.toDouble())
            setDestination(point)
        }

        // Restore selected radius
        when (alertRadius) {
            500f -> binding.rbRadius500.isChecked = true
            1000f -> binding.rbRadius1000.isChecked = true
            2000f -> binding.rbRadius2000.isChecked = true
        }

        updateUiState(isJourneyActive)
    }

    private fun updateUiState(isJourneyActive: Boolean) {
        if (isJourneyActive) {
            binding.btnStartStop.text = getString(R.string.stop_journey)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        } else {
            binding.btnStartStop.text = getString(R.string.start_journey)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
            binding.tvDistanceRemaining.text = getString(R.string.distance_to_dest)
        }
    }
}
