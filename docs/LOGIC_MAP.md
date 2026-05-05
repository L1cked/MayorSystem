# MayorSystem Logic Map (Auto-Generated)

This file is generated from source to map declarations and direct dependencies.

## Big To Small Flow
- Plugin lifecycle root: src/main/kotlin/mayorSystem/Main.kt wires all services, listeners, command bootstrap, async store load, and periodic term ticking.
- Command root: src/main/kotlin/mayorSystem/cloud/CloudBootstrap.kt and src/main/kotlin/mayorSystem/cloud/MayorCommands.kt register public and admin command trees, then fan out to module command classes.
- Domain core:
  - Elections and term clock: src/main/kotlin/mayorSystem/elections/TermService.kt
  - Persistence facade: src/main/kotlin/mayorSystem/data/MayorStore.kt
  - Persistence engines: src/main/kotlin/mayorSystem/data/store/SqliteMayorStore.kt and src/main/kotlin/mayorSystem/data/store/MysqlMayorStore.kt
  - Perk engine: src/main/kotlin/mayorSystem/perks/PerkService.kt
- Presentation layer:
  - GUI engine: src/main/kotlin/mayorSystem/ui/Menu.kt and src/main/kotlin/mayorSystem/ui/GuiManager.kt
  - Player and admin menus: files under src/main/kotlin/mayorSystem/ui/menus and all module ui folders
  - Message formatting and token system: src/main/kotlin/mayorSystem/config/Messages.kt
- World and display integrations:
  - Mayor NPC: src/main/kotlin/mayorSystem/npc/MayorNpcService.kt plus providers in src/main/kotlin/mayorSystem/npc/provider
  - Leaderboard hologram: src/main/kotlin/mayorSystem/hologram/LeaderboardHologramService.kt plus providers in src/main/kotlin/mayorSystem/hologram
  - Showcase arbitration between NPC and hologram: src/main/kotlin/mayorSystem/showcase/ShowcaseService.kt
  - Mayor LuckPerms group assignment: src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt
- Cross-cutting:
  - Settings parse and normalization: src/main/kotlin/mayorSystem/config/Settings.kt
  - Audit and health: src/main/kotlin/mayorSystem/monitoring/AuditService.kt and src/main/kotlin/mayorSystem/monitoring/HealthService.kt
  - Admin mutation layer: src/main/kotlin/mayorSystem/service/AdminActions.kt
  - PlaceholderAPI bridge: src/main/kotlin/mayorSystem/papi/MayorPlaceholderExpansion.kt

## Runtime Sequence
- onEnable loads config/messages, initializes services, registers listeners/commands, and starts async bootstrap.
- Async bootstrap loads store, runs startup term catch-up (tickNow), rebuilds active perk effects, syncs NPC + mayor LuckPerms group, then marks plugin ready.
- Scheduler calls TermService.tick() every 30 seconds unless schedule is blocked.
- TermService decides election/term transitions, finalizes winners, applies/clears perks, emits broadcasts, and updates display services.
- GUI and command actions mutate state via MayorStore and AdminActions.
- onDisable flushes and shuts down tasks/services: NPC, prefix service, hologram, skins, audit, and store.

## Per-File Index
## src/main/kotlin/mayorSystem/api/events/MayorPerksAppliedEvent.kt
- Declarations: class MayorPerksAppliedEvent
- Non-private functions: getHandlerList
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/events/MayorPerksClearedEvent.kt
- Declarations: class MayorPerksClearedEvent
- Non-private functions: getHandlerList
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/MayorSystemApi.kt
- Declarations: interface MayorSystemApi
- Non-private functions: activePerkIdsForTerm, activePerkIdsOrEmpty, allPerkIds, currentTermOrNull, isPerkActive, perkConfigSection
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/api/MayorSystemApiImpl.kt
- Declarations: class MayorSystemApiImpl
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config, hasTermService, perks, settings, store, termService

## src/main/kotlin/mayorSystem/candidates/CandidatesCommands.kt
- Declarations: class CandidatesCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminApplyBanSearchMenu, mayorSystem.candidates.ui.AdminCandidatesMenu, mayorSystem.candidates.ui.AdminSettingsApplyMenu, mayorSystem.cloud.CommandContext, mayorSystem.data.CandidateStatus, mayorSystem.security.Perms
- Uses plugin services: adminActions, mainDispatcher, scope, server, store, termService

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanDurationMenu.kt
- Declarations: class AdminApplyBanDurationMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanSearchMenu.kt
- Declarations: class AdminApplyBanSearchMenu, data class State
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.service.OfflinePlayerCache, mayorSystem.ui.Menu
- Uses plugin services: gui, offlinePlayers, store

