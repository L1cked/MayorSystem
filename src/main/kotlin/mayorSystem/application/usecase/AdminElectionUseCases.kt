package mayorSystem.application.usecase

import java.util.UUID

data class ActorRef(
    val uuid: UUID?,
    val name: String
)

sealed class UseCaseResult {
    data object Success : UseCaseResult()
    data object NoPermission : UseCaseResult()
    data object Busy : UseCaseResult()
    data class Rejected(val messageKey: String, val placeholders: Map<String, String> = emptyMap()) : UseCaseResult()
    data class Failure(val message: String? = null) : UseCaseResult()
}

interface StartElectionUseCase {
    suspend fun start(actor: ActorRef?): UseCaseResult
}

interface EndElectionUseCase {
    suspend fun end(actor: ActorRef?): UseCaseResult
}

interface ForceElectUseCase {
    suspend fun forceElect(actor: ActorRef?, term: Int, candidateId: UUID, candidateName: String, perkIds: Set<String>): UseCaseResult
    suspend fun clearForcedMayor(actor: ActorRef?, term: Int): UseCaseResult
}

interface ReloadMayorSystemUseCase {
    suspend fun reload(actor: ActorRef?): UseCaseResult
}

