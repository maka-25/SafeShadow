package com.example.safeshadow.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R
import com.example.safeshadow.service.SafetyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TravelModeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TravelModeActivity"
        private const val DEFAULT_ZOOM = 14.0
        private const val INDIA_LAT = 20.5937
        private const val INDIA_LNG = 78.9629
    }

    private lateinit var mapView: MapView
    private var destinationMarker: Marker? = null
    private var selectedLat: Double = 0.0
    private var selectedLng: Double = 0.0
    private var selectedDestinationName: String = ""

    private lateinit var etSearch: EditText
    private lateinit var tvSelectedDestination: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var layoutCustomEta: LinearLayout
    private lateinit var etCustomEtaHours: EditText
    private lateinit var etCustomEtaMinutes: EditText
    private lateinit var btnStartTravel: Button
    private lateinit var layoutActive: LinearLayout
    private lateinit var layoutSetup: ScrollView
    private lateinit var tvActiveDest: TextView
    private lateinit var tvActiveEta: TextView
    private lateinit var btnStopTravel: Button

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            tileDownloadMaxQueueSize = 6
        }

        setContentView(R.layout.activity_travel_mode)

        bindViews()
        setupMap()
        setupSearch()
        setupEtaSelector()

        if (PrefsHelper.isTravelActive(this)) {
            showActiveState()
        } else {
            showSetupState()
        }
    }

    // View binding

    private fun bindViews() {
        mapView               = findViewById(R.id.mapView)
        etSearch              = findViewById(R.id.etDestinationSearch)
        tvSelectedDestination = findViewById(R.id.tvSelectedDestination)
        radioGroup            = findViewById(R.id.radioGroupEta)
        layoutCustomEta       = findViewById(R.id.layoutCustomEta)
        etCustomEtaHours      = findViewById(R.id.etCustomEtaHours)
        etCustomEtaMinutes    = findViewById(R.id.etCustomEtaMinutes)
        btnStartTravel        = findViewById(R.id.btnStartTravel)
        layoutActive          = findViewById(R.id.layoutTravelActive)
        layoutSetup           = findViewById(R.id.layoutTravelSetup)
        tvActiveDest          = findViewById(R.id.tvActiveDest)
        tvActiveEta           = findViewById(R.id.tvActiveEta)
        btnStopTravel         = findViewById(R.id.btnStopTravel)
    }

    // Map setup

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        val startPoint = GeoPoint(INDIA_LAT, INDIA_LNG)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(startPoint)

        mapView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    layoutSetup.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    layoutSetup.requestDisallowInterceptTouchEvent(false)
            }
            v.onTouchEvent(event)
        }

        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                placeMarker(p, "Selected Location")
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(MapEventsOverlay(tapReceiver))
    }

    // Destination search (Nominatim)

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim() ?: return
                if (query.length < 3) return
                searchRunnable = Runnable { searchLocation(query) }
                mainHandler.postDelayed(searchRunnable!!, 800)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) searchLocation(query)
                hideKeyboard()
                true
            } else false
        }
    }

    private fun searchLocation(query: String) {
        scope.launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val urlString =
                    "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"

                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "SafeShadow/$packageName")
                    setRequestProperty("Accept-Language", "en")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Nominatim returned HTTP $responseCode")
                    mainHandler.post {
                        Toast.makeText(
                            this@TravelModeActivity,
                            "Search service unavailable. Try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    connection.disconnect()
                    return@launch
                }

                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .readText()
                connection.disconnect()

                val jsonArray = JSONArray(response)

                if (jsonArray.length() == 0) {
                    mainHandler.post {
                        Toast.makeText(
                            this@TravelModeActivity,
                            "Location not found. Try a different search.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val result = jsonArray.getJSONObject(0)
                val lat = result.getDouble("lat")
                val lng = result.getDouble("lon")
                val displayName = result.getString("display_name")
                    .split(",").take(2).joinToString(", ")

                mainHandler.post {
                    val point = GeoPoint(lat, lng)
                    placeMarker(point, displayName)
                    mapView.controller.animateTo(point)
                    mapView.controller.setZoom(15.0)
                    hideKeyboard()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
                mainHandler.post {
                    Toast.makeText(
                        this@TravelModeActivity,
                        "Search failed. Check internet connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Marker placement

    private fun placeMarker(point: GeoPoint, name: String) {
        destinationMarker?.let { mapView.overlays.remove(it) }

        val marker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
            snippet = "Tap to confirm destination"
        }

        mapView.overlays.add(marker)
        mapView.invalidate()

        destinationMarker = marker
        selectedLat = point.latitude
        selectedLng = point.longitude
        selectedDestinationName = name

        tvSelectedDestination.text = "📍 $name"
        tvSelectedDestination.visibility = View.VISIBLE

        Log.d(TAG, "Marker placed: $name at $selectedLat, $selectedLng")
    }

    // ETA selection

    private fun setupEtaSelector() {
        // Hide custom fields initially
        layoutCustomEta.visibility = View.GONE

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioCustomEta -> {
                    layoutCustomEta.visibility = View.VISIBLE
                    etCustomEtaHours.requestFocus()
                }
                R.id.radio15min -> {
                    layoutCustomEta.visibility = View.GONE
                    populateEtaFields(0, 15)
                }
                R.id.radio30min -> {
                    layoutCustomEta.visibility = View.GONE
                    populateEtaFields(0, 30)
                }
                R.id.radio60min -> {
                    layoutCustomEta.visibility = View.GONE
                    populateEtaFields(1, 0)
                }
                R.id.radio90min -> {
                    layoutCustomEta.visibility = View.GONE
                    populateEtaFields(1, 30)
                }
                R.id.radio120min -> {
                    layoutCustomEta.visibility = View.GONE
                    populateEtaFields(2, 0)
                }
                else -> {
                    layoutCustomEta.visibility = View.GONE
                }
            }
        }

        btnStartTravel.setOnClickListener { onStartTravelClicked() }
    }

    // Populates hours and minutes fields from a known preset value
    private fun populateEtaFields(hours: Int, minutes: Int) {
        etCustomEtaHours.setText(if (hours > 0) hours.toString() else "")
        etCustomEtaMinutes.setText(if (minutes > 0) minutes.toString() else "")
    }

    // Reads hours + minutes fields and returns total minutes, or null if invalid
    private fun getCustomEtaMinutes(): Int? {
        val hours = etCustomEtaHours.text.toString().toIntOrNull() ?: 0
        val minutes = etCustomEtaMinutes.text.toString().toIntOrNull() ?: 0

        if (hours < 0 || hours > 8 || minutes < 0 || minutes > 59) {
            Toast.makeText(this, "Hours: 0-8, Minutes: 0-59", Toast.LENGTH_SHORT).show()
            return null
        }

        val totalMinutes = (hours * 60) + minutes

        if (totalMinutes == 0) {
            Toast.makeText(
                this, "Please enter a travel time greater than 0", Toast.LENGTH_SHORT
            ).show()
            return null
        }

        if (totalMinutes > 480) {
            Toast.makeText(
                this, "Maximum travel time is 8 hours (480 minutes)", Toast.LENGTH_SHORT
            ).show()
            return null
        }

        return totalMinutes
    }

    private fun getSelectedEtaMinutes(): Int? {
        return when (radioGroup.checkedRadioButtonId) {
            R.id.radio15min  -> 15
            R.id.radio30min  -> 30
            R.id.radio60min  -> 60
            R.id.radio90min  -> 90
            R.id.radio120min -> 120
            R.id.radioCustomEta -> getCustomEtaMinutes()
            else -> {
                Toast.makeText(this, "Please select a travel time", Toast.LENGTH_SHORT).show()
                null
            }
        }
    }

    // Start travel

    private fun onStartTravelClicked() {
        if (selectedLat == 0.0 && selectedLng == 0.0) {
            Toast.makeText(
                this,
                "Please search for or tap a destination on the map",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val etaMinutes = getSelectedEtaMinutes() ?: return

        PrefsHelper.startTravel(
            context = this,
            destination = selectedDestinationName,
            etaMinutes = etaMinutes,
            destLat = selectedLat,
            destLng = selectedLng
        )

        startService(Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_START_TRAVEL
        })

        Toast.makeText(this, "Travel Mode started! Stay safe 🛡️", Toast.LENGTH_SHORT).show()
        finish()
    }

    // Active state UI

    private fun showActiveState() {
        layoutSetup.visibility = View.GONE
        layoutActive.visibility = View.VISIBLE

        val destination = PrefsHelper.getTravelDestination(this)
        val etaEnd = PrefsHelper.getTravelEtaEndTime(this)
        val remaining = ((etaEnd - System.currentTimeMillis()) / 60000).coerceAtLeast(0)

        tvActiveDest.text = "📍 Destination: $destination"
        tvActiveEta.text = "⏱ ETA: ~$remaining minutes remaining"

        btnStopTravel.setOnClickListener {
            PrefsHelper.stopTravel(this)
            startService(Intent(this, SafetyService::class.java).apply {
                action = SafetyService.ACTION_STOP_TRAVEL
            })
            Toast.makeText(this, "Travel Mode stopped", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showSetupState() {
        layoutSetup.visibility = View.VISIBLE
        layoutActive.visibility = View.GONE
    }

    // Lifecycle

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (PrefsHelper.isTravelActive(this)) {
            showActiveState()
        } else {
            showSetupState()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}