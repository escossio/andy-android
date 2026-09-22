package io.github.escossio.andy.features.command

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escossio.andy.sdk.clientapi.ClientCommand

@Composable
fun CommandPanel(
    state: CommandState,
    voiceListening: Boolean,
    microphoneEnabled: Boolean,
    onSend: (String) -> Unit,
    onRefresh: () -> Unit,
    onMicrophone: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val commands = commandsOf(state)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Surface)
            .border(1.dp, Border, CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(state)

        if (commands.isEmpty() && state is CommandState.Idle) {
            SupportingText(
                "Escreva ou fale com a Andy. Comandos como “pare”, “retome” " +
                    "e “espera 30” usam sua sessão autenticada.",
            )
        } else if (commands.isEmpty() && state is CommandState.Loading) {
            SupportingText("Carregando sua conversa…")
        }

        commands.takeLast(8).forEach { command ->
            CommandTurn(command)
        }

        if (state is CommandState.Sending) {
            UserBubble(state.text, pending = true)
        }

        if (state is CommandState.Failure) {
            SupportingText(failureMessage(state.reason), Danger)
        }

        Composer(
            draft = draft,
            voiceListening = voiceListening,
            microphoneEnabled = microphoneEnabled,
            sending = state is CommandState.Sending,
            onDraftChange = { draft = it.take(4000) },
            onSend = {
                val value = draft.trim()
                if (value.isNotEmpty()) {
                    draft = ""
                    onSend(value)
                }
            },
            onMicrophone = onMicrophone,
        )

        if (commands.isNotEmpty()) {
            TextButton("Atualizar conversa", onRefresh)
        }
    }
}

@Composable
private fun Header(state: CommandState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Fale com a Andy",
                size = 20,
                weight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Comandos e tarefas pelo aplicativo.",
                size = 13,
                color = Muted,
            )
        }

        val label = when (state) {
            CommandState.Idle -> "PRONTA"
            is CommandState.Loading -> "SINCRONIZANDO"
            is CommandState.Ready -> "ONLINE"
            is CommandState.Sending -> "ENVIANDO"
            is CommandState.Failure -> "ATENÇÃO"
        }
        Pill(
            text = label,
            foreground = if (state is CommandState.Failure) Danger else Accent,
            background = if (state is CommandState.Failure) DangerSoft else AccentSoft,
        )
    }
}

@Composable
private fun CommandTurn(command: ClientCommand) {
    UserBubble(command.inputText)
    command.responseText?.takeIf { it.isNotBlank() }?.let { response ->
        AndyBubble(
            response = response,
            state = command.state,
        )
    }
}

@Composable
private fun UserBubble(
    text: String,
    pending: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(BubbleShape)
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                text = text,
                size = 14,
                color = Ink,
                lineHeight = 20,
            )
            if (pending) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "Enviando…",
                    size = 11,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun AndyBubble(
    response: String,
    state: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(BubbleShape)
                .background(AndyBubble)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                text = "Andy",
                size = 11,
                weight = FontWeight.Bold,
                color = Accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = response,
                size = 14,
                color = Ink,
                lineHeight = 20,
            )
            if (state == "CLARIFICATION_REQUIRED") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Preciso que você esclareça esse comando.",
                    size = 11,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    voiceListening: Boolean,
    microphoneEnabled: Boolean,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicrophone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(InputShape)
                .background(QuietSurface)
                .border(1.dp, Border, InputShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Digite uma mensagem para a Andy…",
                    size = 14,
                    color = Muted,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !sending,
                textStyle = TextStyle(
                    color = Ink,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
        }

        ActionButton(
            text = if (voiceListening) "●" else "🎤",
            foreground = if (voiceListening) Danger else Accent,
            background = if (voiceListening) DangerSoft else AccentSoft,
            enabled = microphoneEnabled && !sending,
            onClick = onMicrophone,
        )

        ActionButton(
            text = "Enviar",
            foreground = Color.White,
            background = Accent,
            enabled = !sending && draft.isNotBlank(),
            onClick = onSend,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    foreground: Color,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(ButtonShape)
            .background(if (enabled) background else DisabledSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            size = 13,
            weight = FontWeight.SemiBold,
            color = if (enabled) foreground else Muted,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun TextButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            size = 12,
            weight = FontWeight.SemiBold,
            color = Accent,
        )
    }
}

@Composable
private fun Pill(
    text: String,
    foreground: Color,
    background: Color,
) {
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(background)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            size = 10,
            weight = FontWeight.Bold,
            color = foreground,
        )
    }
}

@Composable
private fun SupportingText(
    text: String,
    color: Color = Muted,
) {
    Text(
        text = text,
        size = 13,
        color = color,
        lineHeight = 19,
    )
}

@Composable
private fun Text(
    text: String,
    size: Int,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Int = size + 4,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = lineHeight.sp,
            textAlign = align,
        ),
    )
}

private fun commandsOf(state: CommandState): List<ClientCommand> =
    when (state) {
        CommandState.Idle -> emptyList()
        is CommandState.Loading -> state.previous
        is CommandState.Ready -> state.commands
        is CommandState.Sending -> state.commands
        is CommandState.Failure -> state.commands
    }

private fun failureMessage(reason: CommandFailure): String =
    when (reason) {
        CommandFailure.NETWORK_FAILURE ->
            "Não foi possível falar com a Andy agora."
        CommandFailure.CLIENT_COMMAND_DISABLED ->
            "O canal de comandos ainda não está habilitado."
        CommandFailure.CLIENT_COMMAND_AUTHORITY_REJECTED,
        CommandFailure.CLIENT_SESSION_AUTHORITY_REJECTED ->
            "Sua sessão não tem autoridade para este comando."
        CommandFailure.CLIENT_COMMAND_CONFLICT ->
            "Esse comando já foi recebido com outro conteúdo."
        CommandFailure.CLIENT_COMMAND_INVALID ->
            "Esse comando não pôde ser enviado."
        CommandFailure.CLIENT_SESSION_UNAUTHENTICATED ->
            "Reconecte sua sessão segura para falar com a Andy."
        CommandFailure.CLIENT_COMMAND_UNAVAILABLE,
        CommandFailure.CLIENT_SESSION_UNAVAILABLE,
        CommandFailure.UNEXPECTED_RESPONSE ->
            "O canal da Andy está temporariamente indisponível."
    }

private val Surface = Color(0xFFFFFFFF)
private val QuietSurface = Color(0xFFF4F7F5)
private val UserBubble = Color(0xFFE9F1ED)
private val AndyBubble = Color(0xFFF4F7F5)
private val DisabledSurface = Color(0xFFE8ECEA)
private val Ink = Color(0xFF17201C)
private val Muted = Color(0xFF68736E)
private val Accent = Color(0xFF197253)
private val AccentSoft = Color(0xFFE3F2EB)
private val Border = Color(0xFFDCE5E0)
private val Danger = Color(0xFF9C3F3F)
private val DangerSoft = Color(0xFFF9EAEA)
private val CardShape = RoundedCornerShape(22.dp)
private val BubbleShape = RoundedCornerShape(16.dp)
private val InputShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(999.dp)
