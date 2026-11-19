package io.github.escossio.andy.features.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ContactsCard(state: ContactsState, onConnect: () -> Unit, onDisconnect: () -> Unit, onSync: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Column {
            BasicText("Google Contatos")
            BasicText(when (state) {
                ContactsState.DISCONNECTED -> "Consulte seus contatos na Andy. Acesso somente de leitura."
                ContactsState.AUTHORIZING -> "Aguardando sua autorização no Google…"
                ContactsState.CONNECTING -> "Conectando com segurança…"
                ContactsState.SYNCING -> "Conectado. Sincronizando contatos…"
                ContactsState.CONNECTED -> "Conectado. Seus contatos estão disponíveis na Andy."
                ContactsState.ERROR -> "Não foi possível atualizar a conexão. Confira a configuração Google e tente novamente."
            })
            if (state in setOf(ContactsState.DISCONNECTED, ContactsState.ERROR)) {
                BasicText("Conectar Google Contatos", Modifier.padding(12.dp).clickable(onClick = onConnect), style = TextStyle(color = Color(0xFF2563EB)))
            }
            if (state in setOf(ContactsState.CONNECTED, ContactsState.SYNCING, ContactsState.ERROR)) {
                BasicText("Sincronizar contatos", Modifier.padding(12.dp).clickable(onClick = onSync), style = TextStyle(color = Color(0xFF2563EB)))
                BasicText("Desconectar contatos da Andy", Modifier.padding(12.dp).clickable(onClick = onDisconnect), style = TextStyle(color = Color(0xFF2563EB)))
            }
        }
    }
}
