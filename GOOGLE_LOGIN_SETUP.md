# Sign in with Google (no password) - one-time setup

The app has a "Continue with Google" button. One tap, no password, no code. The verified Google email becomes the account
(same account as the email-code login). One account = one device at a time.

Free: Google does not charge for this. You only need to create two "OAuth client IDs" in Google Cloud Console.
Menu names in the console change sometimes (it is now called "Google Auth Platform"), but the steps are the same.

## Values you need
- Package name : com.autodialer.app
- SHA-1 of the app signing key (app/debug.keystore): 66:0D:31:BD:17:AB:2D:F1:51:AB:EB:ED:F1:34:A1:6B:9E:D6:C8:90
  (Do NOT replace app/debug.keystore - if the key changes, this SHA-1 changes and Google login stops working.)

## Steps
1. Open console.cloud.google.com with the Google account you use for this project. Pick the project "autodialer-44d89"
   (or create a new project).
2. Consent screen: APIs & Services -> OAuth consent screen (or "Google Auth Platform"). User type: External.
   App name: CallConnect, support email: yours. Save.
   IMPORTANT: set Publishing status to "In production" (Publish app / Audience -> Publish). While it says "Testing"
   only the test users you add can sign in. Only the basic email/profile permission is used, so no Google review is needed.
3. Credentials -> Create credentials -> OAuth client ID -> Application type: ANDROID
   Name: AutoDialer Android | Package name: com.autodialer.app | SHA-1: (the value above). Create.
4. Credentials -> Create credentials -> OAuth client ID -> Application type: WEB APPLICATION
   Name: AutoDialer Server. (No redirect URL needed.) Create, then COPY the "Client ID"
   (it looks like 1234567890-abcdef.apps.googleusercontent.com).
5. Put that Web Client ID in TWO places:
   a) GitHub: app/src/main/java/com/autodialer/app/GoogleConfig.kt -> replace PASTE_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
   b) Apps Script -> Project Settings -> Script Properties -> add  GOOGLE_WEB_CLIENT_ID = same Client ID
6. Apps Script: paste backend/Code.gs, Save, then Deploy -> Manage deployments -> pencil -> New version -> Deploy.
7. Build the APK, install it, tap "Continue with Google".

## If it does not work
- "Google sign-in failed: ... [28444] / 10 / developer console is not set up correctly":
  the SHA-1 or package name in the Android client is wrong, or Google has not picked it up yet (wait 10-30 minutes), or the
  Web Client ID in GoogleConfig.kt is not the "Web application" one.
- "No Google account found on this phone": add a Google account in phone Settings -> Accounts.
- "Google sign-in was rejected for this app": GOOGLE_WEB_CLIENT_ID in Apps Script is different from the one in GoogleConfig.kt.
- "Google sign-in is not set up on the server yet": GOOGLE_WEB_CLIENT_ID is missing in Script Properties, or no new version was deployed.
- Tester not able to sign in but you can: the consent screen is still in "Testing" - publish it (step 2).
