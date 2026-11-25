// Integration test for backend/Code.gs - runs the REAL script against an in-memory fake of Google Sheets.
// Run: node backend/tests/integration.test.js
const fs = require('fs'), vm = require('vm'), crypto = require('crypto'), path = require('path');
let pass = 0, fail = 0;
function ok(cond, name) { if (cond) { pass++; console.log('  ok   ' + name); } else { fail++; console.log('  FAIL ' + name); } }

function makeEnv() {
  const sheets = {};
  const escaped = [];
  // Real Sheets treats a leading apostrophe as the "store as literal text" marker and does not keep it.
  const unq = (v) => { if (typeof v === 'string' && v[0] === "'") { escaped.push(v.slice(1)); return v.slice(1); } return v; };
  const mkSheet = (name) => {
    const rows = [];
    const s = {
      name, rows,
      getDataRange: () => ({ getValues: () => rows.map(r => r.slice()) }),
      appendRow: (r) => { rows.push(r.map(unq)); },
      getLastRow: () => rows.length,
      getRange: (r, c, nr = 1, nc = 1) => ({
        getValue: () => (rows[r - 1] || [])[c - 1] === undefined ? '' : rows[r - 1][c - 1],
        getValues: () => { const o = []; for (let i = 0; i < nr; i++) { const row = []; for (let j = 0; j < nc; j++) { const v = (rows[r - 1 + i] || [])[c - 1 + j]; row.push(v === undefined ? '' : v); } o.push(row); } return o; },
        setValue: (v) => { while (rows.length < r) rows.push([]); rows[r - 1][c - 1] = unq(v); },
        setValues: (vs) => { vs.forEach((vr, i) => { while (rows.length < r + i) rows.push([]); vr.forEach((v, j) => { rows[r - 1 + i][c - 1 + j] = unq(v); }); }); }
      })
    };
    return s;
  };
  ['Codes', 'Activations', 'Users'].forEach(n => { sheets[n] = mkSheet(n); });
  sheets.Codes.rows.push(['Code', 'Type', 'MaxUses', 'UsedCount', 'Active']);
  sheets.Activations.rows.push(['DeviceId', 'Code', 'ActivatedAt', 'ExpiryAt', 'PlanType', 'Revoked']);
  sheets.Users.rows.push(['Phone', 'PinHash', 'ActiveDeviceId', 'UpdatedAt', 'FailedAttempts', 'LockedUntil', 'TrialUsed', 'TrialStartedAt']);
  const cacheStore = {}, props = { RAZORPAY_KEY_ID: 'rzp_test_x', RAZORPAY_KEY_SECRET: 'sec' };
  const env = { escaped, sheets, sentMails: [], mailQuota: 100, failMail: false, razorOrders: {}, razorCalls: [], fakeNow: null };
  env.ctx = {
    console, Date, Math, JSON, String, Number, Array, Object, RegExp, Error, parseInt, parseFloat, isNaN, encodeURIComponent, decodeURIComponent,
    SpreadsheetApp: { getActiveSpreadsheet: () => ({ getSheetByName: (n) => sheets[n] || null, insertSheet: (n) => (sheets[n] = mkSheet(n)) }) },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    PropertiesService: { getScriptProperties: () => ({ getProperty: (k) => props[k] === undefined ? null : props[k] }) },
    CacheService: { getScriptCache: () => ({ get: (k) => (k in cacheStore ? cacheStore[k] : null), put: (k, v) => { cacheStore[k] = v; }, remove: (k) => { delete cacheStore[k]; } }) },
    Utilities: {
      DigestAlgorithm: { SHA_256: 'sha256' },
      computeDigest: (a, s) => Array.from(crypto.createHash('sha256').update(String(s)).digest()).map(b => b > 127 ? b - 256 : b),
      base64Encode: (s) => Buffer.from(s).toString('base64'),
      getUuid: () => crypto.randomUUID()
    },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: (t) => ({ t, setMimeType() { return this; }, getContent() { return this.t; } }) },
    Logger: { log() {} },
    MailApp: {
      sendEmail: (o) => { if (env.failMail) throw new Error('mail down'); env.sentMails.push(o); env.mailQuota--; },
      getRemainingDailyQuota: () => env.mailQuota
    },
    UrlFetchApp: {
      fetch: (url, opt) => {
        env.razorCalls.push(url);
        if (url === 'https://api.razorpay.com/v1/orders') {
          const p = JSON.parse(opt.payload); const id = 'order_' + crypto.randomBytes(6).toString('hex');
          env.razorOrders[id] = { id, amount: p.amount, currency: 'INR', status: 'created', amount_paid: 0, notes: p.notes };
          return { getResponseCode: () => 200, getContentText: () => JSON.stringify(env.razorOrders[id]) };
        }
        const m = url.match(/orders\/(order_\w+)/);
        const o = m && env.razorOrders[m[1]];
        return { getResponseCode: () => (o ? 200 : 400), getContentText: () => JSON.stringify(o || {}) };
      }
    }
  };
  vm.createContext(env.ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), env.ctx);
  env.call = (params) => JSON.parse(env.ctx.doGet({ parameter: params }).getContent());
  return env;
}

