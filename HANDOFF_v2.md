# HANDOFF v2 — что осталось доделать

> Документ для следующего разработчика (или для следующей сессии). Описывает,
> что в проекте уже работает, что осталось доделать, и **как именно** это
> делать: с путями к файлам, готовыми сниппетами, оценкой времени и
> приоритетами.
>
> Базовая ветка: `develop`. Все шаги ниже — отдельные коммиты в фиче-ветки и
> отдельные PR в `develop` через `git merge --no-ff` (без squash, без amend),
> по правилам HANDOFF.md §5.

---

## 0. Текущее состояние (что уже работает)

### Что было сделано в PR #1 (Участник 1)
Базовый каркас приложения:

- Compose-UI: `DeviceListScreen`, `ChatScreen`, `MessageBubble`, `EmptyStateView`,
  `PermissionGateView`, `TwoPaneLayout`.
- Room-БД: `Message`, `MessageDao`, `BlueWaveDatabase`.
- Шифрование at-rest: `CryptoManager` (AES-256-GCM, ключ в Android Keystore).
- BluetoothBroadcastReceiver, BondLossReceiver — слушают системные события.
- Discovery (`BluetoothDeviceScanner`, `DeviceListViewModel`) — устройства
  находятся, и приложение их корректно отображает.

### Что было сделано в PR #2 (текущий, ветка `devin/1778150491-handoff-finish`)

Коммиты по порядку:

1. `9ee08e0` — `feat(ui): finish localization sweep …` — все хардкод-строки
   вынесены в `values/strings.xml` + `values-ru/strings.xml`.
2. `7af7440` — `test(viewmodel): add ChatViewModel and DeviceListViewModel
   JVM unit tests` — 13 новых JVM-тестов, `MainDispatcherRule`.
3. `78ce694` — `test(ui): add instrumented Compose tests …` —
   `EmptyStateViewTest`, `TwoPaneLayoutTest`, `MessageBubbleTest`,
   `PermissionGateViewTest`.
4. `a6d8f10` — `fix(manifest): register MainActivity as the LAUNCHER
   activity` — без этого не запускалось из Android Studio.
5. `503aa2d` — **`fix(network): wire Bluetooth session manager so messages
   actually transmit`** — главный функциональный фикс этого PR.

#### Что именно решает коммит `503aa2d`

До него `MessageRepositoryImpl.sendMessage()` шифровал сообщение, писал в
Room и **ничего не отправлял в эфир**. Поэтому второе устройство видело
имя в списке, но текста не получало.

Сейчас в `app/src/main/java/com/example/bluewave_mobile/network/`:

- `MessageFraming.kt` — кодек 4-байтового big-endian length-prefix:
  `[length:UInt32 BE][payload:length bytes]`. Вместе с
  `FrameAccumulator` корректно собирает фреймы при любой фрагментации
  RFCOMM (TCP-style stream без сообщений).
- `MessageTransport.kt` — интерфейс `incoming: Flow<IncomingPeerMessage>`,
  `connect(mac)`, `send(mac, payload)`, `disconnect(mac)`. Репозиторий
  знает только этот интерфейс, никаких прямых ссылок на BluetoothSocket.
- `BluetoothSession.kt` — обёртка над одним RFCOMM-сокетом: запускает
  reader-loop в `Dispatchers.IO`, прокачивает байты через
  `FrameAccumulator`, эмитит фреймы наружу. `send()` — синхронный
  write через тот же сокет под `Mutex`.
- `BluetoothSessionManager.kt` — реализует `MessageTransport`, держит
  `ConcurrentHashMap<String /* MAC */, BluetoothSession>`, accept-loop
  на серверном `RfcommServerSocket` с UUID
  `3f1c8a72-7e2c-4f4d-9b40-6d5b1f8b9d31`, и connect-логику для
  исходящих соединений. Под `sessionLock: Mutex`, чтобы не было гонки
  «accept + connect одного и того же MAC одновременно создали две
  сессии».

DI-плумбинг:

- `AppContainer` создаёт `bluetoothSessionManager: BluetoothSessionManager`
  и **передаёт его как `transport: MessageTransport`** в
  `MessageRepositoryImpl`.
- `BlueWaveApplication.onCreate()` вызывает
  `appContainer.bluetoothSessionManager.start()` и запускает корутину,
  которая собирает `transport.incoming` и кладёт каждое входящее
  сообщение в БД через `messageRepository.processIncomingMessage(...)`.
- `BlueWaveApplication.onTerminate()` вызывает `shutdown()`.
- `ChatViewModel` принимает опциональный `transport`. На старте
  чата (init) дёргает `transport.connect(macAddress)` — auto-connect,
  чтобы первое сообщение уходило сразу, без явного клика «коннект».

Контракт wire-протокола: **plain text** (ровно так, как требует
HANDOFF.md §2.3). Шифрование AES-GCM остаётся, но только для at-rest
— то есть для записи в Room. Это сознательный выбор: настоящая E2EE
требует key exchange (X3DH/Noise), который под отдельную задачу
описан ниже в §3.

#### Локальные гейты на 503aa2d

```
./gradlew :app:compileDebugKotlin :app:ktlintCheck :app:testDebugUnitTest :app:assembleDebug
```

Все четыре зелёные. `app-debug.apk` собирается. Юнит-тестов теперь 35
(было 30): добавлены `MessageFramingTest` (round-trip, фрагментация,
склейка, oversize) и переписан `MessageRepositoryIntegrationTest` под
новый контракт (plain wire, шифрование при записи, корректный
`disconnect()` при `pauseNetworkOperations()`).

---

## 1. Приоритет 1 (P1) — UI-редизайн под макет

### Цель

Из дизайна, присланного в чат:

- Один скроллящийся список «Контакты».
- Сначала строки **«с кем уже есть переписка»** — показывают имя, превью
  последнего сообщения, время.
- Дальше строки **«с кем можно начать переписку»** — устройства,
  обнаруженные по Bluetooth, у которых поднят сервис BlueWave (наш UUID
  виден в SDP).
- В конце строки **«устройство без приложения»** — обнаружено, но SDP
  не содержит наш UUID. У такой строки кнопка **«Предложить установку»**.
- При тапе на эту кнопку наш APK отправляется на выбранное устройство
  по Bluetooth OPP / SPP file-transfer (см. §2 ниже).

### Что менять

`app/src/main/java/com/example/bluewave_mobile/ui/screens/DeviceListScreen.kt`
сейчас просто отображает плоский `LazyColumn` устройств. Нужно
сделать секционированный список с тремя категориями.

#### 1.1. Новый sealed-класс для строки списка

Создать
`app/src/main/java/com/example/bluewave_mobile/ui/model/ContactRow.kt`:

```kotlin
package com.example.bluewave_mobile.ui.model

import com.example.bluewave_mobile.data.Message
import com.example.bluewave_mobile.bluetooth.BluetoothDevice

sealed interface ContactRow {

    /** Имя устройства, MAC — это идентификатор переписки. */
    val displayName: String
    val macAddress: String

    /** Уже есть переписка: показываем превью и время. */
    data class ExistingChat(
        override val displayName: String,
        override val macAddress: String,
        val lastMessage: Message?,
        val unreadCount: Int,
        val isOnline: Boolean,
    ) : ContactRow

    /** Видно по BT, наш сервис поднят, переписки ещё нет. */
    data class StartChatCandidate(
        override val displayName: String,
        override val macAddress: String,
        val isBonded: Boolean,
    ) : ContactRow

    /** Видно по BT, нашего сервиса нет → предложить установку. */
    data class InstallSuggestion(
        override val displayName: String,
        override val macAddress: String,
    ) : ContactRow
}
```

#### 1.2. Расширить DeviceListViewModel

В `app/src/main/java/com/example/bluewave_mobile/ui/viewmodel/DeviceListViewModel.kt`:

