package com.example.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.model.AudioTrack
import com.example.repository.AudioRepository
import com.example.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AudioUiState {
    object Loading : AudioUiState()
    data class Success(val tracks: List<AudioTrack>) : AudioUiState()
    data class Error(val message: String) : AudioUiState()
    object PermissionRequired : AudioUiState()
}

data class PlaybackUiState(
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AudioRepository(application)

    private val _uiState = MutableStateFlow<AudioUiState>(AudioUiState.PermissionRequired)
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener(
            {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updateCurrentTrack(mediaItem)
                    }
                })
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun updateCurrentTrack(mediaItem: MediaItem?) {
        val currentTrackId = mediaItem?.mediaId?.toLongOrNull()
        if (currentTrackId != null) {
            val state = _uiState.value
            if (state is AudioUiState.Success) {
                val track = state.tracks.find { it.id == currentTrackId }
                _playbackState.value = _playbackState.value.copy(currentTrack = track)
            }
        } else {
            _playbackState.value = _playbackState.value.copy(currentTrack = null)
        }
    }

    fun playTrack(track: AudioTrack) {
        val controller = mediaController ?: return
        val state = _uiState.value
        if (state is AudioUiState.Success) {
            val mediaItems = state.tracks.map { t ->
                MediaItem.Builder()
                    .setMediaId(t.id.toString())
                    .setUri(Uri.parse(t.uri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.title)
                            .setArtist(t.artist)
                            .setAlbumTitle(t.album)
                            .build()
                    )
                    .build()
            }
            val startIndex = state.tracks.indexOf(track).takeIf { it >= 0 } ?: 0
            
            controller.setMediaItems(mediaItems, startIndex, 0)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun skipToNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        mediaController?.seekToPreviousMediaItem()
    }

    fun loadAudioTracks() {
        _uiState.value = AudioUiState.Loading
        viewModelScope.launch {
            try {
                val tracks = repository.getLocalAudioTracks()
                _uiState.value = AudioUiState.Success(tracks)
                // If we already have a controller and it's playing, ensure the UI is updated
                updateCurrentTrack(mediaController?.currentMediaItem)
            } catch (e: Exception) {
                _uiState.value = AudioUiState.Error(e.message ?: "Failed to load audio tracks")
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.value = AudioUiState.PermissionRequired
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