## src/main/kotlin/mayorSystem/candidates/ui/AdminApplyBanTypeMenu.kt
- Declarations: class AdminApplyBanTypeMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, store

## src/main/kotlin/mayorSystem/candidates/ui/AdminCandidatesMenu.kt
- Declarations: class AdminCandidatesMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, scope, store, termService

## src/main/kotlin/mayorSystem/candidates/ui/AdminSettingsApplyMenu.kt
- Declarations: class AdminSettingsApplyMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/candidates/ui/ConfirmRemoveCandidateMenu.kt
- Declarations: class ConfirmRemoveCandidateMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, store

## src/main/kotlin/mayorSystem/cloud/CloudBootstrap.kt
- Declarations: object CloudBootstrap
- Non-private functions: createManager, enable
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/cloud/CommandContext.kt
- Declarations: class CommandContext
- Non-private functions: blockIfActionsPaused, checkCooldown, checkPublicAccess, currentGateOptions, findOnlinePlayer, msg, onQuit, optionListString, parseBool, registerMenuRoute, requireReady, resolveToggle, sendOnlinePlayers, toggleGateOption, withPlayer
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, isReady, messages, server, settings

## src/main/kotlin/mayorSystem/cloud/MayorCommands.kt
- Declarations: class MayorCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.candidates.CandidatesCommands, mayorSystem.config.MayorStepdownPolicy, mayorSystem.data.CandidateStatus, mayorSystem.elections.ElectionsCommands, mayorSystem.governance.GovernanceCommands, mayorSystem.maintenance.MaintenanceCommands, mayorSystem.MayorPlugin, mayorSystem.messaging.MessagingCommands, mayorSystem.monitoring.MonitoringCommands, mayorSystem.perks.PerksCommands, mayorSystem.security.Perms, mayorSystem.system.SystemCommands, mayorSystem.ui.menus.ApplySectionsMenu, mayorSystem.ui.menus.CandidateMenu, mayorSystem.ui.menus.MainMenu, mayorSystem.ui.menus.MayorStepDownConfirmMenu, mayorSystem.ui.menus.StatusMenu, mayorSystem.ui.menus.StepDownConfirmMenu, mayorSystem.ui.menus.VoteConfirmMenu, mayorSystem.ui.menus.VoteMenu
- Uses plugin services: applyFlow, config, gui, isReady, settings, store, termService

## src/main/kotlin/mayorSystem/cloud/TitleCommandAliasListener.kt
- Declarations: class TitleCommandAliasListener
- Non-private functions: onPlayerCommand, onServerCommand
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: settings

## src/main/kotlin/mayorSystem/config/CustomRequestCondition.kt
- Declarations: enum class CustomRequestCondition
- Non-private functions: next, prev
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/MayorStepdownPolicy.kt
- Declarations: enum class MayorStepdownPolicy
- Non-private functions: next
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/Messages.kt
- Declarations: class Messages
- Non-private functions: contains, get, getList, msg, reload
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: dataFolder, getResource, saveResource, settings

## src/main/kotlin/mayorSystem/config/Settings.kt
- Declarations: data class Settings
- Non-private functions: applyTitleTokens, from, isBlocked, isDisabled, isPaused, perksAllowed, resolvedChatPrefix, resolvedTitlePlayerPrefix, titleNameLower
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/SystemGateOption.kt
- Declarations: enum class SystemGateOption
- Non-private functions: all, parse
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/config/TiePolicy.kt
- Declarations: enum class TiePolicy
- Non-private functions: next, prev
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/data/MayorStore.kt
- Declarations: class MayorStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, hasEverBeenMayor, hasVoted, isCandidate, isPerksLocked, isReady, listApplyBans, listRequests, listRequestsForCandidate, loadAsync, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.store.MysqlMayorStore, mayorSystem.data.store.SqliteMayorStore, mayorSystem.data.store.StoreBackend, mayorSystem.data.store.WarmupStore, mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/data/Models.kt
- Declarations: enum class CandidateStatus, enum class RequestStatus, data class CandidateEntry, data class CustomPerkRequest, data class ApplyBan
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/data/store/MysqlMayorStore.kt
- Declarations: class MysqlMayorStore
- Non-private functions: fallbackAlphabetical, toPublic
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config

