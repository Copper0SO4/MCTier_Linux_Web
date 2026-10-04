package top.pmh13.mctier.network

/** A signed-in account saves once per day. Reserve first; never retry a lost response. */
internal object QuarkDailyAttempt {
    fun due(account: String, day: String, lastDay: String?): Boolean =
        account.isNotEmpty() && day != lastDay

    suspend fun run(
        account: String, day: String, lastDay: String?,
        reserve: () -> Unit, transfer: suspend () -> Unit,
    ): Boolean {
        if (!due(account, day, lastDay)) return false
        reserve() // Failure must prevent any cloud write.
        transfer()
        return true
    }
}
