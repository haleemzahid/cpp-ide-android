package dev.cppide.core.student

import android.content.Context
import dev.cppide.core.BuildConfig
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.common.httpGet
import dev.cppide.core.common.httpPost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Student(
    val id: Long,
    val displayName: String,
    val email: String,
)

data class AuthSession(
    val token: String,
    val student: Student,
)

/** Get the current token or throw. Used by authenticated API clients. */
fun StudentAuthClient.requireToken(): String =
    token ?: error("not authenticated")

/**
 * Handles signup, login, and token persistence via SharedPreferences.
 * The token is stored when "remember me" is checked; otherwise it lives
 * only in memory for the current session.
 */
class StudentAuthClient(
    context: Context,
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = BuildConfig.EXERCISES_API_URL,
) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null
    val token: String? get() = _session.value?.token

    /** Try to restore a saved token on app launch. */
    suspend fun tryRestore(): Boolean = withContext(dispatchers.io) {
        val saved = prefs.getString("token", null) ?: return@withContext false
        try {
            val body = httpGet("$baseUrl/me", token = saved)
            val student = parseStudent(JSONObject(body).getJSONObject("student"))
            _session.value = AuthSession(saved, student)
            true
        } catch (_: Exception) {
            prefs.edit().remove("token").apply()
            false
        }
    }

    suspend fun signup(
        displayName: String,
        email: String,
        password: String,
        rememberMe: Boolean,
    ): Result<AuthSession> = withContext(dispatchers.io) {
        runCatching {
            val reqBody = JSONObject().apply {
                put("displayName", displayName)
                put("email", email)
                put("password", password)
            }
            val resp = httpPost("$baseUrl/signup", reqBody.toString())
            val session = parseAuthResponse(resp)
            _session.value = session
            if (rememberMe) prefs.edit().putString("token", session.token).apply()
            session
        }
    }

    suspend fun login(
        email: String,
        password: String,
        rememberMe: Boolean,
    ): Result<AuthSession> = withContext(dispatchers.io) {
        runCatching {
            val reqBody = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("rememberMe", rememberMe)
            }
            val resp = httpPost("$baseUrl/login", reqBody.toString())
            val session = parseAuthResponse(resp)
            _session.value = session
            if (rememberMe) prefs.edit().putString("token", session.token).apply()
            session
        }
    }

    fun logout() {
        _session.value = null
        prefs.edit().remove("token").apply()
    }

    private fun parseAuthResponse(body: String): AuthSession {
        val root = JSONObject(body)
        return AuthSession(
            token = root.getString("token"),
            student = parseStudent(root.getJSONObject("student")),
        )
    }

    private fun parseStudent(s: JSONObject) = Student(
        id = s.getLong("id"),
        displayName = s.getString("displayName"),
        email = s.getString("email"),
    )
}
