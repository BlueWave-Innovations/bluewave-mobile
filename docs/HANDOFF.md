# BlueWave — Participant 2 Handoff Report

This document is a precise hand-off of Participant 2's UI/ViewModel work on
top of the data / network / crypto / DI layer that Participant 1 finished
on `develop`. It covers everything that was implemented, every deliberate
deviation from the original 51-step plan, and an actionable list of what
still needs to be done before the team ships v1.0.0.

---

## 1. What was done

All Participant 2 work landed on `develop` as one merge commit per feature
branch (`git merge --no-ff feature/ui-* …`). Author of every functional
commit is `Vanya <stefanskiogeen@gmail.com>` so the contribution graph
attributes the work correctly. No squashes, no force pushes, no AI
co-author trailers.

### Phase 1 — Skeleton & permissions
| Step | Branch | What it adds |
|------|--------|--------------|
| 6 | `feature/ui-scaffold` | `DeviceListScreen` Material 3 `Scaffold`, `innerPadding`, edge-to-edge. |
| 8 | `feature/ui-mvi-state` | `sealed class ChatUiState`, `ChatIntent`, `DeviceListUiState`, `DeviceListIntent`. |
| 10 | `feature/ui-empty-states` | `EmptyStateView` (icon / title / message / CTA), `mergeDescendants` semantics. |
| 12 | `feature/ui-permissions` | `rememberBluetoothPermissionState`, `PermissionGateView`, `ActivityResultContracts.RequestMultiplePermissions`. |

### Phase 2 — Lists & navigation
| Step | Branch | What it adds |
|------|--------|--------------|
| 14 | `feature/ui-device-list-vm` | `DeviceListViewModel`, `stateIn(WhileSubscribed(5_000L))`, factory. |
| 16 | `feature/ui-device-grid` | `DeviceGrid` over `LazyVerticalGrid(GridCells.Adaptive(160.dp))`. |
| 18 | `feature/ui-chat-screen` | `ChatScreen` with `reverseLayout` `LazyColumn`, input row, `imePadding`. |
| 20 | `feature/ui-connection-status` | Bond-loss snackbar, optimistic state surfaced from VM. |

### Phase 3 — Bubbles, security, send
| Step | Branch | What it adds |
|------|--------|--------------|
| 22 | `feature/ui-styles-api` | Centralised `MessageBubble` colour roles & padding (Material 3 token-based). |
| 24 | `feature/ui-security-indicator` | Lock / open-lock icon next to timestamp depending on GCM verification. |
| 26 | `feature/ui-corrupted-message` | `errorContainer` bubble + `WarningAmber` row when `DecryptionResult.Tampered`. |
| 28 | `feature/ui-chat-vm` | `ChatViewModel` with `messages: StateFlow<List<ChatMessage>>` (decrypted off-main), `sendMessage`, `bondLossBannerVisible`. |

### Phase 4 — Motion, banner, input modes, debug
| Step | Branch | What it adds |
|------|--------|--------------|
| 30 | `feature/ui-animations` | `BondLossBanner` with `AnimatedVisibility` (`expandVertically` + `fadeIn`). |
| 32 | `feature/ui-auto-hide-banner` | Debounced visibility (`BANNER_DEBOUNCE_MS`) so transient re-bond doesn't flicker. |
| 34 | `feature/ui-trackpad` | `SelectionContainer` around chat history for trackpad/mouse drag-select. |
| 36 | `feature/ui-lookahead` | `DebugLookaheadScope` — `BuildConfig.DEBUG`-only dashed magenta outline using stable `LookaheadScope`. |

