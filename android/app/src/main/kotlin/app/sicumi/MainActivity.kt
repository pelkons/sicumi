package app.sicumi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.sicumi.dictation.DictationSetupScreen
import app.sicumi.meeting.MeetingScreen
import app.sicumi.meetings.AudioImporter
import app.sicumi.meetings.MeetingRepository
import app.sicumi.meetings.MeetingTitles
import app.sicumi.pipeline.MeetingProcessor
import app.sicumi.recording.RecorderService
import app.sicumi.recording.RecorderState
import app.sicumi.recording.RecordingScreen
import app.sicumi.settings.SettingsScreen
import app.sicumi.ui.home.HomeScreen
import app.sicumi.ui.theme.SicumiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Действие из внешнего Intent (уведомление, «Поделиться»), которое UI выполнит один раз. */
    private sealed interface External {
        data object OpenRecording : External
        data class ImportAudio(val uri: Uri) : External
    }

    private val external = MutableStateFlow<External?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            MeetingProcessor.resumePending(this)
            handleIntent(intent)
        }
        setContent {
            SicumiTheme { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.getBooleanExtra(EXTRA_OPEN_RECORDING, false) -> external.value = External.OpenRecording
            intent.action == Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) external.value = External.ImportAudio(uri)
            }
        }
    }

    @Composable
    private fun App() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val repo = remember { MeetingRepository.get(context) }
        val meetings by repo.meetings.collectAsState()
        val recording by RecorderState.current.collectAsState()

        // Простой стек экранов: "home", "dictation", "settings", "recording", "meeting:<id>".
        var stack by rememberSaveable { mutableStateOf(arrayListOf(ROUTE_HOME)) }
        fun push(route: String) {
            stack = ArrayList(stack.filterNot { it == route } + route)
        }
        fun pop() {
            if (stack.size > 1) stack = ArrayList(stack.dropLast(1))
        }
        fun openMeeting(id: String) {
            stack = arrayListOf(ROUTE_HOME, "$ROUTE_MEETING$id")
        }

        var importing by remember { mutableStateOf(false) }
        fun importAudio(uri: Uri) {
            if (importing) return
            importing = true
            scope.launch {
                val meeting = AudioImporter.import(context, uri)
                importing = false
                if (meeting == null) {
                    Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show()
                } else {
                    MeetingProcessor.enqueue(context, meeting.id)
                    openMeeting(meeting.id)
                }
            }
        }

        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAudio(uri)
        }

        val notificationsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun startRecording() {
            RecorderService.start(context, MeetingTitles.default(context, System.currentTimeMillis()))
            push(ROUTE_RECORDING)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else Toast.makeText(context, R.string.recording_need_mic, Toast.LENGTH_LONG).show()
        }

        val pending by external.collectAsState()
        LaunchedEffect(pending) {
            when (val action = pending) {
                External.OpenRecording -> if (RecorderState.current.value != null) push(ROUTE_RECORDING)
                is External.ImportAudio -> importAudio(action.uri)
                null -> Unit
            }
            external.value = null
        }

        val route = stack.last()
        if (stack.size > 1) BackHandler { pop() }
        when {
            route == ROUTE_DICTATION -> DictationSetupScreen(onBack = ::pop)
            route == ROUTE_SETTINGS -> SettingsScreen(onBack = ::pop)
            route == ROUTE_RECORDING && recording != null -> RecordingScreen(onBack = ::pop)
            route.startsWith(ROUTE_MEETING) -> MeetingScreen(
                meetingId = route.removePrefix(ROUTE_MEETING),
                onBack = ::pop,
                onOpenSettings = { push(ROUTE_SETTINGS) },
            )
            else -> {
                // Сервис публикует состояние чуть позже старта; если записи так и нет — уходим с маршрута.
                if (route == ROUTE_RECORDING) {
                    LaunchedEffect(Unit) {
                        delay(1500)
                        if (RecorderState.current.value == null) pop()
                    }
                }
                HomeScreen(
                    meetings = meetings,
                    recording = recording,
                    importing = importing,
                    onStartRecording = {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onOpenRecording = { push(ROUTE_RECORDING) },
                    onImport = { pickAudio.launch(arrayOf("audio/*", "video/*")) },
                    onOpenMeeting = ::openMeeting,
                    onOpenDictation = { push(ROUTE_DICTATION) },
                    onOpenSettings = { push(ROUTE_SETTINGS) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_RECORDING = "open_recording"
        private const val ROUTE_HOME = "home"
        private const val ROUTE_DICTATION = "dictation"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_RECORDING = "recording"
        private const val ROUTE_MEETING = "meeting:"
    }
}
