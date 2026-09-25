package com.hatsyrei.maidnative.domain.tools

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** The device's own clock and zone, which the endpoint's host may not share. */
object GetDatetime : Tool {
    override val name = "get_datetime"
    override val label = "Date & time"
    override val summary = "Current date, time and time zone."
    override val description =
        "Get the current local date, time, weekday and time zone of the user's device."

    override fun parameters(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    override suspend fun invoke(arguments: JSONObject): String = format(Calendar.getInstance())

    internal fun format(calendar: Calendar): String {
        val zone: TimeZone = calendar.timeZone
        val offsetMinutes = zone.getOffset(calendar.timeInMillis) / 60_000
        val iso = String.format(
            Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            if (offsetMinutes < 0) "-" else "+",
            abs(offsetMinutes) / 60,
            abs(offsetMinutes) % 60,
        )
        return JSONObject()
            .put("datetime", iso)
            // Models are unreliable at deriving the weekday from a date.
            .put("weekday", calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH))
            .put("timezone", zone.id)
            .toString()
            // Android's org.json writes "/" as "\/"; valid, but noise in what the model reads.
            .replace("\\/", "/")
    }
}
