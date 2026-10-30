package com.anchorreader.supertonic

sealed interface VoicePackState {
    data object NotInstalled : VoicePackState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : VoicePackState
    data class Installed(val bytesOnDisk: Long) : VoicePackState
    data class Failed(val message: String) : VoicePackState
}
