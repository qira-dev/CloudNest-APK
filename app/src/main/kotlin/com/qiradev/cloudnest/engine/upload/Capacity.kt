package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus

/**
 * Capacity pre-flight (specs/02 §2):
 * `usable = max(0, quota_free − reserve − outstanding_reservations − safety_margin)`.
 * Only `current` accounts qualify; free space is never invented.
 */
object Ineligibility {
    const val ACCOUNT_DISCONNECTED = "account_disconnected"
    const val QUOTA_STALE = "quota_stale"
    const val QUOTA_UNKNOWN = "quota_unknown"
    const val NO_FREE_SPACE = "no_free_space"
}

/** Unlimited accounts carry a sentinel — no fabricated byte counts are stored. */
sealed interface Usable {
    object Unlimited : Usable
    data class Limited(val bytes: Long) : Usable

    val bytesOrMax: Long
        get() = when (this) {
            is Unlimited -> Long.MAX_VALUE
            is Limited -> bytes
        }
}

/**
 * One routing bucket. Accounts sharing a `quota_scope_key` actually share one
 * quota pool, so the pool counts ONCE (specs/02 §2); the representative is the
 * deterministic member that receives the uploads and reservations.
 */
data class AccountCapacity(
    val representativeId: String,
    val memberIds: List<String>,
    val usable: Usable,
    val ineligibilityReason: String? = null,
) {
    val isEligible: Boolean get() = ineligibilityReason == null
}

object Capacity {

    /** [reservationsByAccount] maps account id → outstanding reserved bytes. */
    fun evaluate(
        accounts: List<ProviderAccountEntity>,
        reservationsByAccount: Map<String, Long>,
        reservePerAccountBytes: Long,
        safetyMarginBytes: Long,
    ): List<AccountCapacity> {
        val pools = LinkedHashMap<String, MutableList<ProviderAccountEntity>>()
        for (account in accounts.sortedBy { it.id }) {
            pools.getOrPut(account.quotaScopeKey ?: "account:${account.id}") { mutableListOf() }.add(account)
        }

        return pools.map { (_, members) ->
            val representative = members.first()
            val disconnected = members.firstOrNull { it.status != ProviderAccountStatus.CONNECTED }
            if (disconnected != null) {
                return@map AccountCapacity(
                    representativeId = representative.id,
                    memberIds = members.map { it.id },
                    usable = Usable.Limited(0),
                    ineligibilityReason = Ineligibility.ACCOUNT_DISCONNECTED,
                )
            }
            val stale = members.firstOrNull { it.quotaStatus != QuotaStatus.CURRENT }
            if (stale != null) {
                val reason =
                    if (stale.quotaStatus == QuotaStatus.STALE) Ineligibility.QUOTA_STALE else Ineligibility.QUOTA_UNKNOWN
                return@map AccountCapacity(
                    representativeId = representative.id,
                    memberIds = members.map { it.id },
                    usable = Usable.Limited(0),
                    ineligibilityReason = reason,
                )
            }

            val unlimited = members.any { it.quotaUnlimited == 1 }
            if (unlimited) {
                return@map AccountCapacity(
                    representativeId = representative.id,
                    memberIds = members.map { it.id },
                    usable = Usable.Unlimited,
                )
            }

            // A missing observed total means the quota is effectively unknown.
            val missing = members.firstOrNull { it.quotaTotalBytes == null || it.quotaUsedBytes == null }
            if (missing != null) {
                return@map AccountCapacity(
                    representativeId = representative.id,
                    memberIds = members.map { it.id },
                    usable = Usable.Limited(0),
                    ineligibilityReason = Ineligibility.QUOTA_UNKNOWN,
                )
            }

            // Pool space counts once: the tightest free observation in the pool
            // minus every reservation held by any pool member.
            val poolFree = members.minOf { it.quotaTotalBytes!! - it.quotaUsedBytes!! }
            val poolReservations = members.sumOf { reservationsByAccount[it.id] ?: 0L }
            val usable = poolFree - reservePerAccountBytes - poolReservations - safetyMarginBytes
            AccountCapacity(
                representativeId = representative.id,
                memberIds = members.map { it.id },
                usable = Usable.Limited(maxOf(0L, usable)),
                ineligibilityReason = if (usable <= 0) Ineligibility.NO_FREE_SPACE else null,
            )
        }
    }
}
