package io.github.hassanjaveed84.aatube.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.hassanjaveed84.aatube.R
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.AppState
import io.github.hassanjaveed84.aatube.core.PlayerCommand
import io.github.hassanjaveed84.aatube.core.PlayerMode
import io.github.hassanjaveed84.aatube.core.PlayerStatus
import io.github.hassanjaveed84.aatube.core.StatusText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The car screen: the player fills the surface; the action strip carries
 * play/pause, mode, back (YouTube TV) and reload. Buttons in the action strip
 * are reachable by touch and by a rotary controller, as the host exposes them.
 * Pan is offered so drags reach the surface (as arrow keys in YouTube TV).
 */
class PlayerScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private data class StripState(val playing: Boolean, val mode: PlayerMode)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        lifecycleScope.launch {
            AppHub.state
                .map { StripState(it.player == PlayerStatus.Playing || it.player == PlayerStatus.Buffering, it.mode) }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val state = AppHub.state.value
        val playing = state.player == PlayerStatus.Playing || state.player == PlayerStatus.Buffering
        val strip = ActionStrip.Builder()
            .addAction(
                action(if (playing) R.drawable.ic_pause else R.drawable.ic_play) {
                    val refusal = AppHub.command(if (playing) PlayerCommand.PAUSE else PlayerCommand.PLAY)
                    if (refusal != null) CarToast.makeText(carContext, refusal, CarToast.LENGTH_LONG).show()
                },
            )
            .addAction(action(R.drawable.ic_mode) { cycleMode(state) })
            .addAction(
                if (state.mode == PlayerMode.YOUTUBE_TV) {
                    action(R.drawable.ic_back) { AppHub.command(PlayerCommand.BACK) }
                } else {
                    action(R.drawable.ic_reload) { AppHub.command(PlayerCommand.RELOAD) }
                },
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }
            .build()
    }

    private fun cycleMode(state: AppState) {
        val next = when (state.mode) {
            PlayerMode.DIRECT -> PlayerMode.YOUTUBE_TV
            PlayerMode.YOUTUBE_TV -> PlayerMode.TEST_PATTERN
            PlayerMode.TEST_PATTERN -> PlayerMode.DIRECT
        }
        AppHub.dispatch(AppEvent.ModeSelected(next))
        val label = when (next) {
            PlayerMode.DIRECT -> "Direct video"
            PlayerMode.YOUTUBE_TV -> "YouTube TV"
            PlayerMode.TEST_PATTERN -> "Test pattern"
        }
        CarToast.makeText(carContext, "$label · ${StatusText.gate(AppHub.state.value.gate)}", CarToast.LENGTH_SHORT).show()
    }

    private fun action(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