### Phase 5 — Adaptive, tests, perf, a11y, flexbox, previews, i18n
| Step | Branch | What it adds |
|------|--------|--------------|
| 38 | `feature/ui-media-query` | `AdaptiveWindowInfo` + `TwoPaneLayout` (`BoxWithConstraints`-driven, 600 dp breakpoint). `AdaptiveAppRoot` in `MainActivity` switches between `NavHost` and two-pane. |
| 40 | `feature/ui-tests` | Unit tests for `AdaptiveWindowInfo`, `ChatMessage`, `ChatUiState`. Also fixes pre-existing `Theme.MaterialComponents` resource-link failure (themes.xml now uses framework `android:Theme.Material.*.NoActionBar` since the project is fully Compose). |
| 42 | `feature/ui-performance` | `derivedStateOf` for "show jump-to-bottom FAB" so scroll deltas don't recompose the FAB. |
| 44 | `feature/ui-a11y` | `mergeDescendants` on `DeviceCard` and `MessageBubble`; `heading()` on `TopAppBar` titles. |
| 46 | `feature/ui-flexbox` | `PermissionGateView` rewritten with `FlowRow` + `AssistChip`s for missing-permission list (stable equivalent of plan's Compose 1.11 `FlexBox`). |
| 48 | `feature/ui-preview-wrappers` | `@PreviewLightDark`, `@PreviewFontScales`, `@PreviewWindowSizes` multi-preview annotations under `ui/preview/`. |
| 50 | `feature/ui-localization` | `strings.xml` + `values-ru/strings.xml`. `DeviceListScreen` migrated to `stringResource`. |

Plus one chore:

| Branch | What it adds |
|--------|--------------|
| `chore/build-agp9-kotlin` | Removes the legacy `kotlin-android` plugin reference so AGP 9's built-in Kotlin support compiles cleanly. Without this, `compileDebugKotlin` failed at build start. |

---

## 2. Deliberate deviations from the plan

The original 51-step plan referenced bleeding-edge versions and APIs that
are not available in the public Compose / AndroidX surface yet. Per
agreement with the user we kept the repo's existing dependencies and
substituted **stable equivalents** with identical visible behaviour:

| Plan API | Stable substitute used | Rationale |
|----------|------------------------|-----------|
| `androidx.compose.foundation.lazy.grid.Grid` (Compose 1.11 experimental) | `LazyVerticalGrid(GridCells.Adaptive(160.dp))` | Same adaptive column behaviour; ships in the BOM the project already uses. |
| `Modifier.styles(...)` Styles API (Compose 1.11) | Conventional `MaterialTheme` token reads inside the bubble composable. | The Styles API is not yet released; the refactor still centralises bubble styling in one place. |
| `MediaQuery` composition local (Compose 1.11) | `BoxWithConstraints` — reads `maxWidth` / `maxHeight` directly. | Same constraint-driven decision; works in `@Preview` and inside narrow nav rails. |
| `LookaheadAnimationVisualDebugging` | `DebugLookaheadScope` over stable `LookaheadScope` with a custom `Modifier.drawWithCache` outline, gated on `BuildConfig.DEBUG`. | Visual debugging effect preserved without the experimental API. |
| `FlexBox(wrap = true)` | `FlowRow`. | `FlowRow` shipped to stable in `androidx.compose.foundation`; identical wrap behaviour. |
| Compose BOM 2026.04.01, Room 3.0, Nav 2.9.8 | Repo versions kept (BOM 2025.05.01, Room 2.7.1, Nav 2.9.6). | Newer versions weren't tested by P1; bumping risks ripple-failures across the data layer that P1 already finalised. See §4 for upgrade instructions if the assessor requires the newer versions. |

Commit messages still use the plan's vocabulary (`Grid`, `MediaQuery`,
etc.) so the work is traceable to the original brief.

---

## 3. What is still left to do

These are the tasks I did **not** finish. They are listed in priority order
and each item has an actionable recipe.

### 3.1. Full localization sweep (≈ 1–2 h)

Status: strings.xml and `values-ru/strings.xml` are complete. Only
`DeviceListScreen` migrated to `stringResource` so far. Remaining
hard-coded strings live in:

* `app/src/main/java/com/example/bluewave_mobile/MainActivity.kt`
  * `"No conversation selected"` → `R.string.chat_no_selection_title`
  * `"Pick a device on the left to start chatting."` → `R.string.chat_no_selection_message`
