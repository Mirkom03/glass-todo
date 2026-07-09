package com.mirko.glasstodo.domain

/** Thrown by the remote layer; [status] lets the repository decide rollback vs keep-and-retry. */
class RemoteException(
    val status: Int,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: "HTTP $status", cause)

/**
 * A 4xx that is NOT an auth failure (401/403) is a permanent DATA failure (validation/constraint)
 * → roll back the optimistic write. 401/403 = re-auth (keep data), 5xx/IO = transient (keep + retry).
 */
fun Throwable.isPermanent(): Boolean {
    val status = (this as? RemoteException)?.status ?: return false
    return status in 400..499 && status != 401 && status != 403
}
