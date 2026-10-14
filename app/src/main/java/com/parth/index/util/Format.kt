package com.parth.index.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Small display helpers shared by every screen. */
object Format {

    fun size(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun date(millis: Long): String =
        SimpleDateFormat("EEE, d MMM", Locale.ENGLISH).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(millis))

    fun time(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(millis))

    /** Whole days from today (start of day) to the due date: 0 = today. */
    fun daysLeft(dueMillis: Long, now: Long = System.currentTimeMillis()): Long {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val startOfToday = now - (now % dayMs)
        val startOfDue = dueMillis - (dueMillis % dayMs)
        return TimeUnit.MILLISECONDS.toDays(startOfDue - startOfToday)
    }

    fun daysLeftLabel(dueMillis: Long): String {
        val d = daysLeft(dueMillis)
        return when {
            d < 0L -> "${-d} day${if (-d == 1L) "" else "s"} overdue"
            d == 0L -> "Due today"
            d == 1L -> "1 day left"
            else -> "$d days left"
        }
    }

    fun relative(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            mins < 1 -> "Just now"
            mins < 60 -> "${mins}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> date(millis)
        }
    }

    /** First letter(s) for the coloured subject circle: "Mechanical Engg." -> "M",
     *  two-word names keep their first letter only for a clean look. */
    fun initial(name: String): String =
        name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
