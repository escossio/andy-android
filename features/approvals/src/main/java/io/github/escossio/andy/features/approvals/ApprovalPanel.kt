package io.github.escossio.andy.features.approvals

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escossio.andy.sdk.clientapi.ClientApproval
import io.github.escossio.andy.sdk.clientapi.ClientApprovalDecision
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ApprovalPanel(
    state: ApprovalState,
    onRefresh: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    val approvals = approvalsOf(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Surface)
            .border(1.dp, Border, CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(state, approvals.size)

        when {
            state is ApprovalState.Loading && approvals.isEmpty() ->
                SupportingText("Buscando solicitações pendentes…")
            state is ApprovalState.Failure && approvals.isEmpty() -> {
                SupportingText(failureMessage(state.reason), Danger)
                SecondaryButton("Tentar novamente", onRefresh)
            }
            state is ApprovalState.Ready && approvals.isEmpty() -> {
                SupportingText(
                    "Nada pendente. Quando a Andy precisar da sua autorização, aparece aqui.",
                )
                SecondaryButton("Atualizar", onRefresh)
            }
            state is ApprovalState.Idle -> {
                SupportingText(
                    "As decisões sensíveis da Andy aparecem aqui antes de qualquer execução.",
                )
                PrimaryButton("Verificar aprovações", onRefresh)
            }
            else -> {
                if (state is ApprovalState.Failure) {
                    SupportingText(failureMessage(state.reason), Danger)
                } else if (state is ApprovalState.Loading) {
                    SupportingText("Sincronizando…", Muted)
                }
                approvals.forEach { approval ->
                    ApprovalRequestCard(
                        approval = approval,
                        deciding = state is ApprovalState.Deciding &&
                            state.approvalId == approval.approvalId,
                        decidingAction = (state as? ApprovalState.Deciding)
                            ?.takeIf { it.approvalId == approval.approvalId }
                            ?.decision,
                        onApprove = { onApprove(approval.approvalId) },
                        onDeny = { onDeny(approval.approvalId) },
                    )
                }
                SecondaryButton("Atualizar", onRefresh)
            }
        }
    }
}

@Composable
private fun Header(
    state: ApprovalState,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Aprovações",
                size = 19,
                weight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Você mantém a palavra final.",
                size = 13,
                color = Muted,
            )
        }
        val label = when {
            state is ApprovalState.Loading -> "SINCRONIZANDO"
            state is ApprovalState.Deciding -> "ENVIANDO"
            state is ApprovalState.Failure -> "ATENÇÃO"
            count == 0 -> "EM DIA"
            count == 1 -> "1 PENDENTE"
            else -> "$count PENDENTES"
        }
        Pill(
            text = label,
            foreground = if (state is ApprovalState.Failure) Danger else Accent,
            background = if (state is ApprovalState.Failure) DangerSoft else AccentSoft,
        )
    }
}

@Composable
private fun ApprovalRequestCard(
    approval: ClientApproval,
    deciding: Boolean,
    decidingAction: ClientApprovalDecision?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RequestShape)
            .background(QuietSurface)
            .padding(16.dp),
    ) {
        Text(
            text = approval.capability,
            size = 12,
            weight = FontWeight.Bold,
            color = Accent,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = approval.preview,
            size = 15,
            weight = FontWeight.SemiBold,
            color = Ink,
            lineHeight = 21,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Destino: \${approval.target}",
            size = 12,
            color = Muted,
            lineHeight = 17,
        )
        Text(
            text = "Válida até \${EXPIRY_FORMAT.format(approval.expiresAt)}",
            size = 12,
            color = Muted,
            lineHeight = 17,
        )
        Spacer(Modifier.height(14.dp))
        if (deciding) {
            val action = when (decidingAction) {
                ClientApprovalDecision.APPROVE -> "Aprovando…"
                ClientApprovalDecision.DENY -> "Negando…"
                null -> "Enviando decisão…"
            }
            SupportingText(action, Accent)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DecisionButton(
                    text = "Negar",
                    foreground = Danger,
                    background = Surface,
                    border = DangerBorder,
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                )
                DecisionButton(
                    text = "Aprovar",
                    foreground = Color.White,
                    background = Accent,
                    border = Accent,
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    DecisionButton(
        text = text,
        foreground = Color.White,
        background = Accent,
        border = Accent,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
) {
    DecisionButton(
        text = text,
        foreground = Ink,
        background = Surface,
        border = BorderStrong,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecisionButton(
    text: String,
    foreground: Color,
    background: Color,
    border: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, border, ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            size = 14,
            weight = FontWeight.SemiBold,
            color = foreground,
            align = TextAlign.Center,
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
        contentAlignment = Alignment.Center,
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

private fun approvalsOf(state: ApprovalState): List<ClientApproval> =
    when (state) {
        ApprovalState.Idle -> emptyList()
        is ApprovalState.Loading -> state.previous
        is ApprovalState.Ready -> state.approvals
        is ApprovalState.Deciding -> state.approvals
        is ApprovalState.Failure -> state.approvals
    }

private fun failureMessage(reason: ApprovalFailure): String =
    when (reason) {
        ApprovalFailure.NETWORK_FAILURE ->
            "Não foi possível falar com a Andy agora."
        ApprovalFailure.CLIENT_APPROVAL_DISABLED ->
            "Aprovações pelo aplicativo ainda não estão habilitadas."
        ApprovalFailure.CLIENT_APPROVAL_NOT_FOUND,
        ApprovalFailure.CLIENT_APPROVAL_EXPIRED ->
            "Essa solicitação não está mais disponível."
        ApprovalFailure.CLIENT_APPROVAL_AUTHORITY_REJECTED,
        ApprovalFailure.CLIENT_SESSION_AUTHORITY_REJECTED ->
            "Sua sessão não tem autoridade para essa solicitação."
        ApprovalFailure.CLIENT_APPROVAL_CONFLICT ->
            "Essa solicitação já recebeu outra decisão."
        ApprovalFailure.CLIENT_SESSION_UNAUTHENTICATED ->
            "Reconecte sua sessão segura para continuar."
        ApprovalFailure.CLIENT_APPROVAL_UNAVAILABLE,
        ApprovalFailure.CLIENT_SESSION_UNAVAILABLE,
        ApprovalFailure.UNEXPECTED_RESPONSE ->
            "Aprovações estão temporariamente indisponíveis."
    }

private val EXPIRY_FORMAT =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private val Surface = Color(0xFFFFFFFF)
private val QuietSurface = Color(0xFFF4F7F5)
private val Ink = Color(0xFF17201C)
private val Muted = Color(0xFF68736E)
private val Accent = Color(0xFF197253)
private val AccentSoft = Color(0xFFE3F2EB)
private val Border = Color(0xFFDCE5E0)
private val BorderStrong = Color(0xFFB9C8C0)
private val Danger = Color(0xFF9C3F3F)
private val DangerSoft = Color(0xFFF9EAEA)
private val DangerBorder = Color(0xFFD9A7A7)
private val CardShape = RoundedCornerShape(22.dp)
private val RequestShape = RoundedCornerShape(18.dp)
private val ButtonShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(999.dp)