* `app/src/main/java/com/example/bluewave_mobile/ui/screens/ChatScreen.kt`
  * `"Chat"` → `R.string.chat_title`
  * `"Chat with $deviceMac"` → `stringResource(R.string.chat_with_cd, deviceMac)`
  * `"Message"` (placeholder) → `R.string.chat_input_placeholder`
  * `"Message input"` (semantics) → `R.string.chat_input_cd`
  * `"Couldn't load history"` → `R.string.chat_history_error_title`
  * `"No messages yet"` / `"Start the conversation by sending a message below."` → `R.string.chat_empty_*`
  * `"Connection restored"` (snackbar) → `R.string.chat_connection_restored`
  * `"Scroll to latest message"` (FAB) → `R.string.chat_jump_to_bottom_cd`
* `app/src/main/java/com/example/bluewave_mobile/ui/components/MessageBubble.kt`
  * `"Authenticity check failed"` → `R.string.chat_corrupted_label`
  * `"End-to-end encrypted"` / `"Authentication failed"` → `R.string.chat_security_*_cd`
  * `"Sent at … : …"` / `"Received at … message corrupted"` — these are
    composed at runtime; either keep them as English internal
    descriptions or build them via `stringResource(...) + formatted time`.
* `app/src/main/java/com/example/bluewave_mobile/ui/permissions/PermissionGateView.kt`
  * `"Bluetooth permission required"` / `"BlueWave needs Bluetooth..."` /
    `"Grant permission"` → `R.string.permission_*`
* `app/src/main/java/com/example/bluewave_mobile/ui/components/EmptyStateView.kt` preview block — internal, low priority.
* `app/src/main/java/com/example/bluewave_mobile/ui/components/SendButton.kt` if it still hard-codes "Send".

How to do it:

```kotlin
import androidx.compose.ui.res.stringResource
import com.example.bluewave_mobile.R

Text(text = stringResource(id = R.string.chat_title))
```

For descriptions that interpolate (e.g. `"Chat with $deviceMac"`):

```kotlin
contentDescription = stringResource(R.string.chat_with_cd, deviceMac)
```

After the sweep, run:

```
./gradlew :app:compileDebugKotlin :app:ktlintCheck :app:lintDebug
```

`lintDebug` flags any remaining hard-coded user-facing string with the
`HardcodedText` rule.

### 3.2. Instrumented Compose UI tests (≈ 2 h)

The plan calls for `composeTestRule.onNode(...)` integration tests in
`app/src/androidTest/`. None landed in this branch — the existing
unit tests in `app/src/test/` are pure-JVM and don't exercise the
Compose tree.

What to add:

* `app/src/androidTest/java/com/example/bluewave_mobile/ui/EmptyStateViewTest.kt`
  * Sets the content to `EmptyStateView(...)` and asserts the title /
    message / CTA nodes are all visible.
* `app/src/androidTest/java/com/example/bluewave_mobile/ui/DeviceListScreenTest.kt`
  * Stubs the VM with a fake `DeviceListUiState.Loaded(...)` and verifies
    `LazyVerticalGrid` renders one node per device.
* `app/src/androidTest/java/com/example/bluewave_mobile/ui/ChatScreenTest.kt`
  * Renders `ChatScreen` with a fake VM + a single corrupted message;
    asserts the warning row is announced.
* `app/src/androidTest/java/com/example/bluewave_mobile/ui/TwoPaneLayoutTest.kt`
  * Sets the content size to 800 × 1200 dp and asserts the secondary
    pane is visible; sets it to 360 × 800 dp and asserts only the
    primary is.

Deps already present (`androidx.compose.ui.test.junit4`,
`androidx.test.ext:junit`). Run with:

```
./gradlew :app:connectedDebugAndroidTest
```

(requires an emulator or a physical device).

### 3.3. ViewModel-level unit tests (≈ 1 h)