## src/main/kotlin/mayorSystem/data/store/SqliteMayorStore.kt
- Declarations: class SqliteMayorStore
- Non-private functions: fallbackAlphabetical, toPublic
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: config, dataFolder

## src/main/kotlin/mayorSystem/data/store/StoreBackend.kt
- Declarations: interface StoreBackend, interface WarmupStore
- Non-private functions: activeApplyBan, addRequest, candidateAppliedAt, candidateBio, candidateEntry, candidates, candidateSteppedDown, chosenPerks, clearApplyBan, clearRequests, clearUnapprovedRequests, clearWinner, electionOpenAnnounced, fakeVoteAdjustment, fakeVoteAdjustments, hasEverBeenMayor, hasVoted, isCandidate, isPerksLocked, listApplyBans, listRequests, listRequestsForCandidate, load, mayorElectedAnnounced, pickWinner, realVoteCounts, removeRequests, requestById, requestCountForCandidate, resetTermData, setApplyBanPermanent, setApplyBanTemp, setCandidate, setCandidateBio, setCandidateStatus, setCandidateStepdown, setChosenPerks, setElectionOpenAnnounced, setFakeVoteAdjustment, setMayorElectedAnnounced, setPerksLocked, setRequestCommands, setRequestStatus, setWinner, shutdown, topCandidates, vote, voteCounts, votedFor, winner, winnerName
- Direct mayorSystem imports: mayorSystem.config.TiePolicy, mayorSystem.data.ApplyBan, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/economy/EconomyHook.kt
- Declarations: class EconomyHook
- Non-private functions: balance, deposit, has, isAvailable, providerName, refresh, withdraw
- Direct mayorSystem imports: (none)
- Uses plugin services: Plugin

## src/main/kotlin/mayorSystem/elections/ElectionsCommands.kt
- Declarations: class ElectionsCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.elections.ui.AdminElectionMenu, mayorSystem.elections.ui.AdminElectionSettingsMenu, mayorSystem.elections.ui.AdminFakeVotesMenu, mayorSystem.elections.ui.AdminForceElectFlow, mayorSystem.elections.ui.AdminForceElectMenu, mayorSystem.elections.ui.AdminForceElectSectionsMenu, mayorSystem.elections.ui.AdminSettingsTermMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, gui, mainDispatcher, perks, scope, server, store, termService

## src/main/kotlin/mayorSystem/elections/TermService.kt
- Declarations: data class TermTimes, class TermService
- Non-private functions: clearAllOverridesForTerm, compute, computeCached, computeNow, forceElectNow, forceEndElectionNow, forceMayorStepdownNow, forceStartElectionNow, invalidateScheduleCache, isElectionOpen, tick, tickNow, timesFor
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.SystemGateOption, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.messaging.MayorBroadcasts
- Uses plugin services: config, hasShowcase, logger, mainDispatcher, mayorNpc, mayorUsernamePrefix, perks, reloadSettingsOnly, saveConfig, scope, server, settings, showcase, store

## src/main/kotlin/mayorSystem/elections/ui/AdminElectionMenu.kt
- Declarations: class AdminElectionMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, scope, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminFakeVoteAdjustMenu.kt
- Declarations: class AdminFakeVoteAdjustMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.data.CandidateStatus, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope, store

## src/main/kotlin/mayorSystem/elections/ui/AdminFakeVotesMenu.kt
- Declarations: class AdminFakeVotesMenu, enum class SortMode, data class CandidateVotes
- Non-private functions: titleFor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.data.CandidateStatus, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui, store

## src/main/kotlin/mayorSystem/elections/ui/AdminElectionSettingsMenu.kt
- Declarations: class AdminElectionSettingsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectConfirmMenu.kt
- Declarations: class AdminForceElectConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, perks, scope, settings, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectFlow.kt
- Declarations: object AdminForceElectFlow, enum class Mode, data class Session
- Non-private functions: clear, get, start
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectMenu.kt
- Declarations: class AdminForceElectMenu, data class State
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.service.OfflinePlayerCache, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, offlinePlayers, perks, store, termService

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectPerksMenu.kt
- Declarations: class AdminForceElectPerksMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.menus.ApplyPerksMenu
- Uses plugin services: config, gui, perks, settings, store