1. Принять в конструктор `MessageRepository` (через `AppContainer`).
2. Объединить (через `combine`) три источника:
   - `messageRepository.observeAllConversations(): Flow<List<ConversationSummary>>`
     — нужно добавить новый метод; см. §1.4.
   - текущий `Flow` устройств от `BluetoothDeviceScanner`.
   - per-MAC флаг «наш сервис виден в SDP» — см. §1.3.
3. Эмитить `state.contactRows: List<ContactRow>` в нужном порядке:
   `ExistingChat` (по `lastMessage.timestamp` desc) → `StartChatCandidate`
   → `InstallSuggestion`.

#### 1.3. SDP-проба «есть ли у пира BlueWave-сервис»

Чтобы отличить «друг с приложением» от «просто Bluetooth-устройство»,
делаем `device.fetchUuidsWithSdp()`. Это асинхронный API,
результат приходит через `BluetoothDevice.ACTION_UUID`-broadcast.

В `app/src/main/java/com/example/bluewave_mobile/bluetooth/BlueWaveSdpProber.kt` (новый файл):

```kotlin
package com.example.bluewave_mobile.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import com.example.bluewave_mobile.network.BluetoothConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class BlueWaveSdpProber(private val context: Context) {

    /** mac → true если у этого устройства есть наш Service-UUID. */
    private val _appPresence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val appPresence: StateFlow<Map<String, Boolean>> = _appPresence

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val device: BluetoothDevice =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val uuids: Array<Parcelable>? =
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            val hasOurUuid = uuids?.any {
                (it as? android.os.ParcelUuid)?.uuid == BluetoothConstants.APP_UUID
            } == true
            _appPresence.update { it + (device.address.uppercase() to hasOurUuid) }
        }
    }

    fun start() {
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            Context.RECEIVER_EXPORTED,
        )
    }

    fun stop() = context.unregisterReceiver(receiver)

    @SuppressLint("MissingPermission")
    fun probe(device: BluetoothDevice) {
        // По SDP-кешу первый запрос почти бесплатный, но всё-равно
        // ставим debounce 5–10s в DeviceListViewModel.
        device.fetchUuidsWithSdp()
    }
}
```

Зарегистрировать в `AppContainer` и стартовать в
`BlueWaveApplication.onCreate()`. В `DeviceListViewModel` собирать
`appPresence` и комбинировать с устройствами.

#### 1.4. Метод `observeAllConversations()` в репо

В `MessageRepository` добавить:

```kotlin
data class ConversationSummary(
    val macAddress: String,
    val lastMessage: Message,
    val unreadCount: Int,
)

fun observeAllConversations(): Flow<List<ConversationSummary>>
```

В `MessageDao` (см. `app/src/main/java/com/example/bluewave_mobile/data/MessageDao.kt`):

```kotlin
@Query("""
  SELECT m.* FROM messages m
  INNER JOIN (
    SELECT macAddress, MAX(timestamp) AS maxTs
    FROM messages
    GROUP BY macAddress
  ) t ON m.macAddress = t.macAddress AND m.timestamp = t.maxTs
  ORDER BY m.timestamp DESC
""")
fun observeLastMessagesPerPeer(): Flow<List<MessageEntity>>

@Query("SELECT macAddress, COUNT(*) AS unread FROM messages WHERE isRead = 0 AND isOutgoing = 0 GROUP BY macAddress")
fun observeUnreadCounts(): Flow<List<UnreadByPeer>>
```

Если в `MessageEntity` нет поля `isRead` — добавить (миграция Room +1
версия). Для P1 можно временно проставить `isRead = false` для всех
входящих и read-помечать в `ChatScreen.LaunchedEffect { ... }`.

#### 1.5. Перерисовать `DeviceListScreen.kt`

Заменить плоский `LazyColumn(items)` на секционный:

```kotlin
LazyColumn(...) {
    if (state.contactRows.any { it is ContactRow.ExistingChat }) {
        item { SectionHeader(stringResource(R.string.section_chats)) }
        items(state.contactRows.filterIsInstance<ContactRow.ExistingChat>(), key = { it.macAddress }) {
            ChatRowExisting(it, onClick = { onSelect(it.macAddress) })
        }
    }
    if (state.contactRows.any { it is ContactRow.StartChatCandidate }) {
        item { SectionHeader(stringResource(R.string.section_can_start)) }
        items(state.contactRows.filterIsInstance<ContactRow.StartChatCandidate>(), key = { it.macAddress }) {
            StartChatCandidateRow(it, onClick = { onSelect(it.macAddress) })
        }
    }
    if (state.contactRows.any { it is ContactRow.InstallSuggestion }) {
        item { SectionHeader(stringResource(R.string.section_no_app)) }
        items(state.contactRows.filterIsInstance<ContactRow.InstallSuggestion>(), key = { it.macAddress }) {
            InstallSuggestionRow(it, onSendApk = { onSendApk(it.macAddress) })
        }
    }
}
```

`SectionHeader`, `ChatRowExisting`, `StartChatCandidateRow`,
`InstallSuggestionRow` — отдельные `@Composable`-функции в той же
папке, плюс `@Preview` для каждого.

Иконку «отправить APK» делать `OutlinedButton` с
`Icons.Default.FileUpload` и текстом «Предложить установку» /
`Suggest install`.

Все строки — через `stringResource`. Добавить ключи:

```xml
<!-- values/strings.xml -->
<string name="section_chats">Chats</string>
<string name="section_can_start">Can start chat</string>
<string name="section_no_app">No app yet</string>
<string name="action_suggest_install">Suggest install</string>
<string name="install_progress">Sending app to %1$s…</string>
```

```xml
<!-- values-ru/strings.xml -->
<string name="section_chats">Чаты</string>
<string name="section_can_start">Можно написать</string>
<string name="section_no_app">Без приложения</string>
<string name="action_suggest_install">Предложить установку</string>
<string name="install_progress">Отправляем приложение на %1$s…</string>
```

#### Оценка времени P1
- VM + ContactRow + Repo-метод: 4 ч
- SDP-проба + интеграция: 2 ч
- DeviceListScreen + строки + Preview: 4 ч
- Тесты на VM-сортировку + Compose-snapshot хедеров: 3 ч

Итого ~13 ч.

---

## 2. Приоритет 1 (P1) — отправка APK на «устройство без приложения»

### Подход

Стандарт Android для этого — Bluetooth **OPP (Object Push Profile)**.
Системная реализация лежит в `com.android.bluetooth` (включена на
большинстве Android-смартфонов). Мы НЕ реализуем OPP сами — мы
делегируем системному OPP-серверу через `Intent`.

### 2.1. Подготовить APK для отправки

В `BlueWaveApplication.onCreate()` копируем установленный APK самого
себя в кеш:

```kotlin
val sourceApk = applicationContext.packageManager
    .getApplicationInfo(packageName, 0).sourceDir
val target = File(cacheDir, "BlueWave-current.apk")
File(sourceApk).copyTo(target, overwrite = true)
```

И настроить `FileProvider` в `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`res/xml/file_paths.xml`:

```xml
<paths>
    <cache-path name="apk_cache" path="." />
