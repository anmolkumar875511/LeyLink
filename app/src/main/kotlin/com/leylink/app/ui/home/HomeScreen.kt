package com.leylink.app.ui.home

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leylink.app.R
import com.leylink.app.permissions.PermissionPolicy
import com.leylink.app.ui.theme.LeyLinkTheme

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
    )
}

/** Stateless: renders [state], reports clicks. Easy to preview and to test. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onGrantClick: () -> Unit,
    onToggleMesh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title),
                style = MaterialTheme.typography.headlineSmall,
            )
            StatusRow(
                label = stringResource(R.string.label_ble),
                value = stringResource(if (state.hasBle) R.string.yes else R.string.no),
            )
            StatusRow(
                label = stringResource(R.string.label_bluetooth),
                value = stringResource(if (state.bluetoothEnabled) R.string.state_on else R.string.state_off),
            )
            StatusRow(
                label = stringResource(R.string.label_permissions),
                value = stringResource(
                    if (state.permissionsGranted) R.string.state_granted else R.string.state_missing,
                ),
            )
            StatusRow(
                label = stringResource(R.string.label_mesh),
                value = stringResource(if (state.meshRunning) R.string.state_running else R.string.state_stopped),
            )
            Text(
                text = stringResource(R.string.hint_bluetooth),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onGrantClick,
                enabled = !state.permissionsGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_grant))
            }
            Button(
                onClick = onToggleMesh,
                enabled = state.canStartMesh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.meshRunning) R.string.btn_mode_off else R.string.btn_mode_on))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    LeyLinkTheme {
        HomeScreen(
            state = HomeUiState(hasBle = true, bluetoothEnabled = true, permissionsGranted = true),
            onGrantClick = {},
            onToggleMesh = {},
        )
    }
}
