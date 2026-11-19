package com.underrated.docreader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Asked for when an engine reports a file as encrypted and is able to decrypt it
 * given the password. The platform renderer never gets here — it has no way to accept
 * one — so this is PDFium-only.
 */
@Composable
fun PasswordDialog(
    fileName: String?,
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    if (wrongPassword) {
                        "That password did not work. Try again."
                    } else {
                        fileName?.let { "“$it” is encrypted." } ?: "This file is encrypted."
                    },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty(),
            ) { Text("Open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