</paths>
```

### 2.2. Класс `ApkSender`

`app/src/main/java/com/example/bluewave_mobile/bluetooth/ApkSender.kt`:

```kotlin
package com.example.bluewave_mobile.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class ApkSender(private val context: Context) {

    fun suggestInstall(macAddress: String): Result<Unit> {
        val apk = File(context.cacheDir, "BlueWave-current.apk")
        if (!apk.exists()) return Result.failure(IllegalStateException("APK not staged"))

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Адресуемся напрямую системному Bluetooth-приложению.
            setPackage("com.android.bluetooth")
        }

        return try {
            context.startActivity(send)
            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback: системный share-sheet, пользователь сам выберет
            // Bluetooth и MAC.
            context.startActivity(
                Intent.createChooser(
                    send.apply { setPackage(null) },
                    context.getString(R.string.action_suggest_install),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result.success(Unit)
        }
    }
}
```

> **Внимание.** Программно «насильно» отправить файл на конкретный
> MAC из приложения нельзя — Android показывает свой системный
> bluetooth-share UI с подтверждением получения. Это ограничение
> платформы, обойти его без MDM/Device Owner политики не получится.
> Поэтому UX такой: тап «Предложить установку» → системный диалог
> Bluetooth-share уже с предзаполненным APK → пользователь
> подтверждает MAC из списка спаренных устройств → на втором
> устройстве приходит привычный диалог «принять файл».

### 2.3. Подключить в DeviceListScreen

`onSendApk(mac: String)` в `DeviceListViewModel`:

```kotlin
fun onSendApk(mac: String) {
    viewModelScope.launch {
        val result = apkSender.suggestInstall(mac)
        if (result.isFailure) _events.emit(Event.Error(R.string.install_failed))
    }
}
```

### Оценка времени P2
- FileProvider + копирование APK: 1 ч
- ApkSender + Intent dance: 1 ч
- UI-кнопка + e2e-проверка между двумя устройствами: 2 ч

Итого ~4 ч.

---

## 3. Приоритет 2 (P2) — реальное E2EE (а не только at-rest)

Сейчас контракт wire-протокола — plain text. Это зафиксировано в
HANDOFF §2.3 как первая итерация. Для второй итерации нужно настоящее
end-to-end шифрование.

### Рекомендованный путь: **libsignal-protocol-java**

Готовая Kotlin-friendly реализация Signal Protocol (X3DH + Double
Ratchet). Зрелая, ревьюенная криптография.

`gradle/libs.versions.toml`:

```toml
[versions]
libsignal = "0.62.0"

[libraries]
libsignal-client = { module = "org.signal:libsignal-client", version.ref = "libsignal" }
```

`app/build.gradle.kts`:

```kotlin
implementation(libs.libsignal.client)
```

### Изменения в архитектуре

1. Новый класс `KeyStore` — хранит наш long-term `IdentityKeyPair`,
   `signedPreKey`, прицепные `oneTimePreKeys`. На первом старте
   приложения генерируется один раз, кладётся в
   `EncryptedSharedPreferences`.
2. При первом успешном `BluetoothSession` мы сначала обмениваемся
   prekey-bundle (не зашифрованный handshake-frame с
   `type=PREKEY_BUNDLE`). Потом каждая сторона строит
   `SessionCipher` для этого пира.
3. `MessageRepositoryImpl.sendMessage(...)`:
   - `val ciphertext = sessionCipher.encrypt(plaintext)`
   - `transport.send(mac, ciphertext.serialize())`
4. На `processIncomingMessage(...)`:
   - `val plaintext = sessionCipher.decrypt(SignalMessage(payload))`
   - дальше — как сейчас, шифруем для at-rest и пишем в Room.

### Что точно НЕ делать

- НЕ изобретать свой handshake. AES-GCM с фиксированным IV или
  плоский Diffie-Hellman без аутентификации — антипаттерн.
- НЕ переиспользовать AES-ключ из `CryptoManager` для wire-протокола:
  он хранится в Keystore только нашего устройства, у пира его нет.

### Оценка времени P3
- Подключение либы + KeyStore: 4 ч
- Handshake-frame + интеграция в `BluetoothSession`: 6 ч
- Тесты криптомодуля + e2e между двумя устройствами: 4 ч

Итого ~14 ч.

---

## 4. Приоритет 2 (P2) — надёжная доставка

Сейчас архитектура «отправил в сокет → надеемся, что пришло». Этого
достаточно для дев-теста, но не для production. Нужен ACK-протокол:

### 4.1. Формат фрейма

Поверх 4-byte length-prefix добавить 1 байт типа:

```
[length:UInt32 BE][type:UInt8][messageId:16 bytes][payload:length-17 bytes]
```

`type`:
- `0x01` — DATA (payload = текст / зашифрованный SignalMessage)
- `0x02` — ACK (payload пустой)
- `0x03` — PREKEY_BUNDLE (см. §3)

### 4.2. Где менять

- `MessageFraming.kt` — добавить параметр `type` в `frame()`,
  `FrameAccumulator.next()` возвращает `Pair<UInt8, ByteArray>`.
- `BluetoothSession` — раздаёт фреймы по типу.
- `MessageRepositoryImpl` — после `transport.send(... type=DATA ...)`
  ставит `Message.deliveryStatus = Sent` (новое поле в БД, миграция
  Room +1). На приём ACK — `Delivered`. Таймаут 30s без ACK →
  `Failed`, кнопка retry.
- UI: `MessageBubble` — вместо одной галочки рендерить часы / одну
  / две / красный восклицательный знак (см. Telegram-pattern).

### 4.3. Идемпотентность

`messageId` — random UUID. Если ACK-таймаут сработал, retry шлёт
тот же UUID. Получатель хранит `Set<UUID>` последних 1000 принятых
сообщений (LRU) и игнорирует повторные.

### Оценка времени P4
- Формат фрейма + миграция: 4 ч
- ACK-логика + таймауты + retry-UI: 6 ч
- Тесты: 3 ч

Итого ~13 ч.

---

## 5. Приоритет 2 (P2) — фоновое прослушивание + уведомления

Сейчас accept-loop живёт только пока приложение в foreground. Чтобы
получать сообщения, когда пользователь свернул, нужен
**Foreground Service**.

### 5.1. Создать `BlueWaveBluetoothService`

`app/src/main/java/com/example/bluewave_mobile/service/BlueWaveBluetoothService.kt`:

```kotlin
class BlueWaveBluetoothService : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildOngoingNotification())
        (application as BlueWaveApplication).appContainer.bluetoothSessionManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        (application as BlueWaveApplication).appContainer.bluetoothSessionManager.shutdown()
        super.onDestroy()
    }

    private fun buildOngoingNotification(): Notification {
        // ... NotificationChannel "bluewave_running" + NotificationCompat.Builder
    }

    companion object { const val NOTIF_ID = 4242 }
}
```

`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.BlueWaveBluetoothService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

