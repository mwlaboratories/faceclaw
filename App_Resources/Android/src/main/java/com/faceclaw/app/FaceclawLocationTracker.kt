package com.faceclaw.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import androidx.core.content.ContextCompat

/**
 * Continuous location updates for turn-by-turn navigation. Unlike
 * FaceclawLocationProvider (one-shot, coarse, for Weather), this streams GPS
 * fixes with bearing and speed until stopped.
 *
 * Callbacks are delivered on the Looper of the thread that constructed the
 * tracker, so a worker isolate receives them on its own thread (the same
 * convention as FaceclawSseRequest).
 */
class FaceclawLocationTracker(context: Context) : LocationListener {
    companion object {
        /**
         * Only seed from the platform's cached fix when it is this fresh. An older
         * one is likely wherever the phone was last used (for example the previous
         * navigation's destination), and guidance built on it is wrong from the
         * first frame: the route starts there and arrival fires immediately.
         */
        private const val MAX_SEED_AGE_MS = 60L * 1000L
        /** Network fixes are dropped while a GPS fix is younger than this. */
        private const val PREFER_GPS_MS = 10L * 1000L

        /** Age via the monotonic clock, so a wall-clock change can't make an old fix look fresh. */
        private fun ageMs(location: Location): Long {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            return ageNanos / 1_000_000L
        }
    }

    private val context: Context = context.applicationContext
    private val locationManager: LocationManager? =
        this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    private val callbackHandler: Handler

    private var listener: FaceclawLocationTrackerListener? = null
    private var running = false
    private val activeProviders = mutableSetOf<String>()
    private var lastGpsFixElapsedMs = Long.MIN_VALUE / 2

    init {
        val looper = Looper.myLooper()
        this.callbackHandler = Handler(looper ?: Looper.getMainLooper())
    }

    fun setListener(listener: FaceclawLocationTrackerListener?) {
        this.listener = listener
    }

    /** Begin streaming fixes at roughly the requested interval. */
    fun start(intervalMs: Long) {
        callbackHandler.post { startOnCallbackThread(intervalMs) }
    }

    fun stop() {
        callbackHandler.post {
            if (!running) {
                return@post
            }
            running = false
            try {
                locationManager!!.removeUpdates(this)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun startOnCallbackThread(intervalMs: Long) {
        if (running) {
            return
        }
        val manager = locationManager
        if (manager == null) {
            deliverError("Android location service is unavailable.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            deliverError("Precise location permission is required for navigation.")
            return
        }

        val providers = chooseProviders()
        if (providers.isEmpty()) {
            deliverError("Turn on Location on your phone, then retry.")
            return
        }

        try {
            running = true
            activeProviders.clear()
            for (provider in providers) {
                manager.requestLocationUpdates(provider, Math.max(500L, intervalMs), 0f, this,
                    callbackHandler.looper)
                activeProviders.add(provider)
            }
            // Seed with the freshest cached fix so the UI has a position
            // before the first live fix (GPS cold starts can take a while),
            // but only if it's recent enough to still be where the user is.
            val cached = freshestCachedLocation(providers)
            if (cached != null) {
                deliverLocation(cached)
            }
        } catch (error: SecurityException) {
            running = false
            deliverError("Precise location permission is required for navigation.")
        } catch (error: Throwable) {
            running = false
            deliverError("Unable to start location updates.")
        }
    }

    private fun freshestCachedLocation(providers: List<String>): Location? {
        var best: Location? = null
        for (provider in (providers + listOf(LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER)).distinct()) {
            val candidate: Location?
            try {
                candidate = locationManager!!.getLastKnownLocation(provider)
            } catch (ignored: Throwable) {
                continue
            }
            if (candidate == null || ageMs(candidate) > MAX_SEED_AGE_MS) {
                continue
            }
            if (best == null || candidate.elapsedRealtimeNanos > best.elapsedRealtimeNanos) {
                best = candidate
            }
        }
        return best
    }

    /**
     * The fused provider when the platform has it (Android 12+): it blends GPS
     * with network location, so a fix arrives indoors too. Otherwise GPS and
     * network together, rather than GPS alone: with GPS on but no sky in view
     * (indoors), GPS-only tracking never produced a first fix even though
     * network location was available.
     */
    private fun chooseProviders(): List<String> {
        val manager = locationManager ?: return emptyList()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                return listOf(LocationManager.FUSED_PROVIDER)
            }
            return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { manager.isProviderEnabled(it) }
        } catch (ignored: Throwable) {
        }
        return emptyList()
    }

    override fun onLocationChanged(location: Location) {
        // With GPS and network both on, a coarse network fix must not
        // override a recent GPS one.
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixElapsedMs = SystemClock.elapsedRealtime()
        } else if (location.provider == LocationManager.NETWORK_PROVIDER &&
            SystemClock.elapsedRealtime() - lastGpsFixElapsedMs < PREFER_GPS_MS) {
            return
        }
        deliverLocation(location)
    }

    override fun onProviderDisabled(provider: String) {
        activeProviders.remove(provider)
        if (activeProviders.isEmpty()) {
            deliverError("Location was turned off on the phone.")
        }
    }

    override fun onProviderEnabled(provider: String) {
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    private fun deliverLocation(location: Location?) {
        val current = listener
        if (current == null || location == null) {
            return
        }
        current.onLocation(
            location.latitude,
            location.longitude,
            if (location.hasAccuracy()) location.accuracy else -1f,
            if (location.hasBearing()) location.bearing else -1f,
            if (location.hasSpeed()) location.speed else -1f,
            location.time)
    }

    private fun deliverError(message: String) {
        val current = listener
        current?.onError(message)
    }
}