## src/main/kotlin/mayorSystem/elections/ui/AdminForceElectSectionsMenu.kt
- Declarations: class AdminForceElectSectionsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.menus.ApplyPerksMenu
- Uses plugin services: config, gui, settings, store

## src/main/kotlin/mayorSystem/elections/ui/AdminSettingsTermMenu.kt
- Declarations: class AdminSettingsTermMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/governance/GovernanceCommands.kt
- Declarations: class GovernanceCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.TiePolicy, mayorSystem.governance.ui.GovernanceSettingsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions

## src/main/kotlin/mayorSystem/governance/ui/AdminBonusTermMenu.kt
- Declarations: class AdminBonusTermMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, settings

## src/main/kotlin/mayorSystem/governance/ui/GovernanceSettingsMenu.kt
- Declarations: class GovernanceSettingsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.config.TiePolicy, mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/hologram/DecentHologramsHook.kt
- Declarations: class DecentHologramsHook
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.hologram.LeaderboardHologramProvider
- Uses plugin services: server

## src/main/kotlin/mayorSystem/hologram/DisabledLeaderboardHologramProvider.kt
- Declarations: class DisabledLeaderboardHologramProvider
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.hologram.LeaderboardHologramProvider
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/hologram/FancyHologramsHook.kt
- Declarations: class FancyHologramsHook
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.hologram.LeaderboardHologramProvider
- Uses plugin services: server

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramProvider.kt
- Declarations: interface LeaderboardHologramProvider
- Non-private functions: create, formatLines, get, isAvailable, move, remove, setLines
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramProviderFactory.kt
- Declarations: object LeaderboardHologramProviderFactory
- Non-private functions: select, watchedPluginNames
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/hologram/LeaderboardHologramService.kt
- Declarations: class LeaderboardHologramService
- Non-private functions: backendId, forceUpdate, isAvailable, onDisable, onEnable, onPluginDisable, onPluginEnable, onReload, refreshIfActive, remove, setActive, spawnHere
- Direct mayorSystem imports: mayorSystem.data.CandidateEntry, mayorSystem.elections.TermTimes, mayorSystem.MayorPlugin, mayorSystem.showcase.ShowcaseMode
- Uses plugin services: config, hasShowcase, isReady, messages, name, saveConfig, server, settings, showcase, store, termService

## src/main/kotlin/mayorSystem/Main.kt
- Declarations: class MayorPlugin, enum class ReadyState
- Non-private functions: hasLeaderboardHologram, hasMayorNpc, hasMayorUsernamePrefix, hasShowcase, hasTermService, isLoading, isReady, onPluginDisable, onPluginEnable, onServiceRegister, onServiceUnregister, reloadEverything, reloadSettingsOnly
- Direct mayorSystem imports: mayorSystem.api.MayorSystemApi, mayorSystem.api.MayorSystemApiImpl, mayorSystem.cloud.CloudBootstrap, mayorSystem.cloud.TitleCommandAliasListener, mayorSystem.config.Messages, mayorSystem.config.Settings, mayorSystem.data.MayorStore, mayorSystem.economy.EconomyHook, mayorSystem.elections.TermService, mayorSystem.hologram.LeaderboardHologramService, mayorSystem.messaging.ChatPrompts, mayorSystem.messaging.MayorBroadcasts, mayorSystem.monitoring.AuditService, mayorSystem.monitoring.HealthService, mayorSystem.npc.MayorNpcService, mayorSystem.papi.MayorPlaceholderExpansion, mayorSystem.perks.PerkJoinListener, mayorSystem.perks.PerkService, mayorSystem.service.AdminActions, mayorSystem.service.ApplyFlowService, mayorSystem.service.MayorUsernamePrefixService, mayorSystem.service.OfflinePlayerCache, mayorSystem.service.SkinService, mayorSystem.showcase.ShowcaseService, mayorSystem.ui.GuiManager, mayorSystem.util.PaperMainDispatcher
- Uses plugin services: java, name, Plugin, ServicePriority

## src/main/kotlin/mayorSystem/maintenance/MaintenanceCommands.kt
- Declarations: class MaintenanceCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.maintenance.ui.AdminDebugMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions

## src/main/kotlin/mayorSystem/maintenance/ui/AdminDebugMenu.kt
- Declarations: class AdminDebugMenu
- Non-private functions: nextSlot
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, messages, offlinePlayers

## src/main/kotlin/mayorSystem/maintenance/ui/AdminResetElectionConfirmMenu.kt
- Declarations: class AdminResetElectionConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mainDispatcher, messages, scope

## src/main/kotlin/mayorSystem/messaging/ChatPrompts.kt
- Declarations: class ChatPrompts, data class CustomReq, data class BioEdit
- Non-private functions: beginBioEditFlow, beginCustomPerkRequestFlow, cancel, onChat, onQuit
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: gui, mainDispatcher, messages, scope, settings, store

## src/main/kotlin/mayorSystem/messaging/MayorBroadcasts.kt
- Declarations: object MayorBroadcasts
- Non-private functions: broadcastChat, deserialize, hasPapi, setCommandRoot, setTitleName
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/messaging/MessagingCommands.kt
- Declarations: class MessagingCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.messaging.ui.AdminBroadcastSettingsMenu, mayorSystem.messaging.ui.AdminMessagingMenu, mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions

## src/main/kotlin/mayorSystem/messaging/ui/AdminBroadcastSettingsMenu.kt
- Declarations: class AdminBroadcastSettingsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui

## src/main/kotlin/mayorSystem/messaging/ui/AdminMessagingMenu.kt
- Declarations: class AdminMessagingMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/messaging/ui/AdminSettingsChatPromptsMenu.kt
- Declarations: class AdminSettingsChatPromptsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/monitoring/AuditModels.kt
- Declarations: data class AuditEvent
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/monitoring/AuditService.kt
- Declarations: class AuditService
- Non-private functions: export, log, recent, shutdown
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, dataFolder, logger

## src/main/kotlin/mayorSystem/monitoring/HealthService.kt
- Declarations: enum class HealthSeverity, data class HealthCheck, class HealthService
- Non-private functions: run
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, economy, perks, server, settings, store, termService

## src/main/kotlin/mayorSystem/monitoring/MonitoringCommands.kt
- Declarations: class MonitoringCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.monitoring.ui.AdminAuditMenu, mayorSystem.monitoring.ui.AdminHealthMenu, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.security.Perms
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/monitoring/ui/AdminAuditMenu.kt
- Declarations: class AdminAuditMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.monitoring.AuditEvent, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.ui.Menu
- Uses plugin services: audit, gui

## src/main/kotlin/mayorSystem/monitoring/ui/AdminHealthMenu.kt
- Declarations: class AdminHealthMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.monitoring.HealthCheck, mayorSystem.monitoring.HealthSeverity, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.ui.Menu
- Uses plugin services: economy, gui, health, logger

## src/main/kotlin/mayorSystem/monitoring/ui/AdminMonitoringMenu.kt
- Declarations: class AdminMonitoringMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/npc/MayorNpcIdentity.kt
- Declarations: data class MayorNpcIdentity
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/npc/MayorNpcService.kt
- Declarations: class MayorNpcService
- Non-private functions: forceUpdate, forceUpdateMayor, forceUpdateMayorForTerm, onDamage, onDisable, onEnable, onInteractAtEntity, onInteractEntity, onPluginEnable, onReload, openMayorCard, remove, setActive, spawnHere
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.npc.provider.MayorNpcProvider, mayorSystem.npc.provider.MayorNpcProviderFactory, mayorSystem.ui.menus.MainMenu, mayorSystem.ui.menus.MayorProfileMenu
- Uses plugin services: config, gui, hasShowcase, isEnabled, isReady, logger, messages, name, saveConfig, server, settings, showcase, store, termService

## src/main/kotlin/mayorSystem/npc/provider/CitizensMayorNpcProvider.kt
- Declarations: class CitizensMayorNpcProvider
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: config, isEnabled, logger, mayorNpc, messages, saveConfig, server, settings

## src/main/kotlin/mayorSystem/npc/provider/DisabledMayorNpcProvider.kt
- Declarations: class DisabledMayorNpcProvider
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: logger

## src/main/kotlin/mayorSystem/npc/provider/FancyNpcsMayorNpcProvider.kt
- Declarations: class FancyNpcsMayorNpcProvider
- Non-private functions: onJoin
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: config, EventExecutor, mayorNpc, messages, saveConfig, server, settings