Запускать сервис из `BlueWaveApplication.onCreate()` через
`ContextCompat.startForegroundService(...)` после получения
`BLUETOOTH_CONNECT` + `POST_NOTIFICATIONS`.

### 5.2. Уведомление о новом сообщении

В коллекторе входящих в `BlueWaveApplication` (или лучше в
самом сервисе) при поступлении сообщения — `NotificationCompat`
с `setContentIntent` на `MainActivity` + extra `mac`. В
`MainActivity.onCreate` — если есть extra, открыть нужный чат.

### Оценка времени P5
- Service + уведомление + permissions runtime-prompt: 6 ч
- Реакция на тап-уведомление: 2 ч
- Бой с doze/standby (если будет) и тесты: 3 ч

Итого ~11 ч.

---

## 6. Приоритет 3 (P3) — мелкие улучшения

| # | Задача | Где | Время |
|---|---|---|---|
| 6.1 | Pull-to-refresh в `DeviceListScreen` (кикает discovery) | `DeviceListScreen.kt`, нужен `androidx.compose.material3:material3 + accompanist:swiperefresh` | 1 ч |
| 6.2 | Long-press на чате → меню «Удалить переписку» | `ChatRowExisting.kt` + `MessageDao.deleteByMac()` | 2 ч |
| 6.3 | Поиск по чатам (top bar `SearchBar`) | `DeviceListScreen.kt` + filter в VM | 2 ч |
| 6.4 | Read receipts (когда чат открыт — пометить непрочитанные `isRead=1` и послать ACK типа `READ`) | вместе с §4 | 2 ч |
| 6.5 | Дата-разделители в `ChatScreen` («Сегодня», «Вчера», «12 мая») | `ChatScreen.kt`, group-by-day | 2 ч |
| 6.6 | Дарк-тема и Material-You динамические цвета | `ui/theme/Theme.kt` (уже есть, проверить и расширить) | 1 ч |
| 6.7 | Бекап БД через `BACKUP_AGENT` | `BlueWaveBackupAgent` + `android:fullBackupContent` | 3 ч |
| 6.8 | i18n: добавить хотя бы один не-английский, не-русский язык (например, узбекский / казахский, если целевая аудитория) | `values-uz/strings.xml`, `values-kk/strings.xml` | 1 ч / язык |

