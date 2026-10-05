package dev.vory.android.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.toRelativeTime(): String {
    if (this <= 0) return ""
    val diff = System.currentTimeMillis() - this
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(this))
    }
}

fun Long.toClockTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))

fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

fun formatBytes(n: Long): String = when {
    n >= 1_073_741_824 -> "%.1f GB".format(n / 1_073_741_824.0)
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/** True for 10.x, 172.16.x, 192.168.x, 127.x, ::1 — plain http is fine there. */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().removeSuffix(".")
    if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
    val parts = h.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return when {
        parts[0] == 10 -> true
        parts[0] == 172 && parts[1] in 16..31 -> true
        parts[0] == 192 && parts[1] == 168 -> true
        else -> false
    }
}
