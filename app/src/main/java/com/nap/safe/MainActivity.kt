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
import android.os.PowerManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.location.Address
import android.view.View
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.nap.safe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var lastUserLatLng: LatLng? = null

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            val userLatLng = LatLng(location.latitude, location.longitude)
            lastUserLatLng = userLatLng

            // Draw / update path to the destination
            drawPathToDestination()

            // Calculate and update distance dynamically
            val destLat = preferences.getFloat("dest_lat", Float.NaN)
            val destLng = preferences.getFloat("dest_lng", Float.NaN)
            if (!destLat.isNaN() && !destLng.isNaN()) {
                val dest = Location("dest").apply {
                    latitude = destLat.toDouble()
                    longitude = destLng.toDouble()
                }
                updateDistanceUi(location.distanceTo(dest))
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Unrestricted Battery Usage Required")
                .setMessage("To ensure the alarm works perfectly even when your device goes into sleep (doze mode), please disable battery optimization for NapSafe.")
                .setPositiveButton("Configure") { _, _ ->
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(this, "Could not open settings. Please disable battery optimization manually.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Not Now", null)
                .show()
        }
    }

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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupListeners()
        restoreJourneyState()

        checkAndRequestPermissions()
        checkBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
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
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(distanceReceiver)
        stopLocationUpdates()
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true

        initMyLocation()

        // Restore destination marker
        val destLat = preferences.getFloat("dest_lat", Float.NaN)
        val destLng = preferences.getFloat("dest_lng", Float.NaN)
        if (!destLat.isNaN() && !destLng.isNaN()) {
            val point = LatLng(destLat.toDouble(), destLng.toDouble())
            setDestination(point)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 15f))
        } else {
            // Default to a central point (London or similar) until real location is fetched
            val startPoint = LatLng(51.5074, -0.1278)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
            binding.tvDestinationStatus.visibility = View.GONE
        }

        // Add map click listener
        map.setOnMapClickListener { point ->
            setDestination(point)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initMyLocation() {
        val map = googleMap ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = false // Disabled default since we use our own custom fabMyLocation button

            // Fetch initial user location to draw path
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val userLatLng = LatLng(loc.latitude, loc.longitude)
                    lastUserLatLng = userLatLng
                    drawPathToDestination()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(2000L)
                .build()

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun drawPathToDestination() {
        val map = googleMap ?: return
        val userLatLng = lastUserLatLng ?: return

        val destLat = preferences.getFloat("dest_lat", Float.NaN)
        val destLng = preferences.getFloat("dest_lng", Float.NaN)
        if (destLat.isNaN() || destLng.isNaN()) {
            routePolyline?.remove()
            routePolyline = null
            return
        }

        val destLatLng = LatLng(destLat.toDouble(), destLng.toDouble())

        // Remove existing polyline if any
        routePolyline?.remove()

        // Draw the path to the destination
        val polylineOptions = PolylineOptions()
            .add(userLatLng)
            .add(destLatLng)
            .color(ContextCompat.getColor(this, R.color.purple_500))
            .width(10f)

        routePolyline = map.addPolyline(polylineOptions)
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

    private fun setDestination(point: LatLng) {
        val map = googleMap ?: return
        destinationMarker?.remove()
        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(point)
                .title("Destination")
        )

        preferences.edit().apply {
            putFloat("dest_lat", point.latitude.toFloat())
            putFloat("dest_lng", point.longitude.toFloat())
            apply()
        }

        // Only show the selected location text now that it is selected
        binding.tvDestinationStatus.visibility = View.VISIBLE
        binding.tvDestinationStatus.text = "Selected Destination: (${"%.4f".format(point.latitude)}, ${"%.4f".format(point.longitude)})"

        // Redraw route polyline immediately
        drawPathToDestination()

        // Calculate initial distance to destination if possible
        try {
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

    @SuppressLint("MissingPermission")
    private fun setupListeners() {
        // Set up custom suggestions adapter for AutoCompleteTextView search bar
        val suggestionsAdapter = GeocoderSuggestionsAdapter(this)
        binding.etSearch.setAdapter(suggestionsAdapter)
        binding.etSearch.setOnItemClickListener { parent, _, position, _ ->
            val selectedAddress = parent.getItemAtPosition(position) as? Address
            if (selectedAddress != null) {
                val latLng = LatLng(selectedAddress.latitude, selectedAddress.longitude)
                googleMap?.let { map ->
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    setDestination(latLng)
                }
            }
        }

        binding.btnStartStop.setOnClickListener {
            val isJourneyActive = preferences.getBoolean("journey_active", false)
            if (isJourneyActive) {
                stopJourney()
            } else {
                startJourney()
            }
        }

        binding.btnPreset1km.setOnClickListener {
            binding.etRadius.setText("1000")
            preferences.edit().putFloat("alert_radius", 1000f).apply()
        }

        binding.etRadius.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val radiusStr = s?.toString()?.trim() ?: ""
                val radius = radiusStr.toFloatOrNull()
                if (radius != null && radius > 0f) {
                    preferences.edit().putFloat("alert_radius", radius).apply()
                }
            }
        })

        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        // Setup the Custom Relocate Button (fabMyLocation)
        binding.fabMyLocation.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        val currentLatLng = LatLng(loc.latitude, loc.longitude)
                        lastUserLatLng = currentLatLng
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                        drawPathToDestination()
                    } else {
                        Toast.makeText(this, "Searching for location...", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Location permission is not granted.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

        // Run geocoding in a background thread
        Thread {
            try {
                @Suppress("DEPRECATION")
                val geocoder = android.location.Geocoder(this)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    runOnUiThread {
                        googleMap?.let { map ->
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                            setDestination(latLng)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Location not found.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Search error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startJourney() {
        val destLat = preferences.getFloat("dest_lat", Float.NaN)
        val destLng = preferences.getFloat("dest_lng", Float.NaN)

        if (destLat.isNaN() || destLng.isNaN()) {
            Toast.makeText(this, "Please select a destination on the map first.", Toast.LENGTH_SHORT).show()
            return
        }

        val radiusStr = binding.etRadius.text.toString().trim()
        val radius = radiusStr.toFloatOrNull()
        if (radius == null || radius <= 0f) {
            Toast.makeText(this, "Please enter a valid positive alarm radius.", Toast.LENGTH_SHORT).show()
            return
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
        // Also cancel the notification to clean up immediately
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(LocationCheckWorker.NOTIFICATION_ID)

        updateUiState(false)
        Toast.makeText(this, "Journey stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun restoreJourneyState() {
        val isJourneyActive = preferences.getBoolean("journey_active", false)
        val alertRadius = preferences.getFloat("alert_radius", 1000f)

        binding.etRadius.setText(alertRadius.toInt().toString())

        updateUiState(isJourneyActive)
    }

    private fun updateUiState(isJourneyActive: Boolean) {
        if (isJourneyActive) {
            binding.btnStartStop.text = getString(R.string.stop_journey)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
            binding.etRadius.isEnabled = false
            binding.btnPreset1km.isEnabled = false
        } else {
            binding.btnStartStop.text = getString(R.string.start_journey)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
            binding.tvDistanceRemaining.text = getString(R.string.distance_to_dest)
            binding.etRadius.isEnabled = true
            binding.btnPreset1km.isEnabled = true
        }
    }
}