---

## 7. Приоритет 3 (P3) — testing

### 7.1. Smoke на двух физических устройствах

Минимальный сценарий, который надо прогнать руками:

1. Установить тот же `app-debug.apk` на два Android-устройства
   (≥ Android 12, потому что в манифесте
   `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` runtime-permissions).
2. На устройстве A: открыть приложение → выдать все BT-пермишшены
   → BlueWave должно появиться в верхнем списке.
3. На устройстве B: то же самое. Включить «Видимость для других
   устройств» в системных настройках на 5 минут (без этого
   classic-discovery с устройства A не увидит B).
4. На устройстве A: дождаться, пока B появится в списке. Тапнуть.
5. Откроется `ChatScreen`. `ChatViewModel` дёрнет
   `transport.connect(mac)`. На устройстве B при первом коннекте
   система покажет диалог «принять подключение?». Принять.
6. Отправить сообщение с A.
7. Проверить, что на B появляется в `ChatScreen` соответствующего
   peer.
8. Ответить с B → проверить, что приходит обратно на A.
9. Закрыть/открыть приложение, проверить, что история сохранилась
   (Room).
10. Свернуть приложение (с включённым §5 foreground-сервисом) →
    отправить с другого устройства → проверить, что приходит
    уведомление.

### 7.2. Что писать в `androidTest`

- `MessageFramingInstrumentedTest` — round-trip между двумя
  фейковыми сокетами через PipedInputStream/PipedOutputStream.
- `BluetoothSessionManagerInstrumentedTest` — двусторонний loopback:
  одно приложение слушает, само к себе коннектится, шлёт фрейм,
  ловит на incoming. Маркер: `@RequiresDevice` (нужен живой
  BluetoothAdapter).
- `EndToEndChatFlowTest` — Compose UI test: `composeTestRule` запускает
  `MainActivity`, мокает `MessageTransport` → проверяет, что
  набранное сообщение появляется в чате и улетает в фейковый
  transport.

### Оценка времени P6
- Smoke на двух устройствах + видео: 1 ч
- Instrumented-тесты: 6 ч

---

## 8. Технический долг (на потом, не блокирует)

- **AGP 9.2.1 vs 9.1.0.** Сейчас зафиксирован 9.1.0 (ветка фиксит это
  через sed-команду или локальный `gradle/libs.versions.toml`).
  После выхода Android Studio Narwhal Feature Drop стабильной → можно
  обновить обратно. Файл: `gradle/libs.versions.toml`, ключ `agp`.
- **`UnspecifiedRegisterReceiverFlag` в `BondLossReceiver.kt`.**
  Старый шаг, лежит в `develop` ещё до моей работы. Нужно один раз
  поправить:
  ```kotlin
  ContextCompat.registerReceiver(
      context, receiver, filter, ContextCompat.RECEIVER_EXPORTED
  )
  ```
  и `lintDebug` пройдёт. Не делал в этой сессии по правилу
  HANDOFF §5 «не модифицируем уже смерженные шаги».
- **Bump зависимостей** (HANDOFF §3.5): composeBom 2026.04.01,
  Room 3.0.0, navigation 2.9.8. Опционально, отдельный PR.
- **Гарантия порядка** при многопиршовых ситуациях: сейчас MAC
  uppercase-нормализуется в session manager, но не везде в репо.
  Аудит на консистентность.
