package com.mirko.glasstodo.data

/** Supplies the current authenticated user id — fakeable in unit tests. */
interface AuthSource {
    suspend fun requireUid(): String
}
