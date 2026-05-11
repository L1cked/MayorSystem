package mayorSystem.application.usecase

import mayorSystem.MayorPlugin
import mayorSystem.data.repository.ElectionRepository
import mayorSystem.data.repository.MayorStoreElectionRepository

class AdminUseCases(
    plugin: MayorPlugin,
    electionRepository: ElectionRepository = MayorStoreElectionRepository(plugin.store)
) {
    val candidates = AdminCandidateCommandActions(plugin, electionRepository)
    val displayRewards = AdminDisplayRewardCommandActions(plugin)
    val elections = AdminElectionCommandActions(plugin, electionRepository)
    val perks = AdminPerkCommandActions(plugin, electionRepository)
    val settings = AdminSettingsCommandActions(plugin)
}