const D1 = 'dev-1', D2 = 'dev-2', EM = 'rahul@example.com';
function lastOtp(env) { const m = env.sentMails[env.sentMails.length - 1]; return m.body.match(/(\d{6})/)[1]; }
function login(e, email, dev) {
  e.ctx.CacheService.getScriptCache().remove('otp_' + e.ctx.normalizeEmail(email).key);
  e.call({ action: 'sendOtp', email, deviceId: dev });
  return e.call({ action: 'verifyOtp', email, otp: lastOtp(e), deviceId: dev });
}

console.log('Redeem codes');
{
  const e = makeEnv();
  e.sheets.Codes.rows.push(['SAP-10', 'HOURLY12', 1, 0, true], ['OFF', 'MONTHLY', 0, 0, false], ['MONTH', 'MONTHLY', 0, 0, 'TRUE']);
  ok(e.call({ action: 'redeem', code: 'bogus', deviceId: D1 }).status === 'error', 'invalid code rejected');
  ok(e.call({ action: 'redeem', code: 'off', deviceId: D1 }).message.includes('disabled'), 'disabled code rejected');
  const r = e.call({ action: 'redeem', code: 'sap-10', deviceId: D1 });
  ok(r.status === 'ok' && r.planType === 'HOURLY12', 'valid code redeems (case-insensitive)');
  ok(e.call({ action: 'redeem', code: 'SAP-10', deviceId: D2 }).status === 'error', 'max-uses limit enforced');
  const c = e.call({ action: 'check', deviceId: D1 });
  ok(c.status === 'ok' && c.expiryAt > Date.now(), 'check shows active plan after redeem');
  ok(e.call({ action: 'check', deviceId: 'nobody' }).status === 'not_found', 'check unknown device -> not_found');
  e.call({ action: 'redeem', code: 'MONTH', deviceId: '=EVIL()' });
  ok(e.escaped.includes('=EVIL()'), 'formula-looking deviceId is written with the text-escape marker');
  ok(e.call({ action: 'nonsense' }).message === 'unknown action', 'unknown action handled');
  ok(e.ctx.doGet({ parameter: {} }).getContent().includes('unknown'), 'doGet with no action handled');
}

console.log('Email normalization');
{
  const e = makeEnv(); const n = (x) => e.ctx.normalizeEmail(x);
  ok(n('Rahul@Example.COM').key === 'rahul@example.com', 'case-insensitive');
  ok(n('  rahul@example.com ').key === 'rahul@example.com', 'whitespace trimmed');
  ok(n('rahul+promo@example.com').key === 'rahul@example.com', '+tag removed (same inbox)');
  ok(n('r.a.h.u.l@gmail.com').key === 'rahul@gmail.com', 'gmail dots ignored');
  ok(n('rahul@googlemail.com').key === 'rahul@gmail.com', 'googlemail == gmail');
  ok(n('r.ahul@example.com').key === 'r.ahul@example.com', 'dots kept on non-gmail domains');
  ok(n('r.a.h.u.l+x@gmail.com').display === 'r.a.h.u.l+x@gmail.com', 'OTP is mailed to the address as typed');
  for (const bad of ['', 'rahul', 'rahul@', '@example.com', 'rahul@example', 'a b@example.com', 'rahul@@example.com', 'rahul@exa mple.com', '.rahul@example.com', 'rahul.@example.com', 'ra..hul@example.com', 'rahul@-example.com', 'rahul@example-.com', 'rahul@example.c', '+tag@example.com', 'rahul@example.com\nBcc: x@y.com', '<script>@example.com', "x'--@example.com"]) ok(n(bad) === null, 'rejected: ' + JSON.stringify(bad));
  ok(n('a'.repeat(65) + '@example.com') === null, 'local part over 64 chars rejected');
  ok(n('a@' + 'b'.repeat(250) + '.com') === null, 'over 254 chars rejected');
  const t0 = Date.now(); for (const adv of ['a'.repeat(300) + '@', 'a@' + 'b.'.repeat(120), 'a@' + '-'.repeat(240) + '.com', ('a.'.repeat(130)) + '@x.com']) n(adv);
  ok(Date.now() - t0 < 200, 'adversarial inputs are fast (no regex blow-up)');
}

