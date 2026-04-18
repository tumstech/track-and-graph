/*
 *  This file is part of Track & Graph
 *
 *  Track & Graph is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Track & Graph is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Track & Graph.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.samco.trackandgraph.thingsboard

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.time.Duration.Companion.minutes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.samco.trackandgraph.R
import com.samco.trackandgraph.ui.compose.appbar.AppBarConfig
import com.samco.trackandgraph.ui.compose.appbar.LocalTopBarController
import com.samco.trackandgraph.ui.compose.ui.TextButton
import com.samco.trackandgraph.ui.compose.ui.cardPadding
import com.samco.trackandgraph.ui.compose.ui.inputSpacingLarge
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.DurationUnit

@Serializable
data object ThingsboardSettingsNavKey : NavKey

@Composable
fun ThingsboardSettingsScreen(navArgs: ThingsboardSettingsNavKey) {
    val viewModel: ThingsboardSettingsViewModel = hiltViewModel<ThingsboardSettingsViewModelImpl>()

    TopAppBarContent(navArgs)

    ThingsboardSettingsContent(viewModel = viewModel)
}

@Composable
private fun TopAppBarContent(navArgs: ThingsboardSettingsNavKey) {
    val topBarController = LocalTopBarController.current
    val title = stringResource(R.string.thingsboard_settings)

    topBarController.Set(
        navArgs,
        AppBarConfig(
            title = title,
            appBarPinned = true,
        )
    )
}

@Composable
private fun ThingsboardSettingsContent(viewModel: ThingsboardSettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe state from viewModel
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiToken by viewModel.apiToken.collectAsStateWithLifecycle()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsStateWithLifecycle()
    val autoSyncInterval by viewModel.autoSyncInterval.collectAsStateWithLifecycle()

    // Handle error toasts
    LaunchedEffect(context) {
        viewModel.errorEvent.collect { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    .asPaddingValues()
            )
    ) {
        Spacer(modifier = Modifier.height(inputSpacingLarge))

        ConnectorConfigCard(
            baseUrl = baseUrl,
            onBaseUrlChange = viewModel::setBaseUrl,
            apiToken = apiToken,
            onApiTokenChange = viewModel::setApiToken,
            onSave = {
                scope.launch {
                    viewModel.saveConnectorConfig()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(inputSpacingLarge))

        AutoSyncConfigCard(
            enabled = autoSyncEnabled,
            onEnabledChange = viewModel::setAutoSyncEnabled,
            interval = autoSyncInterval,
            onIntervalChange = viewModel::setAutoSyncInterval,
            onSave = {
                scope.launch {
                    viewModel.saveAutoSyncConfig()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(inputSpacingLarge))
    }
}

@Composable
private fun ColumnScope.ConnectorConfigCard(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(cardPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(inputSpacingLarge)
        ) {
            Text(
                text = stringResource(R.string.thingsboard_connector_config),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(stringResource(R.string.thingsboard_base_url)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )

            OutlinedTextField(
                value = apiToken,
                onValueChange = onApiTokenChange,
                label = { Text(stringResource(R.string.thingsboard_api_token)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            TextButton(
                onClick = onSave,
                text = stringResource(R.string.save)
            )
        }
    }
}

@Composable
private fun ColumnScope.AutoSyncConfigCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    interval: Duration,
    onIntervalChange: (Duration) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(cardPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(inputSpacingLarge)
        ) {
            Text(
                text = stringResource(R.string.thingsboard_auto_sync),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
                Text(
                    text = stringResource(R.string.enable_auto_sync),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (enabled) {
                OutlinedTextField(
                    value = interval.inWholeMinutes.toString(),
                    onValueChange = { newValue ->
                        newValue.toLongOrNull()?.let { minutes ->
                            onIntervalChange(minutes.minutes)
                        }
                    },
                    label = { Text(stringResource(R.string.thingsboard_sync_interval_minutes)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                TextButton(
                    onClick = onSave,
                    text = stringResource(R.string.save)
                )
            }
        }
    }
}
