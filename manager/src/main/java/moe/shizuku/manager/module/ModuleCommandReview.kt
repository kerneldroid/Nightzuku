package moe.shizuku.manager.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.MonospaceLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

enum class ModuleCommandSource {
    WEB_UI,
    ACTION
}

data class ModuleCommandRequest(
    val module: AdbModule,
    val source: ModuleCommandSource,
    val command: String
)

fun interface ModuleCommandReviewer {
    fun confirmCommand(request: ModuleCommandRequest): Boolean
}

enum class AiCommandVerdict {
    DANGER,
    SAFE,
    UNKNOWN
}

data class AiCommandReview(
    val verdict: AiCommandVerdict,
    val message: String
)

object ModuleAiCommandAnalyzer {

    suspend fun analyze(command: String): AiCommandReview = withContext(Dispatchers.IO) {
        val apiKey = ModuleSettings.getAiApiKey()
        require(apiKey.isNotBlank()) { "Gemini API key is not configured." }
        val model = ModuleSettings.getAiModel().modelId
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") +
            ":generateContent"

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put(
                        "text",
                        """
                        Classify this Android shell command before execution.
                        Return exactly one first word: Danger, Safe, or Is unknown.
                        Then add one short sentence with the reason.

                        Command:
                        $command
                        """.trimIndent()
                    )
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("maxOutputTokens", 128)
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = (java.net.URL(endpoint).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            require(code in 200..299) { "Gemini API HTTP $code: ${response.take(240)}" }
            parseReviewText(
                JSONObject(response)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .optString("text")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReviewText(text: String): AiCommandReview {
        val clean = text.trim().ifBlank { "Is unknown: empty AI response." }
        val lower = clean.lowercase()
        val verdict = when {
            lower.startsWith("danger") -> AiCommandVerdict.DANGER
            lower.startsWith("safe") -> AiCommandVerdict.SAFE
            else -> AiCommandVerdict.UNKNOWN
        }
        return AiCommandReview(verdict, clean)
    }
}

@Composable
fun ReCommandDialog(
    request: ModuleCommandRequest,
    busy: Boolean = false,
    aiEnabled: Boolean = false,
    onAnalyze: suspend () -> AiCommandReview,
    onDismiss: () -> Unit,
    onReject: () -> Unit,
    onApprove: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(request.command) { mutableStateOf(request.command.length <= 420) }
    var aiResult by remember(request.command) { mutableStateOf<Result<AiCommandReview>?>(null) }
    var aiBusy by remember(request.command) { mutableStateOf(false) }
    val commandPreview = if (expanded || request.command.length <= 420) {
        request.command
    } else {
        request.command.take(420) + "\n..."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modules_recommand_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.modules_recommand_source,
                        request.module.name,
                        when (request.source) {
                            ModuleCommandSource.WEB_UI -> "WebUI"
                            ModuleCommandSource.ACTION -> "Action"
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MonospaceLog(commandPreview)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                stringResource(R.string.modules_recommand_collapse)
                            } else {
                                stringResource(R.string.modules_recommand_expand)
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.modules_recommand_command_clip_label),
                                    request.command
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.modules_recommand_copy))
                    }
                    if (aiEnabled) {
                        FilledTonalIconButton(
                            enabled = !aiBusy,
                            onClick = {
                                aiBusy = true
                                scope.launch {
                                    aiResult = runCatching { onAnalyze() }
                                    aiBusy = false
                                }
                            }
                        ) {
                            if (aiBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = stringResource(R.string.modules_ai)
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = aiResult != null) {
                    aiResult?.fold(
                        onSuccess = { review ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            when (review.verdict) {
                                                AiCommandVerdict.DANGER -> "Danger"
                                                AiCommandVerdict.SAFE -> "Safe"
                                                AiCommandVerdict.UNKNOWN -> "Is unknown"
                                            },
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                )
                                Text(
                                    text = review.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onFailure = {
                            Text(
                                text = stringResource(
                                    R.string.modules_ai_failed,
                                    it.message ?: it.javaClass.simpleName
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = onApprove
            ) {
                Text(stringResource(R.string.modules_recommand_execute))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.modules_recommand_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}