## src/main/kotlin/mayorSystem/npc/provider/MayorNpcProvider.kt
- Declarations: interface MayorNpcProvider
- Non-private functions: isAvailable, isMayorNpc, onDisable, onEnable, remove, restoreFromConfig, spawnOrMove, updateMayor
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.npc.MayorNpcIdentity
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/npc/provider/MayorNpcProviderFactory.kt
- Declarations: object MayorNpcProviderFactory
- Non-private functions: byId, select
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, logger

## src/main/kotlin/mayorSystem/papi/MayorPlaceholderExpansion.kt
- Declarations: class MayorPlaceholderExpansion
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateEntry, mayorSystem.MayorPlugin
- Uses plugin services: isReady, pluginMeta, store, termService

## src/main/kotlin/mayorSystem/perks/PerkJoinListener.kt
- Declarations: class PerkJoinListener
- Non-private functions: onEffectRemoved, onJoin, onQuit, onRespawn
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: perks, settings

## src/main/kotlin/mayorSystem/perks/PerksCommands.kt
- Declarations: class PerksCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.data.RequestStatus, mayorSystem.perks.ui.AdminPerkCatalogMenu, mayorSystem.perks.ui.AdminPerkRefreshMenu, mayorSystem.perks.ui.AdminPerkRequestsMenu, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu, mayorSystem.security.Perms
- Uses plugin services: adminActions, config, gui, mainDispatcher, scope, store, termService

## src/main/kotlin/mayorSystem/perks/PerkService.kt
- Declarations: data class PerkDef, enum class PerkOrigin, class PerkService
- Non-private functions: applyActiveEffects, applyPerks, availablePerksForCandidate, cleanupPlayerCache, clearPerks, countSelectedInSection, displayNameFor, isActiveGlobalEffect, isPerkSectionAvailable, isSellAddonAvailable, isSkyblockStyleAddonAvailable, orderedSectionIds, perkSectionBlockReason, perksForSection, presetPerks, rebuildActiveEffectsForTerm, refreshAllOnlinePlayers, refreshPlayer, reloadFromConfig, resolveLore, resolveText, sectionEmptyReason, sectionIdForPerk, sectionLimitViolations, sectionPickLimit, skyblockStyleAddonName
- Direct mayorSystem imports: mayorSystem.api.events.MayorPerksAppliedEvent, mayorSystem.api.events.MayorPerksClearedEvent, mayorSystem.config.SystemGateOption, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.messaging.MayorBroadcasts
- Uses plugin services: config, isEnabled, logger, server, settings, store

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkCatalogMenu.kt
- Declarations: class AdminPerkCatalogMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, config, gui, perks

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkRefreshMenu.kt
- Declarations: class AdminPerkRefreshMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkRequestsMenu.kt
- Declarations: class AdminPerkRequestsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, gui, mainDispatcher, scope, server, store, termService

## src/main/kotlin/mayorSystem/perks/ui/AdminPerkSectionMenu.kt
- Declarations: class AdminPerkSectionMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: adminActions, config, gui, perks

## src/main/kotlin/mayorSystem/perks/ui/AdminPerksMenu.kt
- Declarations: class AdminPerksMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/perks/ui/AdminSettingsCustomRequestsMenu.kt
- Declarations: class AdminSettingsCustomRequestsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.CustomRequestCondition, mayorSystem.MayorPlugin, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/security/Perms.kt
- Declarations: object Perms
- Non-private functions: canOpenAdminPanel, isAdmin
- Direct mayorSystem imports: (none)
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/service/AdminActions.kt
- Declarations: class AdminActions
- Non-private functions: clearAllOverridesForTerm, clearApplyBan, clearForcedMayor, findCandidateByName, forceElectNowWithPerks, forceEndElectionNow, forceStartElectionNow, refreshPerksAll, refreshPerksPlayer, reload, resetElectionTerms, setApplyBanPermanent, setApplyBanTemp, setCandidateStatus, setFakeVoteAdjustment, setForcedMayor, setForcedMayorWithPerks, setPerkEnabled, setPerkSectionEnabled, setRequestStatus, togglePerk, togglePerkSection, updateConfig, updatePerkConfig, updateSettingsConfig
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.data.CandidateEntry, mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin
- Uses plugin services: audit, config, hasLeaderboardHologram, hasMayorNpc, hasMayorUsernamePrefix, hasTermService, leaderboardHologram, mainDispatcher, mayorNpc, mayorUsernamePrefix, perks, reloadEverything, reloadSettingsOnly, saveConfig, settings, store, termService