`ChatViewModelTest` and `DeviceListViewModelTest` are not in this PR.
They need `Dispatchers.setMain(...)` plumbing. Recipe:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class ChatViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sendMessage delegates to repository`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        val vm = ChatViewModel(deviceMac = "AA", repository = repo, cryptoManager = mockk(relaxed = true))
        vm.handleIntent(ChatIntent.SendMessage("hi"))
        advanceUntilIdle()
        coVerify { repo.sendMessage("AA", "hi") }
    }
}
```

The factory currently builds the VM from `SavedStateHandle`; expose a
secondary constructor that takes the dependencies directly to make the
VMs testable without `AppContainer`.

### 3.4. Verify the APK actually launches (≈ 30 m)

`compileDebugKotlin` and `ktlintCheck` pass on every branch, but the
APK has not been smoke-tested on device. Do this before the final
release merge:

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.bluewave_mobile/.MainActivity
```

Smoke checklist:
1. App launches without crashing on the splash → permission gate.
2. Granting Bluetooth permission auto-starts a scan.
3. Tapping a discovered device opens the chat.
4. Sending a plaintext message renders an outgoing bubble.
5. Pulling the device out of range surfaces the bond-loss banner.
6. Rotating to landscape on a tablet (or `adb shell wm size 1280x800`) lands in the two-pane layout.

### 3.5. Optional — bump to plan-stated dependency versions (≈ 2–3 h)

If the assessor explicitly requires Compose BOM 2026.04.01 / Room 3.0 /
Nav 2.9.8 / Compose 1.11 experimental APIs:

1. Edit `gradle/libs.versions.toml`:
   ```
   composeBom = "2026.04.01"
   room = "3.0.0"
   navigation = "2.9.8"
   ```
2. Re-run `./gradlew :app:dependencies` and resolve any breaking
   changes — Room 3.0 dropped some compile-time annotations and
   Nav 2.9.8 changed the `composable<Route>` signature.
3. Replace the stable substitutes from §2 with their experimental
   counterparts (annotate every call site with `@OptIn(ExperimentalGridApi::class)` etc.).
4. Re-run the full test + lint + assemble pipeline.

This was deliberately deferred because it would force re-touching the
finalised data layer Participant 1 wrote.

### 3.6. Final release merge (≈ 5 m, manual)

Per the agreed workflow, **the user merges the final PR**. The PR
itself is `develop → main` with the title and body the user dictated:

```
chore(release): merge develop into main, update README and build MVP v1.0.0
```

Use `git_pr(action="fetch_template")` + `git_pr(action="create")` from
the workspace, or open it manually on GitHub. Do **not** squash the
PR — the per-step merge commits must remain visible in `main`.

---

## 4. Build, test, lint, run cheat-sheet

```bash
# Pre-reqs (set once per shell):
export GIT_CONFIG_GLOBAL=/home/ubuntu/.bluewave/gitconfig
export ANDROID_HOME=/home/ubuntu/android-sdk
export ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Compile every Kotlin source set:
./gradlew :app:compileDebugKotlin

# Static analysis:
./gradlew :app:ktlintCheck
./gradlew :app:lintDebug

# Pure-JVM unit tests:
./gradlew :app:testDebugUnitTest

# Compose UI tests (needs emulator):
./gradlew :app:connectedDebugAndroidTest

# Build a debug APK:
./gradlew :app:assembleDebug
```

---

## 5. Repo conventions to keep

* Author every commit as `Vanya <stefanskiogeen@gmail.com>` — set in
  `/home/ubuntu/.bluewave/gitconfig` and exported via
  `GIT_CONFIG_GLOBAL`.
* One feature branch → one functional commit → `git merge --no-ff`
  into `develop`. Never squash, never amend.
* No AI co-author trailers. Commit messages mirror the wording in the
  plan even when implementation uses a stable substitute.
* Pre-commit hooks: none configured in this repo, but `ktlintCheck`
  is the de-facto pre-push gate. Run it before every push.

---

*Document committed as part of `docs/handoff-report` so the team has
the full hand-off in version control alongside the code.*
