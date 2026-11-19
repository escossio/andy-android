package io.github.escossio.andy.sdk.clientapi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class AttentionRouterContactsClientTest {
    @Test fun codeUsesAuthenticatedPostAndCannotAlterGmail()=runBlocking {
        val paths=mutableListOf<String>()
        val transport=object:GmailConnectionTransport {
            override suspend fun get(path:String,bearerToken:String)=GmailConnectionTransportResponse(200,"""{"status":"DISCONNECTED"}""")
            override suspend fun delete(path:String,bearerToken:String)=GmailConnectionTransportResponse(200,"""{"status":"DISCONNECTED"}""")
            override suspend fun post(path:String,bearerToken:String,body:String):GmailConnectionTransportResponse {
                paths.add(path);assertTrue(body.contains("authorization_code"));assertTrue(body.contains("synthetic-code"))
                return GmailConnectionTransportResponse(200,"""{"status":"CONNECTED","scope":"$CONTACTS_READONLY_SCOPE"}""")
            }
        }
        val client=AttentionRouterContactsClient("https://example.invalid",transport)
        val credential=ClientSessionCredential("cst_"+"s".repeat(43),"csn_"+"s".repeat(20),Instant.parse("2030-01-01T00:00:00Z"),"hid_"+"h".repeat(20),"cdev_"+"d".repeat(20),"tenant")
        assertTrue(client.connect(credential,"synthetic-code") is ContactsResult.Success)
        assertEquals(listOf("/api/v1/integrations/contacts"),paths)
    }
}
