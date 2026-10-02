package top.pmh13.mctier.network

import org.json.JSONObject
import java.time.LocalDate

data class QuarkSupportStats(
    val successDays: Int = 0,
    val pcReferenceCents: Long = 0,
    val mobileReferenceCents: Long = 0,
    val firstDay: String? = null,
    val lastDay: String? = null,
    val todayAttempted: Boolean = false,
)

/** Local successful transfers, not Quark's attribution or settlement ledger. */
internal object QuarkContributionLedger {
    fun recordSuccess(saved: JSONObject, account: String, day: String) {
        require(account.isNotBlank() && LocalDate.parse(day).toString() == day)
        val all = saved.optJSONObject("contributions") ?: JSONObject().also { saved.put("contributions", it) }
        val days = all.optJSONObject(account) ?: JSONObject().also { all.put(account, it) }
        if (!days.has(day)) days.put(day, JSONObject()
            .put("pcCents", 22).put("mobileCents", 47).put("rulesVersion", "2026-09-01"))
    }

    fun summary(saved: JSONObject, account: String, today: String): QuarkSupportStats {
        if (account.isBlank()) return QuarkSupportStats()
        val records = saved.optJSONObject("contributions")?.optJSONObject(account) ?: JSONObject()
        val days = records.keys().asSequence().filter { day ->
            val record = records.optJSONObject(day)
            runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false) &&
                record != null && record.optLong("pcCents", -1) >= 0 && record.optLong("mobileCents", -1) >= 0
        }.sorted().toList()
        return QuarkSupportStats(
            successDays = days.size,
            pcReferenceCents = days.sumOf { records.getJSONObject(it).getLong("pcCents") },
            mobileReferenceCents = days.sumOf { records.getJSONObject(it).getLong("mobileCents") },
            firstDay = days.firstOrNull(), lastDay = days.lastOrNull(),
            todayAttempted = saved.optJSONObject("attempts")?.optString(account) == today,
        )
    }
}
