package com.parth.index.firebase

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Await a Play Services / Firebase [Task] from a coroutine WITHOUT adding
 *  the kotlinx-coroutines-play-services dependency — the APK stays small
 *  and this one helper covers every call site. */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.resumeWithException(IllegalStateException("Task cancelled"))
    }
}
