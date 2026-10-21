package io.github.hassanjaveed84.aatube.phone

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hassanjaveed84.aatube.BuildConfig
import io.github.hassanjaveed84.aatube.R
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.AppState
import io.github.hassanjaveed84.aatube.core.PlayerCommand
import io.github.hassanjaveed84.aatube.core.PlayerMode
import io.github.hassanjaveed84.aatube.core.StatusText
import io.github.hassanjaveed84.aatube.core.YouTubeUrl
import io.github.hassanjaveed84.aatube.lab.LabLog
import io.github.hassanjaveed84.aatube.motion.SpeedMonitor
import kotlinx.coroutines.launch

/**
 * Phone-side setup and remote control: permission status, Android Auto
 * status, a YouTube link field (also filled from the share sheet), mode and
 * playback controls. In the lab build it can export the session log.
 */
class MainActivity : ComponentActivity() {

    private lateinit var urlField: EditText
    private lateinit var statusText: TextView
    private lateinit var permissionText: TextView
    private lateinit var modeGroup: RadioGroup

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        AppHub.record("permission", "phone request result $result")
        renderPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlField = findViewById(R.id.url)
        statusText = findViewById(R.id.status)
        permissionText = findViewById(R.id.permissions)
        modeGroup = findViewById(R.id.mode)

        findViewById<Button>(R.id.send).setOnClickListener { send(urlField.text.toString()) }
        findViewById<Button>(R.id.paste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.play).setOnClickListener { command(PlayerCommand.PLAY) }
        findViewById<Button>(R.id.pause).setOnClickListener { command(PlayerCommand.PAUSE) }
        findViewById<Button>(R.id.stop).setOnClickListener { AppHub.dispatch(AppEvent.StopRequested) }
        findViewById<Button>(R.id.grant).setOnClickListener { requestPermissions() }
        modeGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.mode_tv -> PlayerMode.YOUTUBE_TV
                R.id.mode_test -> PlayerMode.TEST_PATTERN
                else -> PlayerMode.DIRECT
            }
            if (AppHub.state.value.mode != mode) AppHub.dispatch(AppEvent.ModeSelected(mode))
        }

        val export = findViewById<Button>(R.id.export_log)
        export.visibility = if (BuildConfig.LAB) View.VISIBLE else View.GONE
        export.setOnClickListener { exportLog() }
        findViewById<TextView>(R.id.build_label).text =
            getString(if (BuildConfig.LAB) R.string.build_label_lab else R.string.build_label_daily, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppHub.state.collect(::render)
            }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        urlField.setText(text)
        send(text)
    }

    private fun send(text: String) {
        val video = YouTubeUrl.parse(text)
        if (video == null) {
            toast(getString(R.string.not_a_youtube_link))
            return
        }
        AppHub.dispatch(AppEvent.VideoRequested(video))
        val state = AppHub.state.value
        toast(
            when {
                !state.carConnected -> getString(R.string.queued_not_connected)
                !state.videoAllowed -> StatusText.gate(state.gate)
                else -> getString(R.string.sent_to_car)
            },
        )
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) toast(getString(R.string.clipboard_empty)) else urlField.setText(text)
    }

    private fun command(command: PlayerCommand) {
        AppHub.command(command)?.let(::toast)
    }

    private fun render(state: AppState) {
        statusText.text = getString(
            R.string.status_template,
            StatusText.connection(state),
            StatusText.gate(state.gate),
            modeLabel(state.mode),
            StatusText.player(state.player),
        )
        val checked = when (state.mode) {
            PlayerMode.DIRECT -> R.id.mode_direct
            PlayerMode.YOUTUBE_TV -> R.id.mode_tv
            PlayerMode.TEST_PATTERN -> R.id.mode_test
        }
        if (modeGroup.checkedRadioButtonId != checked) modeGroup.check(checked)
    }

    private fun modeLabel(mode: PlayerMode): String = getString(
        when (mode) {
            PlayerMode.DIRECT -> R.string.mode_direct
            PlayerMode.YOUTUBE_TV -> R.string.mode_tv
            PlayerMode.TEST_PATTERN -> R.string.mode_test
        },
    )

    private fun renderPermissions() {
        fun line(label: Int, permission: String) =
            getString(label) + ": " + getString(if (granted(permission)) R.string.granted else R.string.missing)
        permissionText.text = listOf(
            line(R.string.perm_car_speed, SpeedMonitor.CAR_SPEED_PERMISSION),
            line(R.string.perm_location, Manifest.permission.ACCESS_FINE_LOCATION),
            line(R.string.perm_notifications, Manifest.permission.POST_NOTIFICATIONS),
        ).joinToString("\n")
    }

    private fun requestPermissions() {
        permissionRequest.launch(
            arrayOf(
                SpeedMonitor.CAR_SPEED_PERMISSION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    private fun exportLog() {
        val file = LabLog.latest(this)
        if (file == null) {
            toast(getString(R.string.no_lab_log))
            return
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.export_log)))
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
