# AutoDialer

Android app jo tumhari apni number list ko ek ke baad ek call karta hai, aur har call ka outcome tag karwata hai.
Backend: Google Apps Script + Google Sheet (login, plans, Razorpay payments). Koi server ya SMS gateway nahi chahiye.

## Kya kya hai (current version)
- **Calling engine**: thread-safe state machine (PENDING -> DIALING -> CONNECTED -> COMPLETED / ABORTED), ek number kabhi do baar dial nahi hota. 5000+ duplicate numbers bhi bina UI atke skip hote hain.
- **Background**: foreground service + wake lock, screen off me bhi chalta hai. Battery-optimization ka in-app flow (Xiaomi/Samsung/Oppo/Vivo/Huawei ke guide ke saath).
- **Crash recovery**: app kill ho ya phone restart, session wahi se wapas milta hai, kisi ko dobara call nahi jaati (write-ahead journal + atomic checkpoints).
- **Security**: sab local data AES-256-GCM se encrypted (Android Keystore), cloud backup band, minimal permissions, R8 on, logs me phone numbers mask.
- **Login**: Email + OTP (Gmail se free). Ek email ek waqt sirf ek device par. Dekho `OTP_SETUP.md`.
- **Payments**: Razorpay (server decides price). Dekho `RAZORPAY_SETUP.md`, `PAYMENT_AND_TRIAL_NOTES.md`.

## Setup (order me)
1. `backend/Code.gs` ko Apps Script me paste karo, Script Properties me `RAZORPAY_KEY_ID` aur `RAZORPAY_KEY_SECRET` daalo (keys kabhi app/GitHub me nahi).
2. `OTP_SETUP.md` ke steps: `testEmailOtp` run karke email permission do, phir New version deploy.
3. Code GitHub par push karo, Actions me APK build hota hai (`.github/workflows/build.yml`).
4. Phone par install karke `docs/ARCHITECTURE.md` ki "Needs a real device" list test karo.

## Tests
`tools/verify-all.sh /path/to/kotlinc/bin` — unit + architecture guards, engine harness (asli CallEngine/SessionStore/crypto JVM par), backend tests. Gradle me: `gradle testDebugUnitTest`.

## Docs
- `docs/ARCHITECTURE.md` — requirement -> code -> test matrix, device-par-verify list, threat model
- `docs/CHANGELOG.md` — ab tak ke sab badlav
- `docs/legacy/` — purane approaches (Firebase, Cashfree, purane release notes), sirf reference ke liye
