package com.antitheft.guardian.location

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resumeWith(Result.success(it)) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
        addOnCanceledListener { cont.cancel() }
    }
