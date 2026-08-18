@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.annotation.TargetApi
import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.os.Build
import android.os.Bundle

data class AndroidNotificationSnapshot(
    val envelopeValues: Map<String, Any?>,
    val rawTextSelection: RawTextSelection,
)

object AndroidNotificationReader {
    fun read(
        notification: Notification,
        rawTextOrder: List<RawTextField>,
    ): AndroidNotificationSnapshot {
        val extras = notification.extras ?: Bundle.EMPTY
        val candidates =
            mapOf(
                RawTextField.BIG_TEXT to extras.strings(Notification.EXTRA_BIG_TEXT),
                RawTextField.TEXT to extras.strings(Notification.EXTRA_TEXT),
                RawTextField.TEXT_LINES to extras.stringArray(Notification.EXTRA_TEXT_LINES),
                RawTextField.TICKER to listOfNotNull(notification.tickerText?.toString()),
            )
        val rawText = RawTextSelector.select(rawTextOrder, candidates)
        return AndroidNotificationSnapshot(
            envelopeValues =
                mapOf(
                    "actions" to notification.actions?.map(::safeAction).orEmpty(),
                    "audioAttributes" to notification.audioAttributes?.toString(),
                    "badgeIconType" to notification.badgeIconType,
                    "category" to notification.category,
                    "channelId" to notification.channelId,
                    "color" to notification.color,
                    "contentIntent" to notification.contentIntent,
                    "defaults" to notification.defaults,
                    "deleteIntent" to notification.deleteIntent,
                    "extras" to extras,
                    "flags" to notification.flags,
                    "group" to notification.group,
                    "groupAlertBehavior" to notification.groupAlertBehavior,
                    "largeIcon" to notification.getLargeIcon(),
                    "localOnly" to (notification.flags and Notification.FLAG_LOCAL_ONLY != 0),
                    "messages" to safeMessages(extras),
                    "number" to notification.number,
                    "priority" to notification.priority,
                    "publicVersionPresent" to (notification.publicVersion != null),
                    "shortcutId" to notification.shortcutId,
                    "showWhen" to extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true),
                    "smallIcon" to notification.smallIcon,
                    "sortKey" to notification.sortKey,
                    "timeoutAfter" to notification.timeoutAfter,
                    "tickerText" to notification.tickerText,
                    "usesChronometer" to extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
                    "visibility" to notification.visibility,
                    "whenEpochMillis" to notification.`when`,
                ),
            rawTextSelection = rawText,
        )
    }

    private fun safeAction(action: Notification.Action): SafeActionValue =
        SafeActionValue(
            title = action.title?.toString(),
            semanticAction =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    action.semanticAction
                } else {
                    0
                },
            showsUserInterface = null,
            authenticationRequired =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    action.isAuthenticationRequired
                } else {
                    false
                },
            remoteInputs = action.remoteInputs?.map(::safeRemoteInput).orEmpty(),
            hasPendingIntent = action.actionIntent != null,
            icon = action.icon,
            extras = action.extras,
            contextual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) action.isContextual else null,
        )

    private fun safeRemoteInput(input: RemoteInput): SafeRemoteInputValue =
        SafeRemoteInputValue(
            resultKey = input.resultKey,
            label = input.label?.toString(),
            allowFreeFormInput = input.allowFreeFormInput,
            allowedDataTypes = input.allowedDataTypes.toSet(),
            choices = input.choices?.map(CharSequence::toString).orEmpty(),
            editChoicesBeforeSending =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    input.editChoicesBeforeSending
                } else {
                    null
                },
            extras = input.extras,
        )

    private fun safeMessages(extras: Bundle): List<SafeMessageValue> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).map { message ->
                SafeMessageValue(
                    text = message.text?.toString(),
                    timestampEpochMillis = message.timestamp,
                    sender = message.senderPerson?.toSafePerson(),
                    dataMimeType = message.dataMimeType,
                    dataUri = message.dataUri?.toString(),
                    extras = message.extras,
                )
            }
        }
        return bundles.mapNotNull { value ->
            (value as? Bundle)?.let { message ->
                SafeMessageValue(
                    text = message.getCharSequence(MESSAGE_TEXT)?.toString(),
                    timestampEpochMillis = message.getLong(MESSAGE_TIME),
                    sender =
                        SafePersonValue(
                            name = message.getCharSequence(MESSAGE_SENDER)?.toString(),
                            uri = null,
                            key = null,
                            isBot = false,
                            isImportant = false,
                            icon = null,
                        ),
                    dataMimeType = message.getString(MESSAGE_TYPE),
                    dataUri = message.getParcelable<android.net.Uri>(MESSAGE_URI)?.toString(),
                    extras = message.getBundle(MESSAGE_EXTRAS),
                )
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun Person.toSafePerson() =
        SafePersonValue(
            name = name?.toString(),
            uri = uri,
            key = key,
            isBot = isBot,
            isImportant = isImportant,
            icon = icon,
        )

    private const val MESSAGE_TEXT = "text"
    private const val MESSAGE_TIME = "time"
    private const val MESSAGE_SENDER = "sender"
    private const val MESSAGE_TYPE = "type"
    private const val MESSAGE_URI = "uri"
    private const val MESSAGE_EXTRAS = "extras"

    private fun Bundle.strings(key: String): List<String> =
        listOfNotNull(runCatching { getCharSequence(key)?.toString() }.getOrNull())

    private fun Bundle.stringArray(key: String): List<String> =
        runCatching { getCharSequenceArray(key)?.map(CharSequence::toString).orEmpty() }.getOrDefault(emptyList())
}
