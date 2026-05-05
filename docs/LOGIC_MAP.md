# MayorSystem Logic Map (Auto-Generated)

This file is generated from source to map declarations and direct dependencies.

## Big To Small Flow
- Plugin lifecycle root: src/main/kotlin/mayorSystem/Main.kt wires services, listeners, command bootstrap, async store load, term ticking, and shutdown cleanup.
- Command root: src/main/kotlin/mayorSystem/cloud/CloudBootstrap.kt and src/main/kotlin/mayorSystem/cloud/MayorCommands.kt register public and admin command trees, then fan out to module command classes.
- Domain core:
  - Elections and term clock: src/main/kotlin/mayorSystem/elections/TermService.kt
  - Persistence facade: src/main/kotlin/mayorSystem/data/MayorStore.kt
  - Persistence engines: src/main/kotlin/mayorSystem/data/store/SqliteMayorStore.kt and src/main/kotlin/mayorSystem/data/store/MysqlMayorStore.kt
  - Perk engine: src/main/kotlin/mayorSystem/perks/PerkService.kt
  - Display Reward planning and targeting: src/main/kotlin/mayorSystem/rewards/* and src/main/kotlin/mayorSystem/service/DisplayRewardTagResolver.kt
- Presentation layer:
  - GUI engine: src/main/kotlin/mayorSystem/ui/Menu.kt and src/main/kotlin/mayorSystem/ui/GuiManager.kt
  - Admin GUI permission gate: src/main/kotlin/mayorSystem/ui/AdminMenuAccess.kt
  - Player and admin menus: files under src/main/kotlin/mayorSystem/ui/menus and all module ui folders
  - Message formatting and token system: src/main/kotlin/mayorSystem/config/Messages.kt, src/main/kotlin/mayorSystem/config/GuiTexts.kt, and src/main/kotlin/mayorSystem/messaging/DisplayTextParser.kt
- World and display integrations:
  - Mayor NPC: src/main/kotlin/mayorSystem/npc/MayorNpcService.kt plus providers in src/main/kotlin/mayorSystem/npc/provider
  - Leaderboard hologram: src/main/kotlin/mayorSystem/hologram/LeaderboardHologramService.kt plus providers in src/main/kotlin/mayorSystem/hologram
  - Showcase arbitration between NPC and hologram: src/main/kotlin/mayorSystem/showcase/ShowcaseService.kt
  - Mayor LuckPerms group assignment: src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt
- Cross-cutting:
  - Settings parse and normalization: src/main/kotlin/mayorSystem/config/Settings.kt
  - Config/resource default synchronization: src/main/kotlin/mayorSystem/config/ConfigDefaultsSync.kt
  - Permissions: src/main/kotlin/mayorSystem/security/Perms.kt
  - Audit and health: src/main/kotlin/mayorSystem/monitoring/AuditService.kt and src/main/kotlin/mayorSystem/monitoring/HealthService.kt
  - Admin mutation layer: src/main/kotlin/mayorSystem/service/AdminActions.kt
  - PlaceholderAPI bridge: src/main/kotlin/mayorSystem/papi/MayorPlaceholderExpansion.kt

## Runtime Sequence
- onEnable loads config/messages/gui text, initializes services, registers listeners/commands, and starts async bootstrap.
- Async bootstrap loads the store, runs startup term catch-up, rebuilds active perk effects, syncs NPC/hologram/showcase, syncs mayor LuckPerms group, then marks the plugin ready.
- Scheduler calls TermService.tick() every 30 seconds unless scheduling is blocked.
- TermService decides election/term transitions, finalizes winners, applies/clears perks and rewards, emits broadcasts, and updates display services.
- GUI and command actions mutate state through MayorStore and AdminActions, with AdminMenuAccess enforcing admin menu entry permissions.
- onDisable flushes and shuts down tasks/services: NPC, prefix service, hologram, skins, audit, and store.

## Per-File Index
## src/main/kotlin/mayorSystem/api/events/MayorPerksAppliedEvent.kt
- Declarations: MayorPerksAppliedEvent
- Non-private functions: getHandlerList, getHandlers
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/events/MayorPerksClearedEvent.kt
- Declarations: MayorPerksClearedEvent
- Non-private functions: getHandlerList, getHandlers
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/MayorSystemApi.kt
- Declarations: MayorSystemApi
- Non-private functions: activePerkIdsForTerm, activePerkIdsOrEmpty, allPerkIds, currentTermOrNull, isPerkActive, perkConfigSection
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/MayorSystemApiImpl.kt
- Declarations: MayorSystemApiImpl
- Non-private functions: activePerkIdsForTerm, activePerkIdsOrEmpty, allPerkIds, currentTermOrNull, isPerkActive, perkConfigSection
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config, hasTermService, perks, settings, store, termService

## src/main/kotlin/mayorSystem/candidates/CandidatesCommands.kt
- Declarations: CandidatesCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminApplyBanSearchMenu, mayorSystem.candidates.ui.AdminCandidatesMenu, mayorSystem.candidates.ui.AdminSettingsApplyMenu, mayorSystem.cloud.CommandContext, mayorSystem.data.CandidateStatus, mayorSystem.security.Perms
- Uses plugin services: adminActions, mainDispatcher, scope, server, store, termService

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanDurationMenu.kt
- Declarations: AdminApplyBanDurationMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanSearchMenu.kt
- Declarations: AdminApplyBanSearchMenu, State
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.service.OfflinePlayerCache, mayorSystem.ui.Menu
- Uses plugin services: gui, offlinePlayers, store

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanTypeMenu.kt
- Declarations: AdminApplyBanTypeMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope, store

## src/main/kotlin/mayorSystem/candidates/ui/AdminCandidatesMenu.kt
- Declarations: AdminCandidatesMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, playerDisplayNames, scope, store, termService

## src/main/kotlin/mayorSystem/candidates/ui/AdminSettingsApplyMenu.kt
- Declarations: AdminSettingsApplyMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/candidates/ui/ConfirmRemoveCandidateMenu.kt
- Declarations: ConfirmRemoveCandidateMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, playerDisplayNames, scope, store

## src/main/kotlin/mayorSystem/cloud/CloudBootstrap.kt
- Declarations: CloudBootstrap
- Non-private functions: createManager, enable
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/cloud/CommandAliasSafety.kt
- Declarations: CommandAliasSafety
- Non-private functions: blockedReason
- Direct mayorSystem imports: (none)
- Uses plugin services: name, Plugin

## src/main/kotlin/mayorSystem/cloud/CommandContext.kt
- Declarations: CommandContext
- Non-private functions: blockIfActionsPaused, checkCooldown, checkPublicAccess, currentGateOptions, dispatch, findOnlinePlayer, msg, onQuit, optionListString, parseBool, registerMenuRoute, requireReady, resolveToggle, rootCommandBuilder, sendOnlinePlayers, withPlayer
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.service.ActionResult, mayorSystem.ui.Menu
- Uses plugin services: config, gui, isReady, messages, server, settings

## src/main/kotlin/mayorSystem/cloud/MayorCommands.kt
- Declarations: MayorCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.candidates.CandidatesCommands, mayorSystem.config.MayorStepdownPolicy, mayorSystem.data.CandidateStatus, mayorSystem.elections.ElectionsCommands, mayorSystem.governance.GovernanceCommands, mayorSystem.maintenance.MaintenanceCommands, mayorSystem.MayorPlugin, mayorSystem.messaging.MessagingCommands, mayorSystem.monitoring.MonitoringCommands, mayorSystem.perks.PerksCommands, mayorSystem.security.Perms, mayorSystem.system.SystemCommands, mayorSystem.ui.menus.ApplySectionsMenu, mayorSystem.ui.menus.CandidateMenu, mayorSystem.ui.menus.MainMenu, mayorSystem.ui.menus.MayorStepDownConfirmMenu, mayorSystem.ui.menus.StatusMenu, mayorSystem.ui.menus.StepDownConfirmMenu, mayorSystem.ui.menus.VoteConfirmMenu, mayorSystem.ui.menus.VoteMenu
- Uses plugin services: applyFlow, gui, isReady, settings, store, termService, voteAccess

## src/main/kotlin/mayorSystem/cloud/TitleCommandAliasListener.kt
- Declarations: TitleCommandAliasListener
- Non-private functions: onPlayerCommand, onServerCommand
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: logger, settings

## src/main/kotlin/mayorSystem/config/ConfigDefaultsSync.kt
- Declarations: (none)
- Non-private functions: syncMissingKeys
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/CustomRequestCondition.kt
- Declarations: CustomRequestCondition
- Non-private functions: next, prev
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/GuiTexts.kt
- Declarations: GuiTexts
- Non-private functions: get, getList, reload
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: dataFolder, getResource, logger, saveResource, settings

## src/main/kotlin/mayorSystem/config/MayorStepdownPolicy.kt
- Declarations: MayorStepdownPolicy
- Non-private functions: next
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/Messages.kt
- Declarations: Messages
- Non-private functions: contains, get, getList, msg, reload
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.messaging.MiniMessageSafety
- Uses plugin services: dataFolder, getResource, logger, saveResource, settings

## src/main/kotlin/mayorSystem/config/Settings.kt
- Declarations: Settings
- Non-private functions: applyTitleTokens, from, isBlocked, isDisabled, isPaused, perksAllowed, resolvedChatPrefix, resolvedTitlePlayerPrefix, titleNameLower
- Direct mayorSystem imports: mayorSystem.rewards.DisplayRewardSettings
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/SystemGateOption.kt
- Declarations: SystemGateOption
- Non-private functions: all, parse
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/TiePolicy.kt
- Declarations: TiePolicy
- Non-private functions: next, prev
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/data/MayorStore.kt
- Declarations: MayorStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, hasEverBeenMayor, hasVoted, highestWinnerTermOrNull, isCandidate, isPerksLocked, isReady, listApplyBans, listRequests, listRequestsForCandidate, loadAsync, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.store.MysqlMayorStore, mayorSystem.data.store.SqliteMayorStore, mayorSystem.data.store.StoreBackend, mayorSystem.data.store.WarmupStore, mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/data/Models.kt
- Declarations: ApplyBan, CandidateEntry, CandidateStatus, CustomPerkRequest, RequestStatus
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/data/store/MysqlMayorStore.kt
- Declarations: MysqlMayorStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, fallbackAlphabetical, hasEverBeenMayor, hasVoted, highestWinnerTermOrNull, isCandidate, isPerksLocked, listApplyBans, listRequests, listRequestsForCandidate, load, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, toPublic, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config

## src/main/kotlin/mayorSystem/data/store/SqliteMayorStore.kt
- Declarations: SqliteMayorStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, fallbackAlphabetical, hasEverBeenMayor, hasVoted, highestWinnerTermOrNull, isCandidate, isPerksLocked, listApplyBans, listRequests, listRequestsForCandidate, load, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, toPublic, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config, dataFolder

## src/main/kotlin/mayorSystem/data/store/StoreBackend.kt
- Declarations: StoreBackend, WarmupStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, hasEverBeenMayor, hasVoted, highestWinnerTermOrNull, isCandidate, isPerksLocked, listApplyBans, listRequests, listRequestsForCandidate, load, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/economy/EconomyHook.kt
- Declarations: EconomyHook
- Non-private functions: balance, deposit, has, isAvailable, providerName, refresh, withdraw
- Direct mayorSystem imports: (none)
- Uses plugin services: Plugin

## src/main/kotlin/mayorSystem/elections/ElectionsCommands.kt
- Declarations: ElectionsCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.elections.ui.AdminElectionMenu, mayorSystem.elections.ui.AdminElectionSettingsMenu, mayorSystem.elections.ui.AdminFakeVotesMenu, mayorSystem.elections.ui.AdminForceElectFlow, mayorSystem.elections.ui.AdminForceElectMenu, mayorSystem.elections.ui.AdminForceElectSectionsMenu, mayorSystem.elections.ui.AdminSettingsTermMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, gui, mainDispatcher, perks, scope, server, store, termService

## src/main/kotlin/mayorSystem/elections/TermService.kt
- Declarations: TermService, TermTimes
- Non-private functions: broadcastApplyActivity, broadcastVoteActivity, clearAllOverridesForTerm, compute, computeCached, computeNow, forceElectNow, forceEndElectionNow, forceMayorStepdownNow, forceStartElectionNow, invalidateScheduleCache, isElectionOpen, tick, tickNow, timesFor
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.SystemGateOption, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.messaging.MayorBroadcasts, mayorSystem.messaging.MiniMessageSafety
- Uses plugin services: config, hasMayorNpc, hasMayorUsernamePrefix, hasShowcase, logger, mainDispatcher, mayorNpc, mayorUsernamePrefix, perks, playerDisplayNames, reloadSettingsOnly, saveConfig, scope, server, settings, showcase, store

## src/main/kotlin/mayorSystem/elections/ui/AdminElectionMenu.kt
- Declarations: AdminElectionMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminElectionSettingsMenu.kt
- Declarations: AdminElectionSettingsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/elections/ui/AdminFakeVoteAdjustMenu.kt
- Declarations: AdminFakeVoteAdjustMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, messages, playerDisplayNames, scope, store

## src/main/kotlin/mayorSystem/elections/ui/AdminFakeVotesMenu.kt
- Declarations: AdminFakeVotesMenu
- Non-private functions: draw, titleFor
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui, playerDisplayNames, store

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectConfirmMenu.kt
- Declarations: AdminForceElectConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, perks, scope, settings, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectFlow.kt
- Declarations: AdminForceElectFlow, Mode, Session
- Non-private functions: clear, get, start
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectMenu.kt
- Declarations: AdminForceElectMenu, State
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.service.OfflinePlayerCache, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, offlinePlayers, perks, scope, store, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectPerksMenu.kt
- Declarations: AdminForceElectPerksMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.menus.ApplyPerksMenu
- Uses plugin services: config, gui, perks, settings, store

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectSectionsMenu.kt
- Declarations: AdminForceElectSectionsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.menus.ApplyPerksMenu
- Uses plugin services: config, gui, settings, store

## src/main/kotlin/mayorSystem/elections/ui/AdminSettingsTermMenu.kt
- Declarations: AdminSettingsTermMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/governance/GovernanceCommands.kt
- Declarations: GovernanceCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.TiePolicy, mayorSystem.governance.ui.GovernanceSettingsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, mainDispatcher, scope

## src/main/kotlin/mayorSystem/governance/ui/AdminBonusTermMenu.kt
- Declarations: AdminBonusTermMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/governance/ui/GovernanceSettingsMenu.kt
- Declarations: GovernanceSettingsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.TiePolicy, mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/hologram/DecentHologramsHook.kt
- Declarations: DecentHologramsHook
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: (none)
- Uses plugin services: server

## src/main/kotlin/mayorSystem/hologram/DisabledLeaderboardHologramProvider.kt
- Declarations: DisabledLeaderboardHologramProvider
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/hologram/FancyHologramsHook.kt
- Declarations: FancyHologramsHook
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: server

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramProvider.kt
- Declarations: LeaderboardHologramProvider
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramProviderFactory.kt
- Declarations: LeaderboardHologramProviderFactory
- Non-private functions: byId, select, watchedPluginNames
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramService.kt
- Declarations: LeaderboardHologramService
- Non-private functions: backendId, forceUpdate, isAvailable, onDisable, onEnable, onPluginDisable, onPluginEnable, onReload, refreshIfActive, remove, setActive, spawnHere
- Direct mayorSystem imports: mayorSystem.data.CandidateEntry, mayorSystem.elections.TermTimes, mayorSystem.MayorPlugin, mayorSystem.showcase.ShowcaseMode, mayorSystem.util.loggedTask
- Uses plugin services: config, hasShowcase, isReady, loggedTask, logger, messages, name, playerDisplayNames, saveConfig, server, settings, showcase, store, termService

## src/main/kotlin/mayorSystem/Main.kt
- Declarations: MayorPlugin, ReadyState
- Non-private functions: hasDisplayRewardTags, hasLeaderboardHologram, hasMayorNpc, hasMayorUsernamePrefix, hasShowcase, hasTermService, isLoading, isReady, onDisable, onEnable, onPluginDisable, onPluginEnable, onServiceRegister, onServiceUnregister, reloadEverything, reloadEverythingVerified, reloadSettingsOnly
- Direct mayorSystem imports: mayorSystem.api.MayorSystemApi, mayorSystem.api.MayorSystemApiImpl, mayorSystem.cloud.CloudBootstrap, mayorSystem.cloud.TitleCommandAliasListener, mayorSystem.config.ConfigDefaultsSync, mayorSystem.config.GuiTexts, mayorSystem.config.Messages, mayorSystem.config.Settings, mayorSystem.data.MayorStore, mayorSystem.economy.EconomyHook, mayorSystem.elections.TermService, mayorSystem.hologram.LeaderboardHologramService, mayorSystem.messaging.ChatPrompts, mayorSystem.messaging.MayorBroadcasts, mayorSystem.monitoring.AuditService, mayorSystem.monitoring.HealthService, mayorSystem.npc.MayorNpcService, mayorSystem.papi.MayorPlaceholderExpansion, mayorSystem.perks.PerkJoinListener, mayorSystem.perks.PerkService, mayorSystem.service.ActionCoordinator, mayorSystem.service.AdminActions, mayorSystem.service.ApplyFlowService, mayorSystem.service.DisplayRewardTagResolver, mayorSystem.service.MayorUsernamePrefixService, mayorSystem.service.OfflinePlayerCache, mayorSystem.service.PlayerDisplayNameService, mayorSystem.service.SkinService, mayorSystem.service.SpigotUpdateNotifier, mayorSystem.service.VoteAccessService, mayorSystem.showcase.ShowcaseService, mayorSystem.ui.GuiManager, mayorSystem.util.loggedTask, mayorSystem.util.PaperMainDispatcher
- Uses plugin services: java, name, Plugin, ServicePriority

## src/main/kotlin/mayorSystem/maintenance/MaintenanceCommands.kt
- Declarations: MaintenanceCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.maintenance.ui.AdminDebugMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, mainDispatcher, scope

## src/main/kotlin/mayorSystem/maintenance/ui/AdminDebugMenu.kt
- Declarations: AdminDebugMenu
- Non-private functions: draw, nextSlot
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, offlinePlayers, scope

## src/main/kotlin/mayorSystem/maintenance/ui/AdminResetElectionConfirmMenu.kt
- Declarations: AdminResetElectionConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope

## src/main/kotlin/mayorSystem/messaging/ChatPrompts.kt
- Declarations: BioEdit, ChatPrompts, CustomReq
- Non-private functions: beginBioEditFlow, beginCustomPerkRequestFlow, cancel, onChat, onQuit
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin
- Uses plugin services: gui, mainDispatcher, messages, scope, settings, store, termService

## src/main/kotlin/mayorSystem/messaging/DisplayTextParser.kt
- Declarations: DisplayTextParser
- Non-private functions: component, escapePlain, legacyAmpersand, mini, plain
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/messaging/MayorBroadcasts.kt
- Declarations: MayorBroadcasts
- Non-private functions: broadcastChat, deserialize, hasPapi, setCommandRoot, setTitleName
- Direct mayorSystem imports: mayorSystem.cloud.CommandAliasSafety
- Uses plugin services: Plugin

## src/main/kotlin/mayorSystem/messaging/MessagingCommands.kt
- Declarations: MessagingCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.messaging.ui.AdminBroadcastSettingsMenu, mayorSystem.messaging.ui.AdminMessagingMenu, mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, mainDispatcher, scope

## src/main/kotlin/mayorSystem/messaging/MiniMessageSafety.kt
- Declarations: MiniMessageSafety
- Non-private functions: applyPlaceholderApiSafely, sanitizeUntrustedMiniMessage
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/messaging/ui/AdminBroadcastSettingsMenu.kt
- Declarations: AdminBroadcastSettingsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, mainDispatcher, scope

## src/main/kotlin/mayorSystem/messaging/ui/AdminMessagingMenu.kt
- Declarations: AdminMessagingMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/messaging/ui/AdminSettingsChatPromptsMenu.kt
- Declarations: AdminSettingsChatPromptsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/monitoring/AuditModels.kt
- Declarations: AuditEvent
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/monitoring/AuditService.kt
- Declarations: AuditService
- Non-private functions: export, log, recent, shutdown
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, dataFolder, logger

## src/main/kotlin/mayorSystem/monitoring/HealthService.kt
- Declarations: HealthCheck, HealthService, HealthSeverity
- Non-private functions: run
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.rewards.DeluxeTagsIntegration, mayorSystem.rewards.DisplayRewardMode, mayorSystem.rewards.DisplayRewardSubject, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.DisplayRewardText, mayorSystem.rewards.TagIconSettings, mayorSystem.rewards.TagRewardSettings
- Uses plugin services: config, economy, hasLeaderboardHologram, hasTermService, isReady, leaderboardHologram, missing, ok, perks, playerDisplayNames, server, settings, store, termService

## src/main/kotlin/mayorSystem/monitoring/MonitoringCommands.kt
- Declarations: MonitoringCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.monitoring.ui.AdminAuditMenu, mayorSystem.monitoring.ui.AdminHealthMenu, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.security.Perms
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/monitoring/ui/AdminAuditMenu.kt
- Declarations: AdminAuditMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.monitoring.AuditEvent, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.ui.Menu
- Uses plugin services: audit, gui, messages

## src/main/kotlin/mayorSystem/monitoring/ui/AdminHealthMenu.kt
- Declarations: AdminHealthMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.monitoring.HealthCheck, mayorSystem.monitoring.HealthSeverity, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.ui.Menu
- Uses plugin services: economy, gui, health, logger, messages

## src/main/kotlin/mayorSystem/monitoring/ui/AdminMonitoringMenu.kt
- Declarations: AdminMonitoringMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/npc/MayorNpcDisplayNames.kt
- Declarations: MayorNpcDisplayNames
- Non-private functions: legacy, mini
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/npc/MayorNpcIdentity.kt
- Declarations: MayorNpcIdentity
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/npc/MayorNpcService.kt
- Declarations: MayorNpcService
- Non-private functions: forceUpdate, forceUpdateMayor, forceUpdateMayorForTerm, onDamage, onDisable, onEnable, onInteractAtEntity, onInteractEntity, onPluginEnable, onReload, openMayorCard, remove, setActive, spawnHere
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.npc.provider.MayorNpcProvider, mayorSystem.npc.provider.MayorNpcProviderFactory, mayorSystem.ui.menus.MainMenu, mayorSystem.ui.menus.MayorProfileMenu, mayorSystem.util.loggedTask
- Uses plugin services: config, gui, hasShowcase, isEnabled, isReady, loggedTask, logger, messages, name, playerDisplayNames, saveConfig, server, settings, showcase, skins, store, termService

## src/main/kotlin/mayorSystem/npc/provider/CitizensMayorNpcProvider.kt
- Declarations: CitizensMayorNpcProvider
- Non-private functions: isAvailable, isMayorNpc, onDisable, onEnable, remove, restoreFromConfig, spawnOrMove, updateMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcDisplayNames, mayorSystem.npc.MayorNpcIdentity, mayorSystem.util.loggedTask
- Uses plugin services: config, isEnabled, loggedTask, logger, mayorNpc, messages, saveConfig, server, settings

## src/main/kotlin/mayorSystem/npc/provider/DisabledMayorNpcProvider.kt
- Declarations: DisabledMayorNpcProvider
- Non-private functions: isAvailable, isMayorNpc, onDisable, onEnable, remove, restoreFromConfig, spawnOrMove, updateMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: logger

## src/main/kotlin/mayorSystem/npc/provider/FancyNpcsMayorNpcProvider.kt
- Declarations: FancyNpcsMayorNpcProvider
- Non-private functions: isAvailable, isMayorNpc, onDisable, onEnable, onJoin, remove, restoreFromConfig, spawnOrMove, updateMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcDisplayNames, mayorSystem.npc.MayorNpcIdentity, mayorSystem.showcase.ShowcaseMode, mayorSystem.showcase.ShowcaseTarget, mayorSystem.util.loggedTask
- Uses plugin services: config, EventExecutor, hasShowcase, loggedTask, mayorNpc, messages, saveConfig, server, settings, showcase

## src/main/kotlin/mayorSystem/npc/provider/MayorNpcProvider.kt
- Declarations: MayorNpcProvider
- Non-private functions: isAvailable, isMayorNpc, onDisable, onEnable, remove, restoreFromConfig, spawnOrMove, updateMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/npc/provider/MayorNpcProviderFactory.kt
- Declarations: MayorNpcProviderFactory
- Non-private functions: byId, select
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/papi/MayorPlaceholderExpansion.kt
- Declarations: MayorPlaceholderExpansion
- Non-private functions: getAuthor, getIdentifier, getVersion, onPlaceholderRequest, persist
- Direct mayorSystem imports: mayorSystem.data.CandidateEntry, mayorSystem.MayorPlugin, mayorSystem.messaging.DisplayTextParser
- Uses plugin services: isReady, playerDisplayNames, pluginMeta, settings, store, termService

## src/main/kotlin/mayorSystem/perks/PerkJoinListener.kt
- Declarations: PerkJoinListener
- Non-private functions: onEffectRemoved, onJoin, onQuit, onRespawn
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: perks, settings

## src/main/kotlin/mayorSystem/perks/PerksCommands.kt
- Declarations: PerksCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.data.RequestStatus, mayorSystem.perks.ui.AdminPerkCatalogMenu, mayorSystem.perks.ui.AdminPerkRefreshMenu, mayorSystem.perks.ui.AdminPerkRequestsMenu, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, config, gui, mainDispatcher, scope, store, termService

## src/main/kotlin/mayorSystem/perks/PerkService.kt
- Declarations: PerkDef, PerkOrigin, PerkService
- Non-private functions: applyActiveEffects, applyPerks, availablePerksForCandidate, cleanupPlayerCache, clearPerks, countSelectedInSection, displayNameFor, isActiveGlobalEffect, isPerkSectionAvailable, isSellAddonAvailable, isSkyblockStyleAddonAvailable, orderedSectionIds, perkSectionBlockReason, perksForSection, presetPerks, rebuildActiveEffectsForTerm, refreshAllOnlinePlayers, refreshPlayer, reloadFromConfig, resolveLore, resolveText, sectionEmptyReason, sectionIdForPerk, sectionLimitViolations, sectionPickLimit, skyblockStyleAddonName
- Direct mayorSystem imports: mayorSystem.api.events.MayorPerksAppliedEvent, mayorSystem.api.events.MayorPerksClearedEvent, mayorSystem.config.SystemGateOption, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.messaging.MayorBroadcasts, mayorSystem.messaging.MiniMessageSafety
- Uses plugin services: config, isEnabled, logger, server, settings, store

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkCatalogMenu.kt
- Declarations: AdminPerkCatalogMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, config, gui, perks

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkRefreshMenu.kt
- Declarations: AdminPerkRefreshMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkRequestsMenu.kt
- Declarations: AdminPerkRequestsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, scope, server, store, termService

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkSectionMenu.kt
- Declarations: AdminPerkSectionMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, config, gui, mainDispatcher, perks, scope

## src/main/kotlin/mayorSystem/perks/ui/AdminPerksMenu.kt
- Declarations: AdminPerksMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/perks/ui/AdminSettingsCustomRequestsMenu.kt
- Declarations: AdminSettingsCustomRequestsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.config.CustomRequestCondition, mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/rewards/DeluxeTagsIntegration.kt
- Declarations: DeluxeTagsCapabilities, DeluxeTagsIntegration, DeluxeTagsOperationResult, DeluxeTagsTagSnapshot
- Non-private functions: activeTagId, capabilities, clearTag, deferred, ensureTag, ensureTagAsync, failed, hasTag, loadedTagIds, orderConflict, selectTag, tagSnapshot
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: logger, Plugin, server

## src/main/kotlin/mayorSystem/rewards/DisplayRewardMode.kt
- Declarations: DisplayRewardMode
- Non-private functions: includesRank, includesTag, label, next, parse, parseOrDefault
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardModeLore.kt
- Declarations: DisplayRewardModeLore
- Non-private functions: lines
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardPlanner.kt
- Declarations: DisplayRewardPlan, DisplayRewardPlanner, TrackedDisplayReward
- Non-private functions: forMayor, removal
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardSettings.kt
- Declarations: DisplayRewardSettings, RankRewardSettings, TagIconSettings, TagRewardSettings
- Non-private functions: from, isUsableItemMaterial, materialOrDefault, materialOrNull, modeFor, normalizeMaterial, permissionNode
- Direct mayorSystem imports: mayorSystem.security.Perms
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardTagId.kt
- Declarations: DisplayRewardTagId
- Non-private functions: isValid
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardTargetResolver.kt
- Declarations: DisplayRewardSubject, DisplayRewardTargets, InvalidDisplayRewardTarget
- Non-private functions: resolve
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardTargetType.kt
- Declarations: DisplayRewardTargetType
- Non-private functions: parse
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/rewards/DisplayRewardText.kt
- Declarations: DisplayRewardText
- Non-private functions: plain, previewMini
- Direct mayorSystem imports: mayorSystem.messaging.DisplayTextParser
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/security/Perms.kt
- Declarations: Perms
- Non-private functions: canOpenAdminPanel, hasAny, isAdmin
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/service/ActionCoordinator.kt
- Declarations: ActionCoordinator
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/service/ActionResult.kt
- Declarations: ActionResult, Failure, Rejected, Success
- Non-private functions: send
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: messages

## src/main/kotlin/mayorSystem/service/AdminActions.kt
- Declarations: AdminActions
- Non-private functions: addAll, clearAllOverridesForTerm, clearApplyBan, clearForcedMayor, findCandidateByName, forceElectNowWithPerks, forceEndElectionNow, forceStartElectionNow, inspectDisplayRewardTarget, listDisplayRewardTargets, refreshPerksAll, refreshPerksPlayer, reload, removeDisplayRewardTarget, resetDisplayRewardTagIcon, resetElectionTerms, setApplyBanPermanent, setApplyBanTemp, setCandidateStatus, setDisplayRewardDefaultMode, setDisplayRewardTagIconCustomModelData, setDisplayRewardTagIconMaterial, setDisplayRewardTarget, setFakeVoteAdjustment, setForcedMayorWithPerks, setPerkEnabled, setPerkSectionEnabled, setRequestStatus, syncDisplayReward, toggleDisplayRewardTagIconGlint, updateConfig, updateDisplayRewardConfig, updatePerkConfig, updateSettingsConfig
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.rewards.DisplayRewardMode, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.DisplayRewardTargetType, mayorSystem.rewards.DisplayRewardText, mayorSystem.rewards.TagIconSettings, mayorSystem.security.Perms
- Uses plugin services: actionCoordinator, audit, config, displayRewardTags, getResource, hasDisplayRewardTags, hasLeaderboardHologram, hasMayorNpc, hasMayorUsernamePrefix, hasTermService, leaderboardHologram, logger, mainDispatcher, mayorNpc, mayorUsernamePrefix, offlinePlayers, perks, playerDisplayNames, reloadEverything, reloadEverythingVerified, reloadSettingsOnly, saveConfig, server, settings, store, termService

## src/main/kotlin/mayorSystem/service/ApplyFlowService.kt
- Declarations: ApplyFlowService, Session
- Non-private functions: clear, get, getOrStart, onQuit, setSelected, start
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/service/DisplayRewardTagResolver.kt
- Declarations: DisplayRewardTagPrefixResolver, DisplayRewardTagResolver, LiveTagSource
- Non-private functions: activeTagId, clear, isPrimaryThread, onlinePlayer, onPlayerCommand, onPlayerJoin, onPlayerQuit, onPluginDisable, onPluginEnable, onServerCommand, resolvePrefix, tagDisplay
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.messaging.DisplayTextParser, mayorSystem.rewards.DeluxeTagsIntegration, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.TagRewardSettings
- Uses plugin services: config, name, server, settings

## src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt
- Declarations: MayorUsernamePrefixService
- Non-private functions: completedFalse, completedTrue, onDisable, onEnable, onJoin, onPluginEnable, onReloadSettings, onServiceRegister, queueRemoval, syncAllOnline, syncKnownMayor, syncPlayer, validRankGroupOrNull, validTagIdOrNull
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.rewards.DeluxeTagsIntegration, mayorSystem.rewards.DeluxeTagsOperationResult, mayorSystem.rewards.DisplayRewardMode, mayorSystem.rewards.DisplayRewardPlanner, mayorSystem.rewards.DisplayRewardSettings, mayorSystem.rewards.DisplayRewardSubject, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.TrackedDisplayReward
- Uses plugin services: config, displayRewardTags, hasDisplayRewardTags, hasTermService, isEnabled, isReady, logger, messages, name, saveConfig, server, settings, store, termService

## src/main/kotlin/mayorSystem/service/OfflinePlayerCache.kt
- Declarations: Entry, OfflinePlayerCache, Snapshot
- Non-private functions: refreshAsync, snapshot
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.util.loggedTask
- Uses plugin services: config, isEnabled, loggedTask, server

## src/main/kotlin/mayorSystem/service/PlayerDisplayNameService.kt
- Declarations: PlayerDisplayNameService, ResolvedPlayerName
- Non-private functions: resolve, resolveMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.messaging.DisplayTextParser
- Uses plugin services: displayRewardTags, settings

## src/main/kotlin/mayorSystem/service/SkinService.kt
- Declarations: RefreshKey, SkinRecord, SkinService
- Non-private functions: applyToProfile, flush, get, isFresh, isLikelyBedrockUuid, request
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, dataFolder, gui, hasMayorNpc, isEnabled, isReady, mayorNpc, scope, server

## src/main/kotlin/mayorSystem/service/SpigotUpdateNotifier.kt
- Declarations: Number, SpigotUpdateNotifier, Text
- Non-private functions: isInstalledOlder, onJoin, onPluginReady, refreshAsync
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: logger, mainDispatcher, pluginMeta, scope

## src/main/kotlin/mayorSystem/service/VoteAccessService.kt
- Declarations: Denial, VoteAccessService
- Non-private functions: activeCandidate, availableCandidateNames, currentElectionTerm, findCandidateByName, isElectionOpen, voteAccessDenial
- Direct mayorSystem imports: mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin
- Uses plugin services: config, settings, store, termService

## src/main/kotlin/mayorSystem/showcase/ShowcaseService.kt
- Declarations: ShowcaseMode, ShowcaseService, ShowcaseTarget
- Non-private functions: activeTarget, desiredTarget, electionOpenNow, mode, parse, sync
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin
- Uses plugin services: config, hasLeaderboardHologram, hasMayorNpc, hasTermService, leaderboardHologram, mayorNpc, saveConfig, settings, store, termService

## src/main/kotlin/mayorSystem/system/SystemCommands.kt
- Declarations: SystemCommands
- Non-private functions: register, targetCommand
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.config.SystemGateOption, mayorSystem.monitoring.HealthSeverity, mayorSystem.monitoring.ui.AdminHealthMenu, mayorSystem.rewards.DeluxeTagsIntegration, mayorSystem.rewards.DisplayRewardMode, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.DisplayRewardTargetType, mayorSystem.rewards.TagIconSettings, mayorSystem.security.Perms, mayorSystem.showcase.ShowcaseMode, mayorSystem.showcase.ShowcaseTarget, mayorSystem.system.ui.AdminDisplayMenu, mayorSystem.system.ui.AdminDisplayRewardTagIconMenu, mayorSystem.system.ui.AdminDisplayRewardTargetsMenu, mayorSystem.system.ui.AdminMenu, mayorSystem.system.ui.AdminSettingsEnableOptionsMenu, mayorSystem.system.ui.AdminSettingsGeneralMenu, mayorSystem.system.ui.AdminSettingsMayorGroupMenu, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.system.ui.AdminSettingsPauseOptionsMenu, mayorSystem.system.ui.DisplayRewardTargetKind
- Uses plugin services: adminActions, gui, health, leaderboardHologram, mainDispatcher, mayorNpc, offlinePlayers, playerDisplayNames, scope, server, settings, showcase

## src/main/kotlin/mayorSystem/system/ui/AdminDisplayMenu.kt
- Declarations: AdminDisplayMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.showcase.ShowcaseMode, mayorSystem.showcase.ShowcaseTarget, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, leaderboardHologram, mainDispatcher, mayorNpc, scope, showcase

## src/main/kotlin/mayorSystem/system/ui/AdminMenu.kt
- Declarations: AdminMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminCandidatesMenu, mayorSystem.elections.ui.AdminElectionMenu, mayorSystem.maintenance.ui.AdminDebugMenu, mayorSystem.MayorPlugin, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.menus.MainMenu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsEnableOptionsMenu.kt
- Declarations: AdminSettingsEnableOptionsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsGeneralMenu.kt
- Declarations: AdminSettingsGeneralMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsMayorGroupMenu.kt
- Declarations: AdminDisplayRewardTagIconMenu, AdminDisplayRewardTargetRemoveConfirmMenu, AdminDisplayRewardTargetsMenu, AdminSettingsMayorGroupMenu, DisplayRewardTargetKind
- Non-private functions: draw, entries, targetType, titleFor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.rewards.DisplayRewardMode, mayorSystem.rewards.DisplayRewardModeLore, mayorSystem.rewards.DisplayRewardSettings, mayorSystem.rewards.DisplayRewardTagId, mayorSystem.rewards.DisplayRewardTargetType, mayorSystem.rewards.DisplayRewardText, mayorSystem.rewards.TagIconSettings, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, health, mainDispatcher, messages, scope, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsMenu.kt
- Declarations: AdminSettingsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminSettingsApplyMenu, mayorSystem.elections.ui.AdminElectionSettingsMenu, mayorSystem.elections.ui.AdminSettingsTermMenu, mayorSystem.governance.ui.GovernanceSettingsMenu, mayorSystem.MayorPlugin, mayorSystem.messaging.ui.AdminBroadcastSettingsMenu, mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu, mayorSystem.perks.ui.AdminPerkCatalogMenu, mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsPauseOptionsMenu.kt
- Declarations: AdminSettingsPauseOptionsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, settings

## src/main/kotlin/mayorSystem/ui/AdminMenuAccess.kt
- Declarations: AdminMenuAccess
- Non-private functions: canOpen, isAdminMenu
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminApplyBanDurationMenu, mayorSystem.candidates.ui.AdminApplyBanSearchMenu, mayorSystem.candidates.ui.AdminApplyBanTypeMenu, mayorSystem.candidates.ui.AdminCandidatesMenu, mayorSystem.candidates.ui.AdminSettingsApplyMenu, mayorSystem.candidates.ui.ConfirmRemoveCandidateMenu, mayorSystem.elections.ui.AdminElectionMenu, mayorSystem.elections.ui.AdminElectionSettingsMenu, mayorSystem.elections.ui.AdminFakeVoteAdjustMenu, mayorSystem.elections.ui.AdminFakeVotesMenu, mayorSystem.elections.ui.AdminForceElectConfirmMenu, mayorSystem.elections.ui.AdminForceElectMenu, mayorSystem.elections.ui.AdminForceElectPerksMenu, mayorSystem.elections.ui.AdminForceElectSectionsMenu, mayorSystem.elections.ui.AdminSettingsTermMenu, mayorSystem.governance.ui.AdminBonusTermMenu, mayorSystem.governance.ui.GovernanceSettingsMenu, mayorSystem.maintenance.ui.AdminDebugMenu, mayorSystem.maintenance.ui.AdminResetElectionConfirmMenu, mayorSystem.messaging.ui.AdminBroadcastSettingsMenu, mayorSystem.messaging.ui.AdminMessagingMenu, mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu, mayorSystem.monitoring.ui.AdminAuditMenu, mayorSystem.monitoring.ui.AdminHealthMenu, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.perks.ui.AdminPerkCatalogMenu, mayorSystem.perks.ui.AdminPerkRefreshMenu, mayorSystem.perks.ui.AdminPerkRequestsMenu, mayorSystem.perks.ui.AdminPerkSectionMenu, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu, mayorSystem.security.Perms, mayorSystem.system.ui.AdminDisplayMenu, mayorSystem.system.ui.AdminDisplayRewardTagIconMenu, mayorSystem.system.ui.AdminDisplayRewardTargetRemoveConfirmMenu, mayorSystem.system.ui.AdminDisplayRewardTargetsMenu, mayorSystem.system.ui.AdminMenu, mayorSystem.system.ui.AdminSettingsEnableOptionsMenu, mayorSystem.system.ui.AdminSettingsGeneralMenu, mayorSystem.system.ui.AdminSettingsMayorGroupMenu, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/ui/GuiManager.kt
- Declarations: GuiManager
- Non-private functions: onClick, onClose, onPrepareAnvil, open, openAnvilPrompt, reopenIfViewing, track
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.menus.ApplyConfirmMenu, mayorSystem.ui.menus.ApplyPerksMenu, mayorSystem.ui.menus.ApplySectionsMenu
- Uses plugin services: applyFlow, isReady, logger, messages, server, settings

## src/main/kotlin/mayorSystem/ui/Menu.kt
- Declarations: Button, UiClickSound
- Non-private functions: buttonAt, open
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.service.ActionResult
- Uses plugin services: gui, guiTexts, messages, playerDisplayNames, settings, skins

## src/main/kotlin/mayorSystem/ui/menus/ApplyConfirmMenu.kt
- Declarations: ApplyConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: actionCoordinator, applyFlow, economy, gui, hasLeaderboardHologram, leaderboardHologram, logger, mainDispatcher, messages, perks, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/ApplyPerksMenu.kt
- Declarations: ApplyPerksMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: applyFlow, gui, messages, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/ApplySectionsMenu.kt
- Declarations: ApplySectionsMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: applyFlow, config, gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidateCustomPerksMenu.kt
- Declarations: CandidateCustomPerksMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui, mainDispatcher, messages, prompts, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidateMenu.kt
- Declarations: CandidateMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, prompts, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerkCatalogMenu.kt
- Declarations: CandidatePerkCatalogMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerkSectionMenu.kt
- Declarations: CandidatePerkSectionMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerksViewMenu.kt
- Declarations: CandidatePerksViewMenu
- Non-private functions: draw, escapeMm
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.perks.PerkDef, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, playerDisplayNames, store

## src/main/kotlin/mayorSystem/ui/menus/ElectionRankingMenu.kt
- Declarations: ElectionRankingMenu
- Non-private functions: draw, titleFor
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, playerDisplayNames, store

## src/main/kotlin/mayorSystem/ui/menus/MainMenu.kt
- Declarations: MainMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui, playerDisplayNames, server, settings, store, termService, voteAccess

## src/main/kotlin/mayorSystem/ui/menus/MayorProfileMenu.kt
- Declarations: MayorProfileMenu
- Non-private functions: draw, escapeMm, titleFor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.PerkDef, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, playerDisplayNames, store

## src/main/kotlin/mayorSystem/ui/menus/MayorStepDownConfirmMenu.kt
- Declarations: MayorStepDownConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: actionCoordinator, gui, mainDispatcher, messages, scope, store, termService

## src/main/kotlin/mayorSystem/ui/menus/StatusMenu.kt
- Declarations: StatusMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, gui, perks, playerDisplayNames, server, store, termService

## src/main/kotlin/mayorSystem/ui/menus/StepDownConfirmMenu.kt
- Declarations: StepDownConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: actionCoordinator, gui, mainDispatcher, messages, perks, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/VoteConfirmMenu.kt
- Declarations: VoteConfirmMenu
- Non-private functions: draw
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: actionCoordinator, gui, hasLeaderboardHologram, leaderboardHologram, mainDispatcher, messages, playerDisplayNames, scope, settings, store, termService, voteAccess

## src/main/kotlin/mayorSystem/ui/menus/VoteMenu.kt
- Declarations: VoteMenu
- Non-private functions: draw, isPerkSortStrict, perkSortSelection, titleFor, updatePerkSort
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, playerDisplayNames, settings, store, termService, voteAccess

## src/main/kotlin/mayorSystem/ui/menus/VotePerkSortMenu.kt
- Declarations: VotePerkSortMenu
- Non-private functions: draw, titleFor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, perks

## src/main/kotlin/mayorSystem/util/PaperDispatchers.kt
- Declarations: PaperMainDispatcher
- Non-private functions: dispatch
- Direct mayorSystem imports: (none)
- Uses plugin services: Plugin

## src/main/kotlin/mayorSystem/util/ScheduledTasks.kt
- Declarations: (none)
- Non-private functions: Plugin
- Direct mayorSystem imports: (none)
- Uses plugin services: Plugin