console.log('Email OTP login');
{
  const e = makeEnv();
  ok(e.call({ action: 'sendOtp', email: 'nope', deviceId: D1 }).status === 'error', 'invalid email rejected');
  ok(e.call({ action: 'sendOtp', phone: '+919075034748', deviceId: D1 }).message.includes('update the app'), 'old app (phone) is told to update');
  ok(e.call({ action: 'sendOtp', email: 'x@mailinator.com', deviceId: D1 }).message.includes('permanent'), 'disposable mail domain rejected');
  const s = e.call({ action: 'sendOtp', email: EM, deviceId: D1 });
  ok(s.status === 'ok', 'sendOtp ok'); ok(e.sentMails.length === 1 && e.sentMails[0].to === EM, 'OTP is mailed to the typed address');
  ok(/AutoDialer/.test(e.sentMails[0].subject) && e.sentMails[0].noReply === true, 'subject + noReply set');
  ok(!/@|http|password|secret/i.test(e.sentMails[0].body), 'mail body carries no addresses, links or secrets');
  ok(e.call({ action: 'sendOtp', email: EM, deviceId: D1 }).message.includes('wait'), 'resend cooldown enforced');
  ok(e.call({ action: 'verifyOtp', email: EM, otp: '000000', deviceId: D1 }).message.includes('Wrong OTP'), 'wrong OTP rejected');
  const v = e.call({ action: 'verifyOtp', email: EM, otp: lastOtp(e), deviceId: D1 });
  ok(v.status === 'ok' && v.registered === true, 'correct OTP registers new account');
  ok(e.call({ action: 'verifyOtp', email: EM, otp: lastOtp(e), deviceId: D1 }).status === 'error', 'OTP is single-use');
  ok(e.call({ action: 'checkSession', email: EM, deviceId: D1 }).active === true, 'session active on device 1');
  ok(e.call({ action: 'checkSession', email: 'RAHUL+x@Example.com', deviceId: D1 }).active === true, 'same inbox, other spelling = same account');

  // ONE EMAIL = ONE DEVICE
  const v2 = login(e, EM, D2);
  ok(v2.status === 'ok' && v2.registered === false, 'second login is not a new registration');
  ok(e.call({ action: 'checkSession', email: EM, deviceId: D1 }).active === false, 'ONE DEVICE: the old device is signed out');
  ok(e.call({ action: 'checkSession', email: EM, deviceId: D2 }).active === true, 'ONE DEVICE: the new device is the active one');
  login(e, 'Rahul+z@example.com', 'dev-3');
  ok(e.call({ action: 'checkSession', email: EM, deviceId: D2 }).active === false, 'an alias of the same inbox logging in elsewhere also takes over (same account)');
  ok(e.sheets.Users.rows.length === 2, 'users sheet has exactly one row for the account');
  ok(e.call({ action: 'checkSession', email: 'nobody@example.com', deviceId: D1 }).status === 'not_found', 'unknown account -> not_found');
  const old = e.call({ action: 'checkSession', phone: '+919075034748', deviceId: D1 }); ok(old.status === 'error', 'phone-style checkSession refused');

  // brute force
  e.ctx.CacheService.getScriptCache().remove('otp_rahul@example.com');
  e.call({ action: 'sendOtp', email: EM, deviceId: 'dev-3' });
  let last; for (let i = 0; i < 10; i++) last = e.call({ action: 'verifyOtp', email: EM, otp: '111111', deviceId: 'dev-3' });
  ok(last.message.includes('expired') || last.message.includes('Too many') || last.message.includes('request a new'), 'OTP burned after too many wrong tries');
  // a code for one account cannot be used on another
  const e2 = makeEnv(); e2.call({ action: 'sendOtp', email: 'a@example.com', deviceId: D1 }); const code = lastOtp(e2);
  ok(e2.call({ action: 'verifyOtp', email: 'b@example.com', otp: code, deviceId: D1 }).status === 'error', 'an OTP is bound to its own email');
}

