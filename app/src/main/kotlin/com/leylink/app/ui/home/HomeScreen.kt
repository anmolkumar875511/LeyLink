package com.leylink.app.ui.home

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leylink.app.R
import com.leylink.app.permissions.PermissionPolicy
import com.leylink.app.ui.theme.LeyLinkTheme
import com.leylink.core.model.PacketType
import com.leylink.core.model.StoredMessage
import com.leylink.core.router.RouterStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stateful entry point: wires the ViewModel and the permission launcher to the stateless screen. */
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    // Permissions or Bluetooth may have changed while we were in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    HomeScreen(
        state = state,
        onGrantClick = {
            permissionLauncher.launch(PermissionPolicy.requestAll(Build.VERSION.SDK_INT).toTypedArray())
        },
        onToggleMesh = viewModel::onToggleMesh,
        onDraftChange = viewModel::onDraftChange,
        onSendChat = { viewModel.onSend(PacketType.CHAT) },
        onSendSos = { viewModel.onSend(PacketType.SOS) },
    )
}

/**
 * Stateless: renders [state], reports events. Easy to preview and to test.
 *
 * Responsive: below 640 dp wide everything lives in one scrolling column with the composer pinned at the
 * bottom; from 640 dp (tablets, foldables, phones in landscape) it becomes two panes: the sigil and the
 * controls on the left, the messages and the composer on the right.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onGrantClick: () -> Unit,
    onToggleMesh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendSos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant),
                    ),
                )
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
                if (maxWidth >= WIDE_BREAKPOINT) {
                    WidePane(state, onGrantClick, onToggleMesh, onDraftChange, onSendChat, onSendSos)
                } else {
                    CompactPane(state, maxWidth, onGrantClick, onToggleMesh, onDraftChange, onSendChat, onSendSos)
                }
            }
        }
    }
}

@Composable
private fun CompactPane(
    state: HomeUiState,
    maxWidth: Dp,
    onGrantClick: () -> Unit,
    onToggleMesh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendSos: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // Everything except the composer scrolls, so short screens and big fonts never hide the message box.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Hero(state, minOf(maxWidth * 0.62f, SIGIL_MAX), onGrantClick, onToggleMesh)
            }
            item { Rule() }
            items(state.messages, key = { it.msgId.toString() }) { MessageItem(it) }
        }
        Composer(state, onDraftChange, onSendChat, onSendSos, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun WidePane(
    state: HomeUiState,
    onGrantClick: () -> Unit,
    onToggleMesh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendSos: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Hero(state, SIGIL_MAX, onGrantClick, onToggleMesh)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(0.58f).fillMaxHeight()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.messages, key = { it.msgId.toString() }) { MessageItem(it) }
            }
            Composer(state, onDraftChange, onSendChat, onSendSos, Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        }
    }
}

/** Title, sigil, status lines, the two main buttons and the (collapsible) stats. */
@Composable
private fun Hero(state: HomeUiState, sigilSize: Dp, onGrantClick: () -> Unit, onToggleMesh: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.title).substringAfter("· "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.height(20.dp))
        Sigil(state.peerCount, state.meshRunning, sigilSize)
        Box(Modifier.height(20.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(stringResource(R.string.label_bluetooth), onOff(state.bluetoothEnabled), state.bluetoothEnabled)
            StatusLine(
                stringResource(R.string.label_permissions),
                grantedMissing(state.permissionsGranted),
                state.permissionsGranted,
            )
            StatusLine(stringResource(R.string.label_mesh), runningStopped(state.meshRunning), state.meshRunning)
        }
        if (!state.hasBle) {
            Box(Modifier.height(10.dp))
            Text(
                stringResource(R.string.error_no_ble),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onGrantClick,
                enabled = !state.permissionsGranted,
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.btn_grant), textAlign = TextAlign.Center)
            }
            Button(onClick = onToggleMesh, enabled = state.canStartMesh, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.meshRunning) R.string.btn_mode_off else R.string.btn_mode_on),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Box(Modifier.height(16.dp))
        StatsPanel(state.stats)
    }
}

/**
 * The leyline sigil: three thin rings, one node per neighbour on the middle ring, a slow rotation and,
 * while Disaster Mode runs, a pulse that radiates outward. The number in the middle is the peer count.
 */
