package com.narada.android.transfers

import com.narada.android.connection.connectionMessage

fun transferMessage(code: String): String = when (code) {
    "EXACT_COPY_REUSED" -> "This exact version is already in your downloads. Use its existing progress or saved copy; paused work stays paused. No extra slot was used."
    "TRANSFER_BUSY" -> "This transfer is already running or waiting for a brief retry. Choose Pause transfer to stop it."
    "PEER_TIMEOUT", "PEER_UNREACHABLE", "CONNECTION_INTERRUPTED", "RATE_LIMITED" -> "The connection was interrupted or busy. Saved progress is kept. While this run remains open, at most two short retries use the same transfer. If paused, check Wi-Fi and the saved device, then resume; do not pair again."
    "PIN_REFERENCED" -> "A pin still references this version or candidate. Explicitly release that reference in Offline files before removing its private bytes."
    "SYNC_ACCESS_DENIED" -> "This pinned copy requires current Read + SyncRead authority, including on resume. Previously verified copies remain available locally."
    "EXPORT_HISTORY_FULL" -> "Two export records are retained. Explicitly remove a reviewed local record before choosing another destination."
    "FOREGROUND_PAUSED", "USER_PAUSED", "TRANSFER_STOPPED", "RESTART_PAUSED", "EXPORT_REVIEW" -> "Progress is kept, not automatically resumed. Keep NARADA visible, then choose Resume exact transfer. After restart, saved chunks are decrypted and rehashed before being counted."
    "TRANSFER_ACCESS_CHANGED", "TRANSFER_SCOPE_CHANGED", "ACCESS_DENIED", "FILE_ACCESS_CHANGED", "PAIRING_STATE_CHANGED", "DEVICE_REVOKED" -> "Current access or the original pairing changed. No new bytes are accepted under replacement trust. Already saved copies are not remotely erased. Review desktop grants, then resume explicitly."
    "TRANSFERS_UNSUPPORTED" -> "This desktop does not advertise the supported transfer protocol. No fallback, owner API or reverse connection was used."
    "TRANSFER_LIMIT", "TRANSFER_UNSUPPORTED" -> "Select one ordinary visible file, at most 64 MiB. Recursive directory downloads and filesystem uploads are not supported here. Encrypted chat attachments use their separate 8 MiB workflow."
    "TRANSFER_LOCAL_QUOTA" -> "The four-record / 128 MiB private retention limit is full. Explicitly remove a reviewed private copy to free space; unknown exports and saved data are never silently evicted."
    "TRANSFER_QUOTA", "TRANSFER_REMOTE_FULL" -> "Desktop snapshot storage is full. Its owner must review retained sources/receipts; the phone cannot purge them."
    "LOCAL_STORAGE_FULL" -> "Phone storage is low. Partial private fragments remain; nothing is reported complete or silently deleted. Free space outside this copy, then explicitly resume."
    "LOCAL_STORAGE_UNAVAILABLE", "LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING", "HASH_MISMATCH", "MANIFEST_MISMATCH", "INVALID_MANIFEST", "INVALID_TRANSFER_RESPONSE" -> "The bytes, protected manifest or private storage could not be verified. No saved/exported success was invented and no other version was substituted. Preserve app data and review the record."
    "SOURCE_CHANGED", "STALE_VERSION", "TRANSFER_EXPIRED", "SOURCE_PREPARATION_INTERRUPTED", "TRANSFER_NOT_READY", "TRANSFER_NOT_FOUND" -> "The exact desktop snapshot is changed, expired or unavailable. Resume will not select another version or generate a replacement transfer ID. Review the old record before choosing a current file as a new download."
    "TRANSFER_REMOTE_STORAGE", "FILE_IO", "FILE_WORK_FAILED" -> "Desktop storage needs attention. The phone's protected identity was not reset."
    "PROVIDER_PERMISSION_LOST", "PROVIDER_READ_ONLY", "INVALID_EXPORT_TARGET" -> "Choose a writable document-provider folder with temporary read and write access. Retained read-only Storage grants are separate. No URI is converted into a raw path."
    "EXPORT_NAME_COLLISION", "PROVIDER_REUSED_DOCUMENT", "PROVIDER_NOT_EMPTY" -> "The provider target is not a confirmed new empty document, or the name already exists. NARADA will not truncate or replace it. Choose another name after reviewing the provider."
    "PROVIDER_DIRECTORY_INCOMPLETE" -> "The provider did not return a complete folder listing. No document was requested."
    "PROVIDER_NAME_CHANGED" -> "The provider changed the requested name. An empty document may remain; no existing document was overwritten."
    "PROVIDER_DIRECTORY_LIMIT" -> "Choose a folder with at most 256 immediate children. A complete bounded collision/identity check is required before creating a document."
    "PROVIDER_TIMEOUT", "PROVIDER_BUSY", "PROVIDER_UNAVAILABLE", "PROVIDER_CREATE_UNCONFIRMED", "EXPORT_INTERRUPTED", "EXPORT_VERIFICATION_FAILED" -> "The provider did not confirm a complete readback. A new empty, partial or full document may exist. The private copy is separate. Inspect the destination; no automatic rewrite/delete is attempted. A stuck provider worker remains quarantined."
    "EXPORT_UNRESOLVED" -> "Review the unfinished export before another copy or removal. Acknowledging local uncertainty does not cancel or delete a provider document."
    "TRANSFER_NOT_VERIFIED", "TRANSFER_ALREADY_VERIFIED" -> "Export requires a full private receipt and a fresh integrity check. A saved copy is removed only through explicit private-data removal."
    "EXPORT_PICKER_CANCELLED" -> "The folder picker was cancelled; this attempt did not create a document."
    else -> connectionMessage(code)
}
