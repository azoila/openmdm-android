package com.openmdm.agent.ui.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openmdm.agent.R
import com.openmdm.agent.ui.theme.OpenMDMAgentTheme

/**
 * Enrollment screen shown when the device is not yet enrolled.
 * Requires user to enter a device code to complete enrollment.
 */
@Composable
fun EnrollmentScreen(
    serverUrl: String,
    errorMessage: String?,
    isEnrolling: Boolean,
    onEnroll: (deviceCode: String, serverUrl: String) -> Unit,
    onScanQrCode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var deviceCode by remember { mutableStateOf("") }
    var editableServerUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 400.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Logo/Icon
                Icon(
                    imageVector = Icons.Default.PhonelinkSetup,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = stringResource(R.string.enrollment_title),
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = stringResource(R.string.enrollment_enter_code_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Server URL field
                OutlinedTextField(
                    value = editableServerUrl,
                    onValueChange = { editableServerUrl = it },
                    label = { Text(stringResource(R.string.enrollment_server_url)) },
                    placeholder = { Text(stringResource(R.string.enrollment_server_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isEnrolling,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    )
                )

                // Device Code field
                OutlinedTextField(
                    value = deviceCode,
                    onValueChange = { deviceCode = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                    label = { Text(stringResource(R.string.enrollment_device_code)) },
                    placeholder = { Text(stringResource(R.string.enrollment_device_code_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isEnrolling,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (deviceCode.isNotBlank() && editableServerUrl.isNotBlank() && !isEnrolling) {
                                onEnroll(deviceCode, editableServerUrl)
                            }
                        }
                    )
                )

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Enroll button
                Button(
                    onClick = { onEnroll(deviceCode, editableServerUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = deviceCode.isNotBlank() && editableServerUrl.isNotBlank() && !isEnrolling
                ) {
                    if (isEnrolling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (isEnrolling) R.string.enrollment_button_enrolling
                            else R.string.enrollment_button
                        )
                    )
                }

                // QR Code scan option (optional future feature)
                TextButton(
                    onClick = onScanQrCode,
                    enabled = !isEnrolling
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.enrollment_scan_qr))
                }
            }
        }
    }
}

/**
 * Loading screen shown while checking enrollment status or fetching policy.
 */
@Composable
fun LoadingScreen(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    val displayMessage = message ?: stringResource(R.string.launcher_loading)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = displayMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

// ============================================
// Preview Functions
// ============================================

@Preview(showBackground = true, name = "Enrollment Screen - Default")
@Composable
private fun EnrollmentScreenPreview() {
    OpenMDMAgentTheme {
        EnrollmentScreen(
            serverUrl = "https://mdm.example.com",
            errorMessage = null,
            isEnrolling = false,
            onEnroll = { _, _ -> },
            onScanQrCode = {}
        )
    }
}

@Preview(showBackground = true, name = "Enrollment Screen - Error")
@Composable
private fun EnrollmentScreenErrorPreview() {
    OpenMDMAgentTheme {
        EnrollmentScreen(
            serverUrl = "https://mdm.example.com",
            errorMessage = "Invalid device code. Please try again.",
            isEnrolling = false,
            onEnroll = { _, _ -> },
            onScanQrCode = {}
        )
    }
}

@Preview(showBackground = true, name = "Enrollment Screen - Loading")
@Composable
private fun EnrollmentScreenLoadingPreview() {
    OpenMDMAgentTheme {
        EnrollmentScreen(
            serverUrl = "https://mdm.example.com",
            errorMessage = null,
            isEnrolling = true,
            onEnroll = { _, _ -> },
            onScanQrCode = {}
        )
    }
}

@Preview(showBackground = true, name = "Loading Screen")
@Composable
private fun LoadingScreenPreview() {
    OpenMDMAgentTheme {
        LoadingScreen()
    }
}
