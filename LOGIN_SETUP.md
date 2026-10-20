# Login / sign up with MOBILE NUMBER or EMAIL (free)

One box: the user types a mobile number OR an email. A 6-digit code is sent, they type it, done.
A new number/email is signed up automatically; an existing one just logs in.
ONE ACCOUNT = ONE DEVICE: logging in on a new phone logs the old phone out within ~1 minute.

## EMAIL login - completely free, no extra phone/app (do this first)
1. Paste backend/Code.gs into Apps Script (project "AutoDialer License Backend") and press Save.
2. In the editor pick the function `testEmail` (function list at the top) and press Run.
   Google asks to allow the script to send email: Review permissions -> choose your account -> Advanced -> Go to project -> Allow.
   You should receive a test email.
3. Deploy -> Manage deployments -> pencil -> Version: New version -> Deploy (do NOT use "New deployment").
4. Install the new app and log in with an email. The code arrives from your Gmail address (check Spam the first time).
Limit: a normal @gmail.com account can send about 100 emails a day (the script stops at 40 per 6 hours to stay safe).

## MOBILE-number login - optional (SMS from your own SIM)
Needs the free "SMS Gateway for Android" app on a phone that stays on with internet (see steps below).
If you skip this, mobile numbers show "Mobile OTP is not set up yet - please log in with your email instead" and email still works.
1. Install the APK from github.com/capcom6/android-sms-gateway/releases, allow SMS permission.
2. Turn on "Cloud Server" -> Online, note the Username and Password.
3. Apps Script -> Project Settings -> Script Properties: SMSGATE_USER and SMSGATE_PASS.
4. Test with the function `testSmsGateway` (put your number in first).
A SIM usually allows ~100 SMS a day (the script stops at 30 per 6 hours).

## Safety built in
60 s between codes per account, 5 per account per hour, 10 per device per hour, 5 wrong tries per code, code valid 10 minutes, single use.
The old PIN login is switched off on the server (it would let anyone skip the code).