console.log('Email OTP - failure handling and quota');
{
  const e = makeEnv(); e.failMail = true;
  const r = e.call({ action: 'sendOtp', email: EM, deviceId: D1 });
  ok(r.status === 'error' && r.message.includes('Could not send'), 'mail failure -> clean error');
  e.failMail = false;
  ok(e.call({ action: 'sendOtp', email: EM, deviceId: D1 }).status === 'ok', 'a failed send does not trigger the cooldown / burn a counter');
  const q = makeEnv(); q.mailQuota = 0;
  ok(q.call({ action: 'sendOtp', email: EM, deviceId: D1 }).message.includes('busy'), 'daily mail quota exhausted -> busy message');
  const r2 = makeEnv(); r2.mailQuota = 20;   // only the reserve is left
  ok(r2.call({ action: 'sendOtp', email: 'new@example.com', deviceId: D1 }).message.includes('busy'), 'reserve: brand-new addresses are refused when the quota is nearly gone');
  login(makeEnv(), EM, D1);
  const r3 = makeEnv(); login(r3, EM, D1); r3.mailQuota = 15;
  r3.ctx.CacheService.getScriptCache().remove('otp_rahul@example.com'); r3.ctx.CacheService.getScriptCache().remove('otpcnt_p_rahul@example.com');
  ok(r3.call({ action: 'sendOtp', email: EM, deviceId: D2 }).status === 'ok', 'reserve: an EXISTING user can still log in from the last quota');
}

console.log('Free trial');
{
  const e = makeEnv();
  ok(e.call({ action: 'startTrial', email: EM, deviceId: D1 }).status === 'error', 'SECURITY: trial refused without OTP login');
  login(e, EM, D1);
  const t1 = e.call({ action: 'startTrial', email: EM, deviceId: D1 });
  ok(t1.status === 'ok' && !t1.resumed, 'trial starts after login');
  const t2 = e.call({ action: 'startTrial', email: EM, deviceId: D1 });
  ok(t2.status === 'ok' && t2.resumed === true && t2.startedAt === t1.startedAt, 'same account+device resumes, not restarted');
  const t2b = e.call({ action: 'startTrial', email: 'Rahul+new@EXAMPLE.com', deviceId: D1 });
  ok(t2b.status === 'ok' && t2b.resumed === true, 'an alias of the same inbox shares the SAME trial');
  login(e, EM, D2);
  const t3 = e.call({ action: 'startTrial', email: EM, deviceId: D2 });
  ok(t3.status === 'ok' && t3.resumed === true, 'new device login with running trial resumes');
  ok(e.call({ action: 'startTrial', email: EM, deviceId: D1 }).status === 'error', 'SECURITY: signed-out old device cannot touch the trial');
  login(e, 'second@example.com', D1);
  ok(e.call({ action: 'startTrial', email: 'second@example.com', deviceId: D1 }).message.includes('device'), 'second email on a device that already had a trial is refused');
  ok(e.call({ action: 'trialInfo', email: EM, deviceId: D2 }).used === true, 'trialInfo reports used to the logged-in device');
  ok(e.call({ action: 'trialInfo', email: EM, deviceId: 'stranger' }).used === false, 'SECURITY: trialInfo gives strangers no information');
  ok(JSON.stringify(e.call({ action: 'trialInfo', email: 'zzz@example.com', deviceId: 'stranger' })) === JSON.stringify(e.call({ action: 'trialInfo', email: EM, deviceId: 'stranger' })), 'SECURITY: no account enumeration');
  e.sheets.Users.rows[1][7] = Date.now() - 25 * 3600 * 1000;
  ok(e.call({ action: 'startTrial', email: EM, deviceId: D2 }).status === 'error', 'expired trial cannot be restarted');
}

