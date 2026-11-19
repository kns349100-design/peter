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
  const cacheStore = {}, props = { RAZORPAY_KEY_ID: 'rzp_test_x', RAZORPAY_KEY_SECRET: 'sec', SMSGATE_USER: 'u', SMSGATE_PASS: 'p' };
  const env = { escaped, sheets, sentSms: [], razorOrders: {}, razorCalls: [], fakeNow: null };
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
    UrlFetchApp: {
      fetch: (url, opt) => {
        env.razorCalls.push(url);
        if (url.indexOf('sms-gate') >= 0) { env.sentSms.push(JSON.parse(opt.payload)); return { getResponseCode: () => 200, getContentText: () => '{}' }; }
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

const D1 = 'dev-1', D2 = 'dev-2', PH = '+919075034748';
function login(e, phone, dev) {
  e.ctx.CacheService.getScriptCache().remove('otp_' + phone);
  e.call({ action: 'sendOtp', phone, deviceId: dev });
  return e.call({ action: 'verifyOtp', phone, otp: lastOtp(e), deviceId: dev });
}
function lastOtp(env) { const t = env.sentSms[env.sentSms.length - 1].textMessage.text; return t.match(/(\d{6})/)[1]; }

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

console.log('OTP login');
{
  const e = makeEnv();
  ok(e.call({ action: 'sendOtp', phone: '9075034748', deviceId: D1 }).status === 'error', 'phone without +91 rejected');
  ok(e.call({ action: 'sendOtp', phone: '+915075034748', deviceId: D1 }).status === 'error', 'phone not starting 6-9 rejected');
  const s = e.call({ action: 'sendOtp', phone: PH, deviceId: D1 });
  ok(s.status === 'ok', 'sendOtp ok');
  ok(e.call({ action: 'sendOtp', phone: PH, deviceId: D1 }).message.includes('wait'), 'resend cooldown enforced');
  ok(e.call({ action: 'verifyOtp', phone: PH, otp: '000000', deviceId: D1 }).message.includes('Wrong OTP'), 'wrong OTP rejected');
  const v = e.call({ action: 'verifyOtp', phone: PH, otp: lastOtp(e), deviceId: D1 });
  ok(v.status === 'ok' && v.registered === true, 'correct OTP registers new user');
  ok(e.call({ action: 'verifyOtp', phone: PH, otp: lastOtp(e), deviceId: D1 }).status === 'error', 'OTP is single-use');
  ok(e.call({ action: 'checkSession', phone: PH, deviceId: D1 }).active === true, 'session active on device 1');
  // login on device 2 -> device 1 signed out
  e.ctx.CacheService.getScriptCache().remove('otp_' + PH);
  e.call({ action: 'sendOtp', phone: PH, deviceId: D2 });
  const v2 = e.call({ action: 'verifyOtp', phone: PH, otp: lastOtp(e), deviceId: D2 });
  ok(v2.status === 'ok' && v2.registered === false, 'second login is not a new registration');
  ok(e.call({ action: 'checkSession', phone: PH, deviceId: D1 }).active === false, 'old device is signed out');
  ok(e.call({ action: 'checkSession', phone: PH, deviceId: D2 }).active === true, 'new device is the active one');
  ok(e.sheets.Users.rows.length === 2, 'users sheet has exactly one row for the number');
  // brute force
  e.ctx.CacheService.getScriptCache().remove('otp_' + PH);
  e.call({ action: 'sendOtp', phone: PH, deviceId: D2 });
  let last; for (let i = 0; i < 10; i++) last = e.call({ action: 'verifyOtp', phone: PH, otp: '111111', deviceId: D2 });
  ok(last.message.includes('expired') || last.message.includes('Too many') || last.message.includes('request a new'), 'OTP burned after too many wrong tries');
}

console.log('Free trial');
{
  const e = makeEnv();
  ok(e.call({ action: 'startTrial', phone: PH, deviceId: D1 }).status === 'error', 'SECURITY: trial refused without OTP login');
  login(e, PH, D1);
  const t1 = e.call({ action: 'startTrial', phone: PH, deviceId: D1 });
  ok(t1.status === 'ok' && !t1.resumed, 'trial starts after login');
  const t2 = e.call({ action: 'startTrial', phone: PH, deviceId: D1 });
  ok(t2.status === 'ok' && t2.resumed === true && t2.startedAt === t1.startedAt, 'same number+device resumes, not restarted');
  login(e, PH, D2);
  const t3 = e.call({ action: 'startTrial', phone: PH, deviceId: D2 });
  ok(t3.status === 'ok' && t3.resumed === true, 'new device login with running trial resumes');
  ok(e.call({ action: 'startTrial', phone: PH, deviceId: D1 }).status === 'error', 'SECURITY: signed-out old device cannot touch the trial');
  login(e, '+919999999999', D1);
  ok(e.call({ action: 'startTrial', phone: '+919999999999', deviceId: D1 }).message.includes('device'), 'second number on same device refused');
  ok(e.call({ action: 'trialInfo', phone: PH, deviceId: D2 }).used === true, 'trialInfo reports used to the logged-in device');
  ok(e.call({ action: 'trialInfo', phone: PH, deviceId: 'stranger' }).used === false, 'SECURITY: trialInfo gives strangers no information');
  ok(JSON.stringify(e.call({ action: 'trialInfo', phone: '+918888888888', deviceId: 'stranger' })) === JSON.stringify(e.call({ action: 'trialInfo', phone: PH, deviceId: 'stranger' })), 'SECURITY: no account enumeration');
  e.sheets.Users.rows[1][7] = Date.now() - 25 * 3600 * 1000;
  ok(e.call({ action: 'startTrial', phone: PH, deviceId: D2 }).status === 'error', 'expired trial cannot be restarted');
}

console.log('Attack regressions');
{
  const e = makeEnv(); let granted = 0;
  for (let i = 0; i < 50; i++) if (e.call({ action: 'startTrial', phone: '+91900000' + (1000 + i), deviceId: 'fake-' + i }).status === 'ok') granted++;
  ok(granted === 0, 'trial farming with fake phones/devices grants 0 trials');
}
{
  const e = makeEnv(); login(e, PH, D1);
  let sent = 0;
  for (let i = 0; i < 200; i++) if (e.call({ action: 'sendOtp', phone: '+9198765' + (10000 + i), deviceId: 'd' + i }).status === 'ok') sent++;
  ok(sent === 29, 'SMS flood capped at the unknown-number pool (1 of 30 used by the login above)');
  e.ctx.CacheService.getScriptCache().remove('otp_' + PH); e.ctx.CacheService.getScriptCache().remove('otpcnt_p_' + PH);
  ok(e.call({ action: 'sendOtp', phone: PH, deviceId: 'legit' }).status === 'ok', 'existing customer can still get an OTP during a flood');
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
