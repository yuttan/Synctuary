package io.synctuary.android.data

sealed class TransferState {
    object Idle : TransferState()

    data class Running(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long?,
    ) : TransferState() {
        // -1f = indeterminate (total unknown); 0..1 = determinate
        val progressFraction: Float
            get() = if (totalBytes != null && totalBytes > 0)
                transferredBytes.toFloat() / totalBytes
            else -1f
    }

    data class Done(val fileName: String, val location: String) : TransferState()
    data class Failed(val fileName: String, val message: String) : TransferState()
}
