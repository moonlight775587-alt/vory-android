package dev.vory.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.vory.android.R
import dev.vory.android.VoryApp
import dev.vory.android.data.AnswerShape
import dev.vory.android.data.PendingCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

const val ACTION_APPROVAL = "dev.vory.android.ACTION_APPROVAL"
const val ACTION_REPLY = "dev.vory.android.ACTION_REPLY"
const val EXTRA_REQUEST_ID = "request_id"
const val EXTRA_CHOICE = "choice"
const val EXTRA_SESSION_ID = "session_id"
const val KEY_TEXT_REPLY = "key_text_reply"

private const val CH_APPROVALS = "vory_approvals"
private const val CH_CHAT = "vory_chat"

/**
 * Local notifications, gateway-direct (no FCM — same bring-your-own model as
 * the original). Approvals carry Approve once / Deny actions; finished turns
 * carry an inline Reply; everything deep-links to vory://chat/<id>.
 */
class VoryNotifications(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        val approvals = NotificationChannel(
            CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Approval, clarify, sudo and secret prompts from your bots" }
        val chat = NotificationChannel(
            CH_CHAT, "Chat activity", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Finished turns and messages while the app is backgrounded" }
        manager.createNotificationChannels(listOf(approvals, chat))
    }

    private fun chatIntent(sessionId: String, draft: String? = null): PendingIntent {
        val uri = Uri.parse("vory://chat/$sessionId").buildUpon()
            .apply { if (draft != null) appendQueryParameter("draft", draft) }
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri, context, dev.vory.android.MainActivity::class.java)
        return PendingIntent.getActivity(
            context, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun showApproval(card: PendingCard) {
        val approve = PendingIntent.getBroadcast(
            context, card.requestId.hashCode(),
            Intent(context, VoryActionReceiver::class.java).apply {
                action = ACTION_APPROVAL
                putExtra(EXTRA_REQUEST_ID, card.requestId)
                putExtra(EXTRA_CHOICE, "once")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getBroadcast(
            context, card.requestId.hashCode() + 1,
            Intent(context, VoryActionReceiver::class.java).apply {
                action = ACTION_APPROVAL
                putExtra(EXTRA_REQUEST_ID, card.requestId)
                putExtra(EXTRA_CHOICE, "deny")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${card.title} — needs you")
            .setContentText(card.detail.take(140))
            .setContentIntent(chatIntent(card.sessionId))
            .setAutoCancel(true)
            .addAction(0, "Approve once", approve)
            .addAction(0, "Deny", deny)
            .build()
        manager.notify(card.requestId.hashCode(), n)
    }

    fun showTurnDone(sessionId: String, preview: String) {
        val replyInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply").build()
        val reply = PendingIntent.getBroadcast(
            context, sessionId.hashCode() + 7,
            Intent(context, VoryActionReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_CHAT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Turn finished")
            .setContentText(preview.ifEmpty { "Your bot finished a turn." })
            .setContentIntent(chatIntent(sessionId))
            .setAutoCancel(true)
            .addAction(
                NotificationCompat.Action.Builder(0, "Reply", reply)
                    .addRemoteInput(replyInput).build(),
            )
            .build()
        manager.notify(sessionId.hashCode(), n)
    }

    fun dismissApproval(id: Int) = manager.cancel(id)
}

/**
 * Handles notification actions without opening the UI:
 * approval choices are answered on the live socket via AppRepository.
 */
class VoryActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VoryApp ?: return
        val repo = app.repository
        when (intent.action) {
            ACTION_APPROVAL -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
                val choice = intent.getStringExtra(EXTRA_CHOICE) ?: "deny"
                val pending = goAsync()
                scope.launch {
                    try {
                        val card = repo.pendingCard(requestId) ?: return@launch
                        // CHOICE cards only; secret/text cards must be answered in-app.
                        if (repo.answerShape(card) != AnswerShape.CHOICE) return@launch
                        repo.answerCard(card, JSONObject().put("choice", choice))
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_REPLY -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_TEXT_REPLY)?.toString().orEmpty()
                if (text.isBlank()) return
                // Deep-link into the chat with the reply as a prefilled draft.
                val uri = Uri.parse("vory://chat/$sessionId").buildUpon()
                    .appendQueryParameter("draft", text).build()
                val open = Intent(Intent.ACTION_VIEW, uri, context, dev.vory.android.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(open)
            }
        }
    }
}
