# AutoDialer - production architecture & verification matrix

Run everything: `tools/verify-all.sh /path/to/kotlinc/bin` (JVM only). Gradle runs the same unit tests via `gradle testDebugUnitTest`.

| # | Requirement | Where | Proven by |
|---|---|---|---|
| 1 | Lifecycle state machine PENDING->DIALING->CONNECTED->COMPLETED / ABORTED, one lock, zero double-dial | `CallSequencer`, `CallEngine` | `CallSequencerTest`, `CallSequencerConcurrencyTest` (1000-lead panic storm), engine harness chaos tests, `UnifiedSystemHarnessTest` |
| 2 | Iterative skip resolution, 5000+ skips, O(1) stack, UI time-slicing (`handler.post`, 500/slice) | `DialResolver`, `CallEngine.dial` | `DialResolverTest` (500,000 skips on 128 KB stack), harness 5000/50000 on small stacks |
| 3 | Android 12+ `TelephonyCallback` on its own executor, fallbacks (API 29-30 / 24-28), generations drop stale events | `CallStateMonitor`, `CallStateTracker` | `CallStateTrackerTest`, harness (events really arrive on the `call-state` thread) |
| 4 | Foreground service (specialUse) + PARTIAL_WAKE_LOCK with timeout + release hooks; OEM battery flow with cooldown | `DialerService`, `KeepAliveController`, `AndroidKeepAlive`, `BatteryExemption*` | `KeepAliveControllerTest` (8-thread storm), harness leak tests, `BatteryExemptionAdvisorTest` |
| 5 | Atomic checkpoint + write-ahead journal + reconciler, never re-dial | `DurableFile`, `SessionStore`, `DialJournal`, `RecoveryReconciler` | `DurableFileTest`, `DialJournalTest`, `RecoveryReconcilerTest` (5000 histories + control), `ProcessDeathHarnessTest` (25 random-death seeds) |
| 6a | AES-256-GCM (Keystore key, random IV, AAD, per-line journal) on session, journal, call logs **and** all key-value stores (login, license, drafts, outcomes) | `DataCipher`, `AndroidCrypto`, `SecurePrefs`, `FileStore` | `AesGcmCipherTest`, `SecureFileTest`, `DialJournalCryptoTest`, `EncryptionHarnessTest`, `SecurePrefsHarnessTest` |
| 6b | Manifest: `allowBackup=false`, extraction rules, FileProvider = `exports/` only, no unused permissions | `AndroidManifest.xml`, `res/xml/*` | `ManifestHardeningTest` |
| 6c | PII scrubber + only-SafeLog logging; R8 `minifyEnabled true` + rules | `PiiScrubber`, `SafeLog`, `proguard-rules.pro`, `build.gradle` | `PiiScrubberTest` (linear-time on adversarial input), `ArchitectureGuardTest`, `BuildHardeningTest` |

## Guards that keep it this way
`ArchitectureGuardTest` fails the build if anyone: logs outside `SafeLog`, adds a plaintext `getSharedPreferences`, uses a cipher other than AES/GCM, writes files outside the crash-safe helpers, dials without the journal write first, or re-introduces `dial()` recursion.

## Needs a real device before release (cannot be proven on a JVM)
1. **R8**: first `assembleRelease` must be smoke-tested: login, a payment (Razorpay), a dial, restore after "kill app". Upload `mapping.txt` with each release.
2. **Keystore**: key creation on StrongBox/TEE phones, behaviour after OEM updates.
3. **Foreground service**: Play Console "special use" declaration (text is in the manifest property); Android 14/15 start restrictions.
4. **OEM settings screens** (Xiaomi/Samsung/Oppo/Vivo/Huawei): component names change per ROM; they are resolved at runtime with a fallback to the app-settings page.
5. **Play policy** for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; fallback to the settings list is already coded.
6. **Real OOM / SIGKILL timing**: the JVM suite emulates death by losing queued writes, not by killing a process.

## Threat model (honest)
Encryption protects data at rest: file explorers, `adb pull`, cloud/device backup, lost phone. It does not stop an attacker who runs code inside the app's own process on a rooted phone.