- **Бондинг.** Сейчас перед `connect()` мы не форсим pairing. Если
  устройства не были спарены, у пользователя B вылетит системный
  диалог. Можно сделать UX мягче: при тапе в `StartChatCandidate`
  → `device.createBond()` → ждать `BluetoothDevice.ACTION_BOND_STATE_CHANGED`
  → потом коннектиться.

---

## 9. Чек-лист «как мержить и катить»

> Эти правила взяты из HANDOFF.md §5 первого участника и
> распространяются на всё дальнейшее.

- ✱ Каждый шаг — отдельная фиче-ветка от `develop`.
  Имя: `feat/<area>-<short>` или `fix/<area>-<short>`.
- ✱ `git merge --no-ff` в `develop` (НЕ squash, НЕ rebase-merge).
  Это создаёт явный merge-commit, и всю эволюцию видно в `git log`.
- ✱ НЕ amend. НЕ force-push в `develop`/`main`. На своих фичевых
  ветках можно `--force-with-lease`, но желательно не надо.
- ✱ Автор всех коммитов — то имя/email, под которым работаем.
  Текущая сессия писала под `WaveWaySpindle <sharondaglassett@gmail.com>`.
- ✱ Никаких AI-trailers (`Co-authored-by: Claude`,
  `🤖 Generated with...` и т.п.).
- ✱ Перед мержем PR в `develop`:
  1. `./gradlew :app:compileDebugKotlin` — зелёный
  2. `./gradlew :app:ktlintCheck` — зелёный
  3. `./gradlew :app:testDebugUnitTest` — зелёный
  4. `./gradlew :app:assembleDebug` — собирается APK
- ✱ Финальный мерж `develop` → `main` — отдельный PR, тоже `--no-ff`,
  с changelog в описании.

---

## 10. Контакты «гайд по запуску за 5 минут»

Если нужно с нуля у себя поднять:

```bash
# 1) JDK 17 (mac)
brew install --cask temurin@17
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
echo 'export PATH=$JAVA_HOME/bin:$PATH'                >> ~/.zshrc
source ~/.zshrc

# 2) Android SDK — через Android Studio (она сама докачает API 34/35)
# Открыть Android Studio → File → Open → выбрать корень bluewave-mobile.

# 3) Указать SDK для Gradle
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 4) Сборка + тесты
./gradlew :app:compileDebugKotlin :app:ktlintCheck :app:testDebugUnitTest :app:assembleDebug

# 5) APK здесь:
ls -lh app/build/outputs/apk/debug/app-debug.apk

# 6) Поставить на телефон
brew install --cask android-platform-tools
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.bluewave_mobile/.MainActivity
```

---

## 11. Открытые вопросы (нужны ответы заказчика)

1. **APK transfer UX.** Системный bluetooth-share с подтверждением на
   обоих устройствах нас устраивает, или нужно полностью бесшумно (без
   диалогов) — что технически невозможно без Device Owner / MDM?
2. **Целевой Android.** `minSdk` сейчас 24 (Android 7). Реально ли
   поддерживать < 12? На старых устройствах нет
   `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`-permissions, и часть функций
   деградирует. Если не нужно — поднять до 31.
3. **Чужие устройства без приложения.** Ставим APK через Bluetooth-share
   (как описано в §2) — это всё, что нужно? Или планируем ещё
   web-fallback (QR-код с ссылкой на скачивание)?
4. **E2EE.** Нужна полноценная end-to-end (§3) или ограничимся
   plaintext-over-RFCOMM + at-rest (текущее поведение) на эту итерацию?
5. **Multi-device на одного пользователя.** Один человек = один MAC
   навсегда, или планируется аккаунт-привязка между несколькими
   устройствами одного владельца?
6. **Группы / каналы.** Нужны на следующих итерациях или это «1-на-1»
   мессенджер навсегда?

---

_Документ сгенерирован: май 2026, в рамках работы над PR #2._
_Все ссылки на коммиты — на ветке `devin/1778150491-handoff-finish`._