## src/main/kotlin/mayorSystem/service/ApplyFlowService.kt
- Declarations: class ApplyFlowService, data class Session
- Non-private functions: clear, get, getOrStart, onQuit, setSelected, start
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: (none)

## src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt
- Declarations: class MayorUsernamePrefixService
- Non-private functions: onDisable, onEnable, onJoin, onQuit, onReloadSettings, syncAllOnline, syncPlayer
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: hasTermService, isEnabled, isReady, logger, server, settings, store, termService

## src/main/kotlin/mayorSystem/service/OfflinePlayerCache.kt
- Declarations: class OfflinePlayerCache, data class Entry, data class Snapshot
- Non-private functions: refreshAsync, snapshot
- Direct mayorSystem imports: mayorSystem.MayorPlugin
- Uses plugin services: config, isEnabled, server

## src/main/kotlin/mayorSystem/service/SkinService.kt
- Declarations: class SkinService, data class SkinRecord, data class RefreshKey
- Non-private functions: applyToProfile, flush, get, isFresh, request
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, dataFolder, gui, scope, server

## src/main/kotlin/mayorSystem/showcase/ShowcaseService.kt
- Declarations: enum class ShowcaseMode, enum class ShowcaseTarget, class ShowcaseService
- Non-private functions: activeTarget, desiredTarget, electionOpenNow, mode, parse, sync
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin
- Uses plugin services: config, hasLeaderboardHologram, hasMayorNpc, hasTermService, leaderboardHologram, mayorNpc, saveConfig, settings, store, termService

## src/main/kotlin/mayorSystem/system/SystemCommands.kt
- Declarations: class SystemCommands
- Non-private functions: register
- Direct mayorSystem imports: mayorSystem.cloud.CommandContext, mayorSystem.config.SystemGateOption, mayorSystem.security.Perms, mayorSystem.showcase.ShowcaseMode, mayorSystem.showcase.ShowcaseTarget, mayorSystem.system.ui.AdminDisplayMenu, mayorSystem.system.ui.AdminMenu, mayorSystem.system.ui.AdminSettingsEnableOptionsMenu, mayorSystem.system.ui.AdminSettingsGeneralMenu, mayorSystem.system.ui.AdminSettingsMayorGroupMenu, mayorSystem.system.ui.AdminSettingsMenu, mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
- Uses plugin services: adminActions, gui, leaderboardHologram, mayorNpc, offlinePlayers, settings, showcase

## src/main/kotlin/mayorSystem/system/ui/AdminDisplayMenu.kt
- Declarations: class AdminDisplayMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.showcase.ShowcaseMode, mayorSystem.showcase.ShowcaseTarget, mayorSystem.ui.Menu
- Uses plugin services: adminActions, config, gui, leaderboardHologram, mayorNpc, showcase

## src/main/kotlin/mayorSystem/system/ui/AdminMenu.kt
- Declarations: class AdminMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminCandidatesMenu, mayorSystem.elections.ui.AdminElectionMenu, mayorSystem.maintenance.ui.AdminDebugMenu, mayorSystem.MayorPlugin, mayorSystem.monitoring.ui.AdminMonitoringMenu, mayorSystem.perks.ui.AdminPerksMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu, mayorSystem.ui.menus.MainMenu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsEnableOptionsMenu.kt
- Declarations: class AdminSettingsEnableOptionsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsGeneralMenu.kt
- Declarations: class AdminSettingsGeneralMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, messages, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsMayorGroupMenu.kt
- Declarations: class AdminSettingsMayorGroupMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, mayorUsernamePrefix, messages, settings

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsMenu.kt
- Declarations: class AdminSettingsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.candidates.ui.AdminSettingsApplyMenu, mayorSystem.elections.ui.AdminElectionSettingsMenu, mayorSystem.elections.ui.AdminSettingsTermMenu, mayorSystem.governance.ui.GovernanceSettingsMenu, mayorSystem.MayorPlugin, mayorSystem.messaging.ui.AdminBroadcastSettingsMenu, mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu, mayorSystem.perks.ui.AdminPerkCatalogMenu, mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu, mayorSystem.security.Perms, mayorSystem.ui.Menu
- Uses plugin services: gui

