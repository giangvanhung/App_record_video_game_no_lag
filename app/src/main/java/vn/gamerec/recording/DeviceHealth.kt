package vn.gamerec.recording

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class DeviceStatus(val battery: Int?, val temperature: Float?, val freeBytes: Long?, val hot: Boolean) {
    val reduceQuality get() = hot || (battery?.let { it < 20 } == true)
}

class DeviceHealth @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): DeviceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f)
        val power = context.getSystemService(PowerManager::class.java)
        val hot = (Build.VERSION.SDK_INT >= 29 && (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_SEVERE) ||
            (temperature != null && temperature >= 45f)
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()
        return DeviceStatus(percent, temperature, free, hot)
    }

    companion object {
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}