console.log('Attack regressions');
{
  const e = makeEnv(); let granted = 0;
  for (let i = 0; i < 50; i++) if (e.call({ action: 'startTrial', email: 'fake' + i + '@example.com', deviceId: 'fake-' + i }).status === 'ok') granted++;
  ok(granted === 0, 'trial farming with fake emails/devices grants 0 trials');
}
{
  const e = makeEnv(); let sent = 0;
  for (let i = 0; i < 200; i++) if (e.call({ action: 'sendOtp', email: 'u' + i + '@example.com', deviceId: 'd' + i }).status === 'ok') sent++;
  ok(sent === 30, 'mail flood capped at the new-address pool (30)');
  ok(e.mailQuota === 70, 'only 30 of the daily quota were spent');
}
{
  const e = makeEnv(); login(e, EM, D1);   // an existing customer...
  for (let i = 0; i < 200; i++) e.call({ action: 'sendOtp', email: 'u' + i + '@example.com', deviceId: 'd' + i });   // ...then a flood
  login(e, EM, D2);
  ok(e.call({ action: 'checkSession', email: EM, deviceId: D2 }).active === true, 'an existing customer can still log in during/after a flood');
}
{
  const e = makeEnv(); let sent = 0;
  const variants = []; for (let i = 0; i < 20; i++) { variants.push('victim+' + i + '@gmail.com'); variants.push('v.i.c.t.i.m' + '+' + i + '@gmail.com'); variants.push('VICTIM@googlemail.com'); }
  variants.forEach((v, i) => { if (e.call({ action: 'sendOtp', email: v, deviceId: 'd' + i }).status === 'ok') sent++; });
  ok(sent <= 1, 'mail-bombing one inbox via +tags / dots is limited to one OTP per cooldown (aliases share one counter)');
}
{
  const e = makeEnv(); e.sheets.Codes.rows.push(['SECRET-FREE', 'FREE', 0, 0, true]);
  let r; for (let i = 0; i < 12; i++) r = e.call({ action: 'redeem', code: 'GUESS' + i, deviceId: 'x' });
  ok(r.message.includes('Too many'), 'redeem guessing is locked out after 10 wrong codes');
  ok(e.call({ action: 'redeem', code: 'SECRET-FREE', deviceId: 'x' }).status === 'error', 'even the right code is refused while locked');
  ok(e.call({ action: 'redeem', code: 'SECRET-FREE', deviceId: 'other' }).status === 'ok', 'other devices are unaffected');
}
{
  const e = makeEnv(); const o = e.call({ action: 'razorpayCreateOrder', plan: 'YEARLY', deviceId: 'victim' });
  e.razorOrders[o.orderId].status = 'paid'; e.razorOrders[o.orderId].amount_paid = 99900;
  for (let i = 0; i < 310; i++) e.call({ action: 'razorpayCreateOrder', plan: 'HOURLY12', deviceId: 'spam' });
  ok(e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: 'thief' }).status === 'error', 'order cannot be stolen even after it leaves the 300-row window');
  ok(e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: 'victim' }).status === 'ok', 'rightful owner still gets the plan');
}
{
  const e = makeEnv();
  ok(e.call({ action: 'login', phone: '+919075034748', pin: '1234', deviceId: 'x' }).status === 'error', 'old PIN login stays disabled');
  ok(e.call({ action: 'resetPin', phone: '+919075034748', newPin: '1234', deviceId: 'x' }).status === 'error', 'old resetPin stays disabled (no takeover)');
  ok(e.sheets.Users.rows.length === 1, 'and neither created an account');
}

console.log('Razorpay payments');
{
  const e = makeEnv();
  ok(e.call({ action: 'razorpayCreateOrder', plan: 'BAD', deviceId: D1 }).status === 'error', 'invalid plan rejected');
  const o = e.call({ action: 'razorpayCreateOrder', plan: 'HOURLY12', deviceId: D1 });
  ok(o.status === 'ok' && o.amount === 900, 'order created at server price Rs 9');
  ok(e.call({ action: 'razorpayCreateOrder', plan: 'MONTHLY', deviceId: D1 }).amount === 29900, 'monthly = Rs 299');
  ok(e.call({ action: 'razorpayCreateOrder', plan: 'YEARLY', deviceId: D1 }).amount === 99900, 'yearly = Rs 999');
  ok(e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: D1 }).status === 'error', 'unpaid order not activated');
  ok(e.call({ action: 'check', deviceId: D1 }).hasUnclaimed === true, 'check reports unclaimed order');
  e.razorOrders[o.orderId].status = 'paid'; e.razorOrders[o.orderId].amount_paid = 900;
  ok(e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: D2 }).status === 'error', 'other device cannot claim the order');
  const v = e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: D1 });
  ok(v.status === 'ok' && v.planType === 'HOURLY12', 'paid order activates plan');
  const v2 = e.call({ action: 'razorpayVerify', orderId: o.orderId, deviceId: D1 });
  ok(v2.status === 'ok' && v2.expiryAt === v.expiryAt, 'verify is idempotent (no double time)');
  ok(e.call({ action: 'razorpayVerify', orderId: 'bad id', deviceId: D1 }).status === 'error', 'malformed order id rejected');
  // sync recovery of a paid-but-never-verified order
  const o2 = e.call({ action: 'razorpayCreateOrder', plan: 'MONTHLY', deviceId: D1 });
  e.razorOrders[o2.orderId].status = 'paid'; e.razorOrders[o2.orderId].amount_paid = 29900;
  const s = e.call({ action: 'razorpaySync', deviceId: D1 });
  ok(s.status === 'ok' && s.activated === 1, 'razorpaySync recovers a paid order');
  ok(s.expiryAt > v.expiryAt + 29 * 24 * 3600 * 1000, 'monthly stacks on top of existing time');
  const ord = e.ctx.SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Orders').rows;
  ok(ord.filter(r => r[5] === 'activated').length === 2, 'both paid orders are marked activated in the Orders tab');
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
