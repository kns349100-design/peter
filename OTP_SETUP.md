# Free OTP login by EMAIL (sent from your own Gmail through Google Apps Script)

App -> Apps Script `sendOtp` -> `MailApp.sendEmail` from the Gmail account that owns the script -> user's inbox.
Correct OTP = that device becomes the ONLY active device for the email (any other device is signed out within ~1 minute).
No SMS gateway, no phone to keep switched on, no extra Script Properties.

## One-time setup
1. Use a DEDICATED Gmail account for the project (it is the "From" address users see, e.g. `autodialer.login@gmail.com`).
2. Open the Apps Script project with that account. Paste the new `backend/Code.gs`, save.
3. In the editor choose the function `testEmailOtp`, put your own address inside it, press Run.
   Google asks you once to authorize "send email on your behalf" - allow it. Check the inbox (and Spam): the code 123456 arrives.
4. Deploy -> Manage deployments -> pencil -> Version: New version -> Deploy.  (The URL does not change.)
   If login says "Could not send the OTP email", re-run step 3 and redeploy: the web app uses the permission you granted there.
5. You can delete the old `SMSGATE_USER` / `SMSGATE_PASS` Script Properties and uninstall the SMS Gateway app from the phone.

## Limits / notes
- Gmail lets a script send only about 100 emails a day (Google Workspace about 1,500). The backend checks the real remaining
  quota, keeps the last 20 for people who already have an account, and allows at most 30 OTPs per ~6 hours to brand-new addresses.
- Per email: 60 s between OTPs, 5 per hour. Per device: 10 per hour. OTP valid 10 minutes, 5 wrong tries, one-time use.
- "One account = one inbox": `+tags` are ignored and Gmail dots are ignored (`r.a.hul+x@gmail.com` == `rahul@gmail.com`),
  so one person cannot farm free trials with aliases. A few throw-away mail domains are blocked (list is not complete).
- The first mail to a new user may land in Spam: tell users to check it. A dedicated sender address helps reputation.
- Email is free to create, so the free trial is weaker protection than with phone numbers. It is also limited once per
  DEVICE (server-side), which is what stops simple abuse.
- Users who were logged in with a phone number are asked to log in again with an email after updating the app.
- Plans are tied to the device (not the email): a user who moves to a new phone logs in there, but needs the plan restored by you.