## src/main/kotlin/mayorSystem/system/ui/AdminSettingsPauseOptionsMenu.kt
- Declarations: class AdminSettingsPauseOptionsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: adminActions, gui, settings

## src/main/kotlin/mayorSystem/ui/GuiManager.kt
- Declarations: class GuiManager
- Non-private functions: onClick, onClose, open, openAnvilPrompt, reopenIfViewing, track
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.ui.menus.ApplyConfirmMenu, mayorSystem.ui.menus.ApplyPerksMenu, mayorSystem.ui.menus.ApplySectionsMenu
- Uses plugin services: applyFlow, isReady, logger, messages, server, settings

## src/main/kotlin/mayorSystem/ui/Menu.kt
- Declarations: data class Button, enum class UiClickSound
- Non-private functions: buttonAt, open
- Direct mayorSystem imports: mayorSystem.config.SystemGateOption, mayorSystem.MayorPlugin
- Uses plugin services: gui, messages, settings, skins

## src/main/kotlin/mayorSystem/ui/menus/ApplyConfirmMenu.kt
- Declarations: class ApplyConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: applyFlow, economy, gui, hasLeaderboardHologram, leaderboardHologram, logger, mainDispatcher, perks, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/ApplyPerksMenu.kt
- Declarations: class ApplyPerksMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu, mayorSystem.ui.UiClickSound
- Uses plugin services: applyFlow, gui, messages, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/ApplySectionsMenu.kt
- Declarations: class ApplySectionsMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: applyFlow, config, gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidateCustomPerksMenu.kt
- Declarations: class CandidateCustomPerksMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.CustomPerkRequest, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, mainDispatcher, prompts, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidateMenu.kt
- Declarations: class CandidateMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.data.RequestStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, prompts, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerkCatalogMenu.kt
- Declarations: class CandidatePerkCatalogMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerkSectionMenu.kt
- Declarations: class CandidatePerkSectionMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/CandidatePerksViewMenu.kt
- Declarations: class CandidatePerksViewMenu
- Non-private functions: escapeMm
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.perks.PerkDef, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, store

## src/main/kotlin/mayorSystem/ui/menus/MainMenu.kt
- Declarations: class MainMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.security.Perms, mayorSystem.system.ui.AdminMenu, mayorSystem.ui.Menu
- Uses plugin services: gui, server, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/MayorProfileMenu.kt
- Declarations: class MayorProfileMenu
- Non-private functions: escapeMm
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.perks.PerkDef, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, store

## src/main/kotlin/mayorSystem/ui/menus/MayorStepDownConfirmMenu.kt
- Declarations: class MayorStepDownConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.config.MayorStepdownPolicy, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, mainDispatcher, scope, store, termService

## src/main/kotlin/mayorSystem/ui/menus/ElectionRankingMenu.kt
- Declarations: class ElectionRankingMenu, enum class SortMode, data class RankedCandidate
- Non-private functions: titleFor
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, store

## src/main/kotlin/mayorSystem/ui/menus/StatusMenu.kt
- Declarations: class StatusMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: config, gui, perks, server, store, termService

## src/main/kotlin/mayorSystem/ui/menus/StepDownConfirmMenu.kt
- Declarations: class StepDownConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, mainDispatcher, messages, perks, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/VoteConfirmMenu.kt
- Declarations: class VoteConfirmMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, hasLeaderboardHologram, leaderboardHologram, mainDispatcher, scope, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/VoteMenu.kt
- Declarations: class VoteMenu
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.data.CandidateStatus, mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, settings, store, termService

## src/main/kotlin/mayorSystem/ui/menus/VotePerkSortMenu.kt
- Declarations: class VotePerkSortMenu, data class Entry
- Non-private functions: (none)
- Direct mayorSystem imports: mayorSystem.MayorPlugin, mayorSystem.ui.Menu
- Uses plugin services: gui, perks, termService

## src/main/kotlin/mayorSystem/util/PaperDispatchers.kt
- Declarations: class PaperMainDispatcher
- Non-private functions: (none)
- Direct mayorSystem imports: (none)
- Uses plugin services: Plugin

## Resources
- src/main/resources/config.yml
- src/main/resources/gui.yml
- src/main/resources/messages.yml
- src/main/resources/plugin.yml

