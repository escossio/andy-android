package io.github.escossio.andy.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import io.github.escossio.andy.core.humanidentity.HumanIdentityState

@Composable
fun OnboardingScreen(
    state: HumanIdentityState,
    bootstrapState: DeviceBootstrapState,
    sessionState: ClientSessionState,
    locationState: ClientLocationState,
    gmailState: GmailConnectionState,
    onContinue: () -> Unit,
    onContinueSession: () -> Unit,
    onRetryHuman: () -> Unit,
    onRetryBootstrap: () -> Unit,
    onRetrySession: () -> Unit,
    onShareLocation: () -> Unit,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader(connected = sessionState is ClientSessionState.Connected)

        if (sessionState !is ClientSessionState.Idle) {
            SessionContent(
                sessionState = sessionState,
                locationState = locationState,
                gmailState = gmailState,
                onRetrySession = onRetrySession,
                onRestartHuman = onRetryHuman,
                onShareLocation = onShareLocation,
                onConnectGmail = onConnectGmail,
                onDisconnectGmail = onDisconnectGmail,
            )
        } else {
            HumanIdentityContent(
                state = state,
                bootstrapState = bootstrapState,
                onContinue = onContinue,
                onContinueSession = onContinueSession,
                onRetryHuman = onRetryHuman,
                onRetryBootstrap = onRetryBootstrap,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BrandHeader(connected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            AppText(
                text = "Andy",
                size = 34,
                weight = FontWeight.Bold,
                color = Ink,
            )
            AppText(
                text = "Sua assistente pessoal",
                size = 14,
                color = Muted,
            )
        }
        StatusPill(
            text = if (connected) "ONLINE" else "SEGURA",
            foreground = if (connected) Accent else Ink,
            background = if (connected) AccentSoft else QuietSurface,
        )
    }
}

@Composable
private fun HumanIdentityContent(
    state: HumanIdentityState,
    bootstrapState: DeviceBootstrapState,
    onContinue: () -> Unit,
    onContinueSession: () -> Unit,
    onRetryHuman: () -> Unit,
    onRetryBootstrap: () -> Unit,
) {
    when (state) {
        HumanIdentityState.DeviceIdentityUnavailable ->
            MessageCard(
                title = "Este dispositivo ainda não está pronto",
                body = "A identidade segura do aparelho não pôde ser preparada.",
                tone = DangerSoft,
            )
        HumanIdentityState.Unauthenticated ->
            WelcomeCard(
                onContinue = onContinue,
                onContinueSession = onContinueSession,
            )
        HumanIdentityState.RequestingChallenge,
        HumanIdentityState.AcquiringProviderCredential,
        HumanIdentityState.ValidatingBackend ->
            MessageCard(
                title = "Validando sua identidade",
                body = "Conferindo sua conta e preparando um acesso seguro à Andy.",
            )
        is HumanIdentityState.Failure -> {
            MessageCard(
                title = "Não foi possível validar sua identidade",
                body = "Você pode tentar novamente sem perder o que já está configurado.",
                tone = DangerSoft,
            )
            PrimaryButton("Tentar novamente", onRetryHuman)
        }
        is HumanIdentityState.Validated ->
            BootstrapContent(
                bootstrapState = bootstrapState,
                onRetryBootstrap = onRetryBootstrap,
                onRestartHuman = onRetryHuman,
            )
    }
}

@Composable
private fun WelcomeCard(
    onContinue: () -> Unit,
    onContinueSession: () -> Unit,
) {
    SurfaceCard {
        AppText(
            text = "Bem-vindo à Andy",
            size = 24,
            weight = FontWeight.Bold,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        AppText(
            text = "Conecte sua identidade para continuar. Seus serviços e permissões continuam sob seu controle.",
            size = 15,
            color = Muted,
            lineHeight = 22,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continuar com Google", onContinue)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Conectar dispositivo existente", onContinueSession)
    }
}

@Composable
private fun BootstrapContent(
    bootstrapState: DeviceBootstrapState,
    onRetryBootstrap: () -> Unit,
    onRestartHuman: () -> Unit,
) {
    when (bootstrapState) {
        DeviceBootstrapState.Idle ->
            MessageCard(
                title = "Identidade validada",
                body = "Preparando este dispositivo para a sua conta.",
            )
        DeviceBootstrapState.Establishing ->
            MessageCard(
                title = "Preparando seu dispositivo",
                body = "Criando a ligação segura entre este aparelho e a Andy.",
            )
        is DeviceBootstrapState.Established ->
            MessageCard(
                title = "Dispositivo conectado",
                body = "A base segura está pronta. ${bootstrapState.authority.memberships.size} vínculo(s) disponível(is).",
                tone = AccentSoft,
            )
        is DeviceBootstrapState.Failure -> {
            MessageCard(
                title = "Não foi possível preparar o dispositivo",
                body = "Tente novamente ou reinicie a autenticação.",
                tone = DangerSoft,
            )
            PrimaryButton("Tentar novamente", onRetryBootstrap)
            SecondaryButton("Reiniciar acesso", onRestartHuman)
        }
    }
}

@Composable
private fun SessionContent(
    sessionState: ClientSessionState,
    locationState: ClientLocationState,
    gmailState: GmailConnectionState,
    onRetrySession: () -> Unit,
    onRestartHuman: () -> Unit,
    onShareLocation: () -> Unit,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
) {
    when (sessionState) {
        ClientSessionState.Idle -> Unit
        ClientSessionState.Restoring ->
            MessageCard(
                title = "Reconectando à Andy",
                body = "Restaurando sua sessão segura.",
            )
        ClientSessionState.Establishing ->
            MessageCard(
                title = "Criando sessão segura",
                body = "Este dispositivo está confirmando sua identidade.",
            )
        ClientSessionState.LoadingBootstrap ->
            MessageCard(
                title = "Carregando sua Andy",
                body = "Sincronizando o estado autenticado deste dispositivo.",
            )
        is ClientSessionState.Connected ->
            ConnectedHome(
                state = sessionState,
                locationState = locationState,
                gmailState = gmailState,
                onShareLocation = onShareLocation,
                onConnectGmail = onConnectGmail,
                onDisconnectGmail = onDisconnectGmail,
            )
        is ClientSessionState.Failure -> {
            MessageCard(
                title = "A conexão segura falhou",
                body = "A Andy não vai continuar sem uma sessão autenticada válida.",
                tone = DangerSoft,
            )
            PrimaryButton("Tentar conexão novamente", onRetrySession)
            SecondaryButton("Entrar novamente com Google", onRestartHuman)
        }
    }
}

@Composable
private fun ConnectedHome(
    state: ClientSessionState.Connected,
    locationState: ClientLocationState,
    gmailState: GmailConnectionState,
    onShareLocation: () -> Unit,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
) {
    HeroCard(memberships = state.bootstrap.memberships.size)

    SectionTitle("Seu painel")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FeatureTile(
            title = "Aprovações",
            body = "Autorize decisões importantes da Andy.",
            badge = "PRÓXIMA",
            modifier = Modifier.weight(1f),
        )
        FeatureTile(
            title = "Contexto",
            body = "Preferências, memória e informações pessoais.",
            badge = "EM BREVE",
            modifier = Modifier.weight(1f),
        )
    }

    SectionTitle("Serviços")
    GmailCard(
        state = gmailState,
        onConnect = onConnectGmail,
        onDisconnect = onDisconnectGmail,
    )
    LocationCard(
        state = locationState,
        onShare = onShareLocation,
    )
}

@Composable
private fun HeroCard(memberships: Int) {
    SurfaceCard(background = Accent) {
        StatusPill(
            text = "TUDO PRONTO",
            foreground = Color.White,
            background = Color.White.copy(alpha = 0.16f),
        )
        Spacer(Modifier.height(14.dp))
        AppText(
            text = "Andy conectada.",
            size = 26,
            weight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        AppText(
            text = "Sua sessão está segura e pronta para trabalhar por você.",
            size = 15,
            color = Color.White.copy(alpha = 0.86f),
            lineHeight = 21,
        )
        Spacer(Modifier.height(18.dp))
        AppText(
            text = "$memberships vínculo(s) ativo(s) neste perfil",
            size = 12,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun FeatureTile(
    title: String,
    body: String,
    badge: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(Surface)
            .border(1.dp, Border, CardShape)
            .padding(16.dp),
    ) {
        StatusPill(
            text = badge,
            foreground = Accent,
            background = AccentSoft,
        )
        Spacer(Modifier.height(18.dp))
        AppText(
            text = title,
            size = 18,
            weight = FontWeight.SemiBold,
            color = Ink,
        )
        Spacer(Modifier.height(6.dp))
        AppText(
            text = body,
            size = 13,
            color = Muted,
            lineHeight = 18,
        )
    }
}

@Composable
private fun GmailCard(
    state: GmailConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SurfaceCard {
        AppText(
            text = "Gmail",
            size = 19,
            weight = FontWeight.SemiBold,
            color = Ink,
        )
        Spacer(Modifier.height(6.dp))
        when (state) {
            GmailConnectionState.Idle,
            GmailConnectionState.Loading ->
                ServiceStatus("Verificando conexão…", Muted)
            GmailConnectionState.Disconnected -> {
                ServiceStatus("Não conectado", Muted)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Conectar Gmail", onConnect)
            }
            GmailConnectionState.Authorizing ->
                ServiceStatus("Aguardando autorização do Google…", Accent)
            GmailConnectionState.Connecting ->
                ServiceStatus("Conectando Gmail…", Accent)
            GmailConnectionState.Disconnecting ->
                ServiceStatus("Desconectando Gmail…", Muted)
            is GmailConnectionState.Connected -> {
                ServiceStatus("Conectado e disponível", Accent)
                Spacer(Modifier.height(14.dp))
                SecondaryButton("Desconectar Gmail", onDisconnect)
            }
            is GmailConnectionState.Failure -> {
                ServiceStatus("A conexão precisa de atenção", Danger)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Tentar Gmail novamente", onConnect)
            }
        }
    }
}

@Composable
private fun LocationCard(
    state: ClientLocationState,
    onShare: () -> Unit,
) {
    SurfaceCard {
        AppText(
            text = "Localização",
            size = 19,
            weight = FontWeight.SemiBold,
            color = Ink,
        )
        Spacer(Modifier.height(6.dp))
        when (state) {
            ClientLocationState.Idle ->
                ServiceStatus("Compartilhe somente quando quiser.", Muted)
            ClientLocationState.Acquiring ->
                ServiceStatus("Obtendo sua localização atual…", Accent)
            ClientLocationState.Sharing ->
                ServiceStatus("Compartilhando com segurança…", Accent)
            is ClientLocationState.Shared ->
                ServiceStatus(
                    "Atualizada • precisão aproximada de ${state.accuracyM.toInt()} m",
                    Accent,
                )
            is ClientLocationState.Failure ->
                ServiceStatus("Não foi possível atualizar agora.", Danger)
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = if (state is ClientLocationState.Shared) "Atualizar localização" else "Compartilhar localização",
            onClick = onShare,
            enabled = state !is ClientLocationState.Acquiring &&
                state !is ClientLocationState.Sharing,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    tone: Color = Surface,
) {
    SurfaceCard(background = tone) {
        AppText(
            text = title,
            size = 20,
            weight = FontWeight.SemiBold,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        AppText(
            text = body,
            size = 14,
            color = Muted,
            lineHeight = 20,
        )
    }
}

@Composable
private fun SurfaceCard(
    background: Color = Surface,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(background)
            .border(
                width = if (background == Surface) 1.dp else 0.dp,
                color = if (background == Surface) Border else Color.Transparent,
                shape = CardShape,
            )
            .padding(18.dp),
        content = { content() },
    )
}

@Composable
private fun SectionTitle(text: String) {
    AppText(
        text = text,
        size = 14,
        weight = FontWeight.SemiBold,
        color = Muted,
    )
}

@Composable
private fun ServiceStatus(text: String, color: Color) {
    AppText(
        text = text,
        size = 14,
        color = color,
        lineHeight = 19,
    )
}

@Composable
private fun StatusPill(
    text: String,
    foreground: Color,
    background: Color,
) {
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            size = 11,
            weight = FontWeight.Bold,
            color = foreground,
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val background = if (enabled) Accent else DisabledButton
    val foreground = if (enabled) Color.White else Muted
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            size = 15,
            weight = FontWeight.SemiBold,
            color = foreground,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .border(1.dp, BorderStrong, ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            size = 15,
            weight = FontWeight.SemiBold,
            color = Ink,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun AppText(
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

private val AppBackground = Color(0xFFF4F7F5)
private val Surface = Color(0xFFFFFFFF)
private val Ink = Color(0xFF17201C)
private val Muted = Color(0xFF68736E)
private val Accent = Color(0xFF197253)
private val AccentSoft = Color(0xFFE3F2EB)
private val QuietSurface = Color(0xFFE9EEEB)
private val Border = Color(0xFFDCE5E0)
private val BorderStrong = Color(0xFFB9C8C0)
private val DisabledButton = Color(0xFFE1E6E3)
private val Danger = Color(0xFF9C3F3F)
private val DangerSoft = Color(0xFFF9EAEA)
private val CardShape = RoundedCornerShape(22.dp)
private val ButtonShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(999.dp)
