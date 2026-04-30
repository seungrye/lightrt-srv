package com.litert.tunnel

import com.litert.tunnel.ui.screen.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatMessageTest {

    @Test
    fun `ids are unique even when created in rapid succession`() {
        val messages = (1..100).map { ChatMessage(role = "user", content = "msg $it") }
        val ids = messages.map { it.id }.toSet()
        assertEquals(100, ids.size, "All ChatMessage ids must be unique")
    }

    @Test
    fun `ids from user and assistant created in same call are unique`() {
        // Simulates what MainActivity does: add user + assistant back-to-back
        val user = ChatMessage(role = "user", content = "hello")
        val assistant = ChatMessage(role = "assistant", content = "hi")
        assert(user.id != assistant.id) {
            "User and assistant messages created back-to-back must have different ids (got: ${user.id})"
        }
    }
}
