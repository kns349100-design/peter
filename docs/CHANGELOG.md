# Changelog (hardening rounds)

## Round 1-2 - unit tests, calling-engine fixes
- Fixed: `stop()` left a call stuck in CALLING; "091" phone format; lead label leaking the number; `advance()` moving the index during a call; `skip()` overwriting a finished lead.
- Thread-safe `CallSequencer` with real lifecycle phases; engine glue fixed (stale OFFHOOK flag, busy-phone state, skip during a call).
- Backend: trial needs a verified login, OTP flood no longer locks out customers, redeem brute-force lockout, order-theft fix (ownership from Razorpay notes), no account enumeration, prices aligned to Rs 9/299/999.

## Round 3 - ANR + modern telephony
- `DialResolver`: iterative, sliced skipping (no recursion; 500,000 skips on a 128 KB stack).
- `CallStateMonitor`: TelephonyCallback on its own executor (+ API 29-30 / 24-28 fallbacks), generation-numbered events.

## Round 4 - background + crash recovery
- `DialerService` (specialUse foreground service), `KeepAliveController` (wake lock with timeout, no leaks).
- `SessionStore` v2 atomic checkpoint + encrypted write-ahead `DialJournal` + `RecoveryReconciler`: nobody is called twice after a crash.

## Round 5 - battery flow, encryption, manifest
- Battery exemption at session start with cooldown + OEM guide.
- AES-256-GCM (Keystore) for session, journal, call logs.
- `allowBackup=false` + extraction rules, FileProvider limited to `exports/`, unused `READ_CALL_LOG` removed.

## Round 6 - everything integrated
- All key-value stores (login, license, drafts, outcomes) moved to encrypted `SecurePrefs` (+ migration).
- `PiiScrubber` / `SafeLog`, R8 rules, architecture/manifest/build guard tests, capstone system test.
- Fixed: SecurePrefs migration never persisted; PiiScrubber email regex was quadratic.

## Round 7 - email login
- Phone+SMS OTP replaced by Email+OTP (free, Gmail). One email = one device, alias-safe (`+tag`, Gmail dots), disposable domains blocked, quota-aware.
- Removed dead PIN login and the old Cashfree payment-link code.
