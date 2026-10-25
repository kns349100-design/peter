# Login / sign up: Google or Email (both free, no password to remember)

Two ways in, both on one screen:
1. "Continue with Google" - one tap, no password, no code (setup: GOOGLE_LOGIN_SETUP.md).
2. Email + 6-digit code - the code is emailed from your own Gmail by the Apps Script. Free, nothing else to set up.

A new email is signed up automatically; an existing one just logs in.
ONE ACCOUNT = ONE DEVICE: logging in on a new phone logs the old phone out within ~1 minute.
(Mobile-number login was removed on purpose.)

## EMAIL login - one-time setup
1. Paste backend/Code.gs into Apps Script (project "AutoDialer License Backend") and press Save.
2. In the editor pick the function `testEmail` (function list at the top) and press Run.
   Google asks to allow the script to send email: Review permissions -> choose your account -> Advanced ->
   Go to project (unsafe) -> Allow. You should receive a test email.
3. Deploy -> Manage deployments -> pencil -> Version: New version -> Deploy (do NOT use "New deployment").
4. Install the new app and log in with an email. The code arrives from your Gmail address (check Spam the first time).
Limit: a normal @gmail.com account can send about 100 emails a day (the script stops at 40 per 6 hours to stay safe).
Google sign-in has no such limit, so most users should use it.

## Safety built in
60 s between codes per email, 5 per email per hour, 10 per device per hour, 5 wrong tries per code, code valid 10 minutes,
single use. The old PIN login is switched off on the server (it would let anyone skip the code).
Google tokens are checked by Google itself and must be issued for THIS app.
