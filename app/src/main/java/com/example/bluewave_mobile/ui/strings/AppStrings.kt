package com.example.bluewave_mobile.ui.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Compose-native string catalogue.
 *
 * BlueWave is a pure Jetpack Compose app, so we keep all user-facing
 * copy in Kotlin instead of `res/values/strings.xml`. The selection
 * between English and Russian is made at composition time from the
 * current [LocalConfiguration]'s primary locale, which means the UI
 * reacts immediately when the system locale changes (Android
 * recreates the activity, which re-runs every composable).
 *
 * Add new strings by extending this class and exposing a property —
 * any call site that wants the localized copy should use
 * [rememberAppStrings] to obtain the instance.
 */
@Immutable
class AppStrings(private val isRu: Boolean) {
    val deviceListTitle: String = "BlueWave"

    val deviceListRescanCd: String = if (isRu) {
        "Повторить поиск устройств поблизости"
    } else {
        "Rescan for nearby devices"
    }

    val deviceListEmptyTitle: String = if (isRu) {
        "Устройств не найдено"
    } else {
        "No devices nearby"
    }

    val deviceListEmptyMessage: String = if (isRu) {
        "Подойдите ближе к сопряжённому телефону или нажмите кнопку ниже, чтобы начать новый поиск."
    } else {
        "Move closer to a paired phone or tap the button below to start a fresh scan."
    }

    val deviceListScanAgain: String = if (isRu) "Искать снова" else "Scan again"

    val deviceListBluetoothOffTitle: String = if (isRu) {
        "Bluetooth выключен"
    } else {
        "Bluetooth is off"
    }

    val deviceListBluetoothOffMessage: String = if (isRu) {
        "Включите Bluetooth в системных настройках и вернитесь в BlueWave, чтобы найти устройства поблизости."
    } else {
        "Turn on Bluetooth in system settings, then come back to BlueWave to discover nearby peers."
    }

    val deviceListTryAgain: String = if (isRu) "Повторить" else "Try again"

    val deviceListErrorTitle: String = if (isRu) "Поиск не удался" else "Discovery failed"

    val deviceListRetry: String = if (isRu) "Повторить" else "Retry"

    // Sectioned contact list (Chats / Can-write / Install-suggest).
    val contactsSectionChats: String = if (isRu) "Чаты" else "Chats"
    val contactsSectionCanChat: String = if (isRu) "Можно написать" else "Can start chat"
    val contactsSectionInstallSuggest: String = if (isRu) "Без приложения" else "No app yet"
    val contactsChatEmptyPreview: String = if (isRu) "Сообщений ещё нет" else "No messages yet"
    val contactsInstallSuggestAction: String = if (isRu) {
        "Отправить приложение по Bluetooth"
    } else {
        "Send app via Bluetooth"
    }
    val contactsInstallSuggestSubtitle: String = if (isRu) {
        "BlueWave не обнаружен — поделитесь приложением"
    } else {
        "BlueWave not detected — share the app"
    }
    val contactsOnlineLabel: String = if (isRu) "В сети" else "Online"
    val contactsPairedLabel: String = if (isRu) "Сопряжено" else "Paired"
    val contactsInstallSuggestedSnack: String = if (isRu) {
        "Открыта передача по Bluetooth. Подтвердите на другом устройстве."
    } else {
        "Bluetooth share opened. Confirm on the other device."
    }
    val contactsInstallFailedSnack: String = if (isRu) {
        "На устройстве не нашлось приложения для передачи по Bluetooth."
    } else {
        "No Bluetooth share app found on this device."
    }

    // Chat screen.
    val chatTitle: String = if (isRu) "Чат" else "Chat"
    val chatInputPlaceholder: String = if (isRu) "Сообщение" else "Message"
    val chatInputCd: String = if (isRu) "Поле ввода сообщения" else "Message input"
    val chatEmptyTitle: String = if (isRu) "Сообщений пока нет" else "No messages yet"
    val chatEmptyMessage: String = if (isRu) {
        "Начните разговор, отправив сообщение ниже."
    } else {
        "Start the conversation by sending a message below."
    }
    val chatHistoryErrorTitle: String = if (isRu) {
        "Не удалось загрузить историю"
    } else {
        "Couldn't load history"
    }
    val chatJumpToBottomCd: String = if (isRu) {
        "Прокрутить к последнему сообщению"
    } else {
        "Scroll to latest message"
    }
    val chatConnectionRestored: String = if (isRu) "Связь восстановлена" else "Connection restored"

    fun contactsChatUnreadBadgeCd(count: Int): String = if (isRu) {
        "Непрочитанных сообщений: $count"
    } else {
        "$count unread messages"
    }

    fun chatWithCd(peerName: String): String = if (isRu) {
        "Чат с $peerName"
    } else {
        "Chat with $peerName"
    }
}

/**
 * Returns an [AppStrings] instance bound to the current configuration
 * locale. The result is memoized for the lifetime of the composition;
 * if the system locale changes Android recreates the activity, which
 * forces a fresh `remember` block.
 */
@Composable
fun rememberAppStrings(): AppStrings {
    val locales = LocalConfiguration.current.locales
    val isRu = !locales.isEmpty && locales[0].language == "ru"
    return remember(isRu) { AppStrings(isRu) }
}
