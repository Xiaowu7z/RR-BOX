package com.rr.client.vpn

import kotlinx.coroutines.CancellationException

/**
 * Keeps a downloaded rules generation uncommitted until its data plane has started.
 * The caller owns the engine mutex and commits on the same dispatcher as stop/switch requests.
 * This tests local activation, never whether every remote application is reachable.
 */
internal object RuleUpdateActivationRunner {
    sealed interface Result {
        data object Activated : Result
        data class Restored(val candidateFailure: Throwable) : Result
        data class Failed(val failure: Throwable) : Result
        data object Superseded : Result
    }

    suspend fun activate(
        isCurrent: () -> Boolean,
        startCandidate: suspend () -> Unit,
        commitCandidate: suspend () -> Boolean,
        stopCandidate: suspend () -> Unit,
        restorePrevious: suspend () -> Unit
    ): Result {
        if (!isCurrent()) return Result.Superseded
        val candidateFailure = try {
            startCandidate()
            if (!isCurrent()) {
                stopCandidate()
                return Result.Superseded
            }
            check(commitCandidate()) { "规则版本已变化，未提交本次更新" }
            return Result.Activated
        } catch (cancelled: CancellationException) {
            // The engine owner performs non-cancellable teardown before releasing its mutex.
            throw cancelled
        } catch (failure: Throwable) {
            failure
        }

        try {
            stopCandidate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cleanup: Throwable) {
            return Result.Failed(IllegalStateException(
                "${candidateFailure.message}\n候选数据面清理未确认：${cleanup.message}", cleanup
            ))
        }
        if (!isCurrent()) return Result.Superseded

        return try {
            restorePrevious()
            if (isCurrent()) Result.Restored(candidateFailure) else {
                stopCandidate()
                Result.Superseded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (restore: Throwable) {
            Result.Failed(IllegalStateException(
                "${candidateFailure.message}\n恢复原规则失败：${restore.message}", restore
            ))
        }
    }
}