@Composable
private fun Sigil(peerCount: Int, running: Boolean, diameter: Dp, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val glow = MaterialTheme.colorScheme.primary
    val node = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "sigil")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            val hair = Stroke(width = 1.dp.toPx())
            drawCircle(ring, r * 0.98f, c, style = hair)
            drawCircle(ring.copy(alpha = 0.7f), r * 0.72f, c, style = hair)
            drawCircle(ring.copy(alpha = 0.4f), r * 0.46f, c, style = hair)
            if (running) {
                drawCircle(
                    color = glow.copy(alpha = (1f - pulse) * 0.55f),
                    radius = r * (0.3f + 0.68f * pulse),
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            val count = peerCount.coerceIn(0, MAX_NODES)
            for (i in 0 until count) {
                val a = (turn + i * 360f / count) * (PI / 180.0)
                val p = Offset(c.x + (cos(a) * r * 0.72f).toFloat(), c.y + (sin(a) * r * 0.72f).toFloat())
                drawLine(node.copy(alpha = 0.35f), c, p, strokeWidth = 1.dp.toPx())
                drawCircle(node.copy(alpha = 0.25f), 8.dp.toPx(), p)
                drawCircle(node, 3.5.dp.toPx(), p)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = peerCount.toString(),
                fontFamily = FontFamily.Serif,
                fontSize = (diameter.value * 0.2f).sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.label_peers).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = diameter * 0.5f),
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, active: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline),
        )
        Box(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Collapsed by default to keep the screen calm; a tap on the title opens the counters. */
@Composable
private fun StatsPanel(stats: RouterStats) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.stats_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "−" else "+", color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            val small = MaterialTheme.typography.bodySmall
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.stats_traffic, stats.delivered, stats.relaysQueued, stats.duplicates),
                    style = small, color = tint,
                )
                Text(
                    stringResource(R.string.stats_security, stats.announcesIn, stats.signatureRejected, stats.sosDropped),
                    style = small, color = tint,
                )
                Text(
                    text = if (stats.unackedSos > 0) {
                        stringResource(R.string.stats_beacon_on, stats.unackedSos)
                    } else {
                        stringResource(R.string.stats_beacon_off)
                    },
                    style = small,
                    color = if (stats.unackedSos > 0) MaterialTheme.colorScheme.error else tint,
                )
                Text(
                    stringResource(R.string.stats_bulk, stats.bulkSent, stats.bulkReceived, stats.bulkFallbacks),
                    style = small, color = tint,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Rule() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Composer(
    state: HomeUiState,
    onDraftChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendSos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.hint_message)) },
            supportingText = {
                Text(state.error ?: stringResource(R.string.sos_chars_left, state.sosCharsLeft))
            },
            isError = state.error != null,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSendChat,
                enabled = state.canSend,
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            ) {
                Text(stringResource(R.string.btn_send_chat))
            }
            Button(
                onClick = onSendSos,
                enabled = state.canSend,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_send_sos))
            }
        }
    }
}

/** No boxes: a thin coloured thread on the left (gold for chat, ember for SOS) and serif text. */
@Composable
private fun MessageItem(message: StoredMessage) {
    val isSos = message.type == PacketType.SOS
    val thread = if (isSos) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(thread))
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (isSos) "SOS" else "Chat",
                style = MaterialTheme.typography.labelMedium,
                color = thread,
            )
            Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = describe(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sender, hop telemetry and the sender's timestamp (README: Multi-Hop SOS requirement). */
private fun describe(message: StoredMessage): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestampMs))
    return if (message.mine) {
        "You · sent $time"
    } else {
        val hops = if (message.hop == 1) "1 hop" else "${message.hop} hops"
        "From ${message.originId.toString().take(ID_CHARS)} · $hops · $time"
    }
}

@Composable
private fun onOff(value: Boolean) = stringResource(if (value) R.string.state_on else R.string.state_off)

@Composable
private fun grantedMissing(value: Boolean) =
    stringResource(if (value) R.string.state_granted else R.string.state_missing)

@Composable
private fun runningStopped(value: Boolean) =
    stringResource(if (value) R.string.state_running else R.string.state_stopped)

private const val ID_CHARS = 6
private const val MAX_NODES = 8
private const val PULSE_MS = 3_600
private const val TURN_MS = 60_000
private val WIDE_BREAKPOINT = 640.dp
private val MAX_CONTENT_WIDTH = 1_100.dp
private val SIGIL_MAX = 240.dp

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun HomeScreenPreview() {
    LeyLinkTheme {
        HomeScreen(
            state = HomeUiState(bluetoothEnabled = true, permissionsGranted = true, meshRunning = true, peerCount = 3),
            onGrantClick = {},
            onToggleMesh = {},
            onDraftChange = {},
            onSendChat = {},
            onSendSos = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 560)
@Composable
private fun HomeScreenWidePreview() {
    LeyLinkTheme {
        HomeScreen(
            state = HomeUiState(bluetoothEnabled = true, permissionsGranted = true, meshRunning = true, peerCount = 5),
            onGrantClick = {},
            onToggleMesh = {},
            onDraftChange = {},
            onSendChat = {},
            onSendSos = {},
        )
    }
}
