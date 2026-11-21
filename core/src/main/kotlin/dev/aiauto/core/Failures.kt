package dev.aiauto.core

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PaymentRequiredException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

/** Sorts errors into "try again later" and "needs the user", and words them for the screen and for speech. */
object Failures {
    /** True when waiting and retrying is likely to work: dropped connections, timeouts, rate limits, busy servers. */
    fun isTransient(e: Throwable): Boolean = when (e) {
        is GitHubException -> e.code == 408 || e.code == 429 || e.code >= 500 || e.code == 0
        is AnthropicServiceException -> e is SseException || e.statusCode().let { it == 408 || it == 409 || it == 429 || it >= 500 }
        is AnthropicIoException, is AnthropicRetryableException, is IOException -> true
        else -> e.cause?.takeIf { it !== e }?.let(::isTransient) ?: false
    }

    /** True when the error means the saved agent or environment no longer exists for this API key. */
    fun isMissing(e: Throwable): Boolean = e is NotFoundException

    /** A short sentence the driver can understand when it's read aloud. */
    fun describe(e: Throwable): String = when (e) {
        is UnauthorizedException -> "Claude rejected your API key. Check it in Settings."
        is PermissionDeniedException -> "Your Claude API key can't use Managed Agents. Check the key's workspace in Settings."
        is PaymentRequiredException -> "Your Claude account is out of credit."
        is RateLimitException -> "Claude is limiting requests right now. Try again in a minute."
        is OverloadedException -> "Claude is overloaded right now. Try again in a minute."
        is NotFoundException -> "Claude couldn't find this task's session. Start a new task."
        is BadRequestException -> "Claude rejected the request: ${e.message?.take(120) ?: "bad request"}"
        is AnthropicServiceException -> if (e.statusCode() >= 500) "Claude's servers had a problem. Try again in a minute." else "Claude returned an error (${e.statusCode()})."
        is ChatApiException -> when {
            e.code == 401 || e.code == 403 -> "${e.provider.label} rejected your API key. Check it in Settings."
            e.code == 429 -> "${e.provider.label} is limiting requests right now. Try again in a minute."
            e.code >= 500 -> "${e.provider.label}'s servers had a problem. Try again in a minute."
            else -> "${e.provider.label} rejected the request: ${e.message?.take(120) ?: "bad request"}"
        }
        is GitHubException -> when (e.code) {
            401 -> "GitHub rejected your token. Update it in Settings."
            403 -> e.message?.takeIf { "push" in it } ?: "Your GitHub token isn't allowed to do that. Check its permissions."
            404 -> "GitHub couldn't find that repo, or your token can't see it."
            422 -> e.message ?: "GitHub couldn't open the pull request."
            else -> if (e.code >= 500) "GitHub is having trouble right now." else e.message ?: "GitHub returned an error."
        }
        is UnknownHostException -> "No internet connection."
        is InterruptedIOException -> "The connection timed out."
        is AnthropicIoException, is IOException -> "Lost the connection."
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "Something went wrong."
        else -> e.cause?.takeIf { it !== e }?.let(::describe) ?: (e.message ?: "Something went wrong (${e.javaClass.simpleName}).")
    }

    /** Wait before retry number [attempt] (1-based): 2s, 4s, 8s, 16s, then 30s. */
    fun backoffMillis(attempt: Int): Long = minOf(30_000L, 2_000L shl (attempt - 1).coerceIn(0, 4))
}
