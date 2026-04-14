package dev.cppide.core.chat

import dev.cppide.core.BuildConfig
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.common.httpGet
import dev.cppide.core.common.httpPost
import dev.cppide.core.common.optStringOrNull
import dev.cppide.core.student.StudentAuthClient
import dev.cppide.core.student.requireToken
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SenderRole { Student, Teacher, Unknown }

data class ChatMessage(
    val id: Long,
    val senderRole: SenderRole,
    val body: String,
    val codeSnapshot: String?,
    val mdSnapshot: String?,
    val createdAt: String,
)

data class Conversation(
    val id: Long,
    val categorySlug: String,
    val exerciseSlug: String,
    val filePath: String,
    val unreadCount: Int,
    val messageCount: Int,
)

data class UnreadEntry(
    val categorySlug: String,
    val exerciseSlug: String,
    val filePath: String,
    val unreadCount: Int,
)

/**
 * HTTP client for per-file chat conversations. Auth token is obtained
 * from [StudentAuthClient] at call time.
 */
class ChatApiClient(
    private val dispatchers: DispatcherProvider,
    private val studentAuth: StudentAuthClient,
    private val baseUrl: String = BuildConfig.EXERCISES_API_URL,
) {
    private fun authToken(): String = studentAuth.requireToken()

    suspend fun listConversations(): Result<List<Conversation>> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/conversations", authToken())
                val arr = JSONObject(body).getJSONArray("conversations")
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Conversation(
                            id = o.getLong("id"),
                            categorySlug = o.getString("categorySlug"),
                            exerciseSlug = o.getString("exerciseSlug"),
                            filePath = o.getString("filePath"),
                            unreadCount = o.optInt("unreadCount", 0),
                            messageCount = o.optInt("messageCount", 0),
                        ))
                    }
                }
            }
        }

    suspend fun getMessages(filePath: String): Result<List<ChatMessage>> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/conversations/$filePath/messages", authToken())
                val arr = JSONObject(body).getJSONArray("messages")
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        add(parseMessage(arr.getJSONObject(i)))
                    }
                }
            }
        }

    suspend fun sendMessage(
        filePath: String,
        categorySlug: String,
        exerciseSlug: String,
        body: String,
        codeSnapshot: String?,
        mdSnapshot: String?,
    ): Result<ChatMessage> = withContext(dispatchers.io) {
        runCatching {
            val reqBody = JSONObject().apply {
                put("categorySlug", categorySlug)
                put("exerciseSlug", exerciseSlug)
                put("body", body)
                if (codeSnapshot != null) put("codeSnapshot", codeSnapshot)
                if (mdSnapshot != null) put("mdSnapshot", mdSnapshot)
            }
            val resp = httpPost(
                "$baseUrl/conversations/$filePath/messages",
                reqBody.toString(),
                authToken(),
            )
            parseMessage(JSONObject(resp).getJSONObject("message"))
        }
    }

    suspend fun unreadSummary(): Result<List<UnreadEntry>> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/conversations/unread-summary", authToken())
                val arr = JSONObject(body).getJSONArray("unread")
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(UnreadEntry(
                            categorySlug = o.getString("categorySlug"),
                            exerciseSlug = o.getString("exerciseSlug"),
                            filePath = o.getString("filePath"),
                            unreadCount = o.optInt("unreadCount", 0),
                        ))
                    }
                }
            }
        }

    suspend fun markRead(filePath: String): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                httpPost("$baseUrl/conversations/$filePath/mark-read", "{}", authToken())
                Unit
            }
        }

    private fun parseMessage(o: JSONObject) = ChatMessage(
        id = o.getLong("id"),
        senderRole = when (o.getString("senderRole")) {
            "student" -> SenderRole.Student
            "teacher" -> SenderRole.Teacher
            else -> SenderRole.Unknown
        },
        body = o.getString("body"),
        codeSnapshot = o.optStringOrNull("codeSnapshot"),
        mdSnapshot = o.optStringOrNull("mdSnapshot"),
        createdAt = o.getString("createdAt"),
    )
}
