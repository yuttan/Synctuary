package io.synctuary.android.data

sealed class TransferState {
    object Idle : TransferState()

    data class Running(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long?,
        val startTimeMs: Long = System.currentTimeMillis(),
        val startBytes: Long = 0L,
    ) : TransferState() {
        val progressFraction: Float
            get() = if (totalBytes != null && totalBytes > 0)
                transferredBytes.toFloat() / totalBytes
            else -1f

        val speedBytesPerSec: Long
            get() {
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (elapsed <= 0) return 0L
                return ((transferredBytes - startBytes) * 1000L) / elapsed
            }

        val etaSeconds: Long?
            get() {
                val speed = speedBytesPerSec
                if (speed <= 0 || totalBytes == null) return null
                val remaining = totalBytes - transferredBytes
                return if (remaining <= 0) 0L else remaining / speed
            }
    }

    data class Done(val fileName: String, val location: String) : TransferState()
    data class Failed(val fileName: String, val message: String) : TransferState()
}
