package io.synctuary.android.data

sealed class TransferState {
    object Idle : TransferState()

    data class Running(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long?,
        val startTimeMs: Long = System.currentTimeMillis(),
        val startBytes: Long = 0L,
        // Position within a multi-file batch, 1-based. Both default to 1
        // so single-file transfers (and the download path) are unchanged;
        // the UI only shows the counter when batchTotal > 1.
        val batchIndex: Int = 1,
        val batchTotal: Int = 1,
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

    /**
     * Terminal state for a multi-file upload. Reported instead of
     * [Done]/[Failed] when more than one file was selected, so the UI can
     * summarize rather than surfacing only the last file's outcome. A
     * batch always runs to completion — a failed file does not abort the
     * rest — so both counts can be non-zero.
     */
    data class BatchDone(val succeeded: Int, val failed: Int) : TransferState()
}
