package com.rr.client.storage

import androidx.room.withTransaction
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.model.SubProfile

object LocalProfileStore {
    suspend fun update(database: AppDatabase, transform: (List<ProxyNode>) -> List<ProxyNode>): SubProfile =
        database.withTransaction {
            val old = database.profileDao().getAllProfiles()
                .firstOrNull { it.id == SubProfile.LOCAL_PROFILE_ID }
                ?.let(SubProfile::fromEntity)?.nodes.orEmpty()
            val profile = SubProfile.local(transform(old))
            database.profileDao().insertProfile(profile.toEntity())
            profile
        }
}
