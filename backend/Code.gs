/**
 * AutoDialer License Backend (Google Apps Script)
 * -------------------------------------------------
 * Deploy this as a Web App (free, no hosting cost) attached to a Google Sheet.
 * See backend/SETUP.md for step-by-step deployment instructions.
 *
 * Google Sheet needs 3 tabs:
 *
 * Tab "Codes"  (row 1 = headers)
 *   Code | Type | MaxUses | UsedCount | Active
 *   e.g.  SAPDEAL-FREE | FREE     | 0  | 0 | TRUE
 *         SAPDEAL-150  | MONTHLY  | 0  | 0 | TRUE
 *         SAPDEAL-100  | MONTHLY  | 0  | 0 | TRUE
 *         SAPDEAL-10   | HOURLY12 | 0  | 0 | TRUE
 *   MaxUses = 0 means unlimited uses for that code.
 *   Type options: FREE, HOURLY12 (12 hours), MONTHLY (30 days), YEARLY (365 days)
 *
 * Tab "Activations" (row 1 = headers)
 *   DeviceId | Code | ActivatedAt | ExpiryAt | PlanType | Revoked
 *   (this tab is filled automatically by the script - don't edit rows,
 *    except to set Revoked = TRUE on a row to cut off one device/branch)
 *
 * Tab "Users" (row 1 = headers) - for phone number + PIN login
 *   Phone | PinHash | ActiveDeviceId | UpdatedAt | FailedAttempts | LockedUntil | TrialUsed | TrialStartedAt
 *   (filled automatically by the script - don't edit rows manually. TrialUsed/TrialStartedAt
 *    track the one-time 24-hour free trial per phone number, so it can never be repeated by
 *    uninstalling and reinstalling the app.)
 *
 * ---- Reliability notes for anyone maintaining this file ----
 * - Every request that reads-then-writes a sheet takes a script lock first
 *   (see withLock()), so two requests arriving at the same instant can never
 *   both redeem the last use of a code, both log in with a wrong PIN guess
 *   without it counting, etc.
 * - doGet() never lets an exception escape as a raw Apps Script error page -
 *   it always returns clean JSON, because the app can only parse JSON.
 * - Every value that gets written into a Sheet cell is passed through
 *   safeCell() first, so a device ID or phone number that happens to start
 *   with "=", "+", "-" or "@" can never be misread by Sheets as a formula.
 */

function doGet(e) {
  try {
    var action = e && e.parameter ? e.parameter.action : null;
    if (action === 'redeem') return handleRedeem(e);
    if (action === 'check') return handleCheck(e);
    if (action === 'createPaymentLink') return handleCreatePaymentLink(e);
    if (action === 'checkPayment') return handleCheckPayment(e);
    if (action === 'razorpayVerify') return handleRazorpayVerify(e);
    if (action === 'razorpayCreateOrder') return handleRazorpayCreateOrder(e);
    if (action === 'razorpaySync') return handleRazorpaySync(e);
    if (action === 'trialInfo') return handleTrialInfo(e);
    if (action === 'sendOtp') return handleSendOtp(e);
    if (action === 'verifyOtp') return handleVerifyOtp(e);
    if (action === 'login' || action === 'resetPin') return jsonResponse({ status: 'error', message: 'Login now uses OTP - please update the app.' });
    if (action === 'checkSession') return handleCheckSession(e);
    if (action === 'startTrial') return handleStartTrial(e);
    return jsonResponse({ status: 'error', message: 'unknown action' });
  } catch (err) {
    // Never let a raw exception escape - the app can only understand JSON.
    return jsonResponse({ status: 'error', message: 'Server error, please try again' });
  }
}

/** Like getSheet, but creates the tab (with a header row) the first time it is needed, so new
 * features never fail just because a tab was not created by hand. */
function getOrCreateSheet(name, headers) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.appendRow(headers);
  }
  return sheet;
}

function getSheet(name) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  if (!sheet) throw new Error('Missing sheet tab: ' + name);
  return sheet;
}

/**
 * Takes a script-wide lock for the duration of fn(), waiting up to 10s for
 * any other in-flight request to finish first. Wrap this around ANY
 * read-then-write sequence on a sheet (redeem a code, log in, grant a
 * payment) so two simultaneous requests can never race each other into an
 * inconsistent state (e.g. a 1-use code being redeemed twice at once).
 */
function withLock(fn) {
  var lock = LockService.getScriptLock();
  var gotLock = lock.tryLock(10000);
  if (!gotLock) {
    return jsonResponse({ status: 'error', message: 'Server is busy, try again in a moment' });
  }
  try {
    return fn();
  } finally {
    lock.releaseLock();
  }
}

/** Prevents any value written into a Sheet cell from being misread as a
 * formula - if it starts with =, +, -, or @, prefix it with an apostrophe
 * (Sheets' own "treat as text" escape) so it is always stored literally. */
function safeCell(value) {
  var s = String(value);
  if (/^[=+\-@]/.test(s)) return "'" + s;
  return s;
}

/** Caps + trims any free-text input coming from the app before it's used
 * anywhere, so an unexpectedly huge or malformed value can't cause slow
 * sheet operations or bad rows. */
function cleanInput(value, maxLen) {
  var s = String(value == null ? '' : value).trim();
  if (s.length > maxLen) s = s.substring(0, maxLen);
  return s;
}

var PLAN_AMOUNTS = { HOURLY12: 10, MONTHLY: 300, YEARLY: 1000 };

/**
 * Cashfree base API URL - sandbox for TEST keys, live for PROD keys. Controlled by the
 * CASHFREE_ENV script property ('TEST' or 'PROD', defaults to TEST so nothing breaks if
 * it's not set yet).
 */
function cashfreeBaseUrl() {
  var env = PropertiesService.getScriptProperties().getProperty('CASHFREE_ENV');
  return (env === 'PROD') ? 'https://api.cashfree.com' : 'https://sandbox.cashfree.com';
}

function cashfreeHeaders() {
  var props = PropertiesService.getScriptProperties();
  return {
    'x-client-id': props.getProperty('CASHFREE_APP_ID'),
    'x-client-secret': props.getProperty('CASHFREE_SECRET_KEY'),
    'x-api-version': '2023-08-01',
    'Content-Type': 'application/json'
  };
}

/**
 * Creates a Cashfree Order and returns a URL (pointing back at this same Apps Script Web
 * App) that shows a tiny auto-redirecting page using Cashfree's official JS SDK - this is
 * Cashfree's "Standard Checkout", the one flow every account gets by default with zero extra
 * approval (unlike Payment Links and Custom/Headless Checkout, both of which returned "not
 * enabled or approved" errors on this account). Order creation itself was already confirmed
 * working earlier, so this routes entirely through calls that are proven to work.
 */
function handleCreatePaymentLink(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();
  var phone = cleanInput(e.parameter.phone, 20);

  if (!deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var amountRupees = PLAN_AMOUNTS[planType];
  if (!amountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('CASHFREE_APP_ID') || !props.getProperty('CASHFREE_SECRET_KEY')) {
    return jsonResponse({ status: 'error', message: 'Cashfree keys backend me set nahi hain' });
  }

  if (!/^[0-9]{10}$/.test(phone)) phone = '9999999999'; // Cashfree requires a 10-digit phone

  var orderId = 'AD' + planType + deviceId.replace(/[^a-zA-Z0-9]/g, '').slice(-8) + Date.now();
  var customerId = ('cust' + deviceId.replace(/[^a-zA-Z0-9]/g, '')).slice(0, 40);

  var orderPayload = {
    order_id: orderId,
    order_amount: amountRupees,
    order_currency: 'INR',
    customer_details: {
      customer_id: customerId,
      customer_phone: phone,
      customer_name: 'AutoDialer User'
    }
  };

  var orderResponse;
  try {
    orderResponse = UrlFetchApp.fetch(cashfreeBaseUrl() + '/pg/orders', {
      method: 'post',
      headers: cashfreeHeaders(),
      payload: JSON.stringify(orderPayload),
      muteHttpExceptions: true
    });
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Cashfree se contact nahi ho paya' });
  }

  var order;
  try {
    order = JSON.parse(orderResponse.getContentText());
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Cashfree se galat response mila' });
  }

  if (!order.payment_session_id) {
    return jsonResponse({ status: 'error', message: 'Order nahi ban paya: ' + (order.message || JSON.stringify(order)) });
  }

  var isProd = PropertiesService.getScriptProperties().getProperty('CASHFREE_ENV') === 'PROD';
  // Points at a small static page hosted on GitHub Pages (a fixed, whitelist-able domain) -
  // Apps Script's own content domain changes between deployments, so Cashfree can't whitelist
  // it reliably. Set GITHUB_PAGES_URL in Script Properties once GitHub Pages is enabled, e.g.
  // https://<your-github-username>.github.io/<repo-name>/checkout.html
  var checkoutPageUrl = PropertiesService.getScriptProperties().getProperty('GITHUB_PAGES_URL');
  if (!checkoutPageUrl) {
    return jsonResponse({ status: 'error', message: 'GITHUB_PAGES_URL backend me set nahi hai - CASHFREE_SETUP.md dekho' });
  }
  var payUrl = checkoutPageUrl +
    '?session=' + encodeURIComponent(order.payment_session_id) +
    '&mode=' + (isProd ? 'production' : 'sandbox');

  return jsonResponse({ status: 'ok', linkUrl: payUrl, linkId: orderId, planType: planType });
}

/**
 * Checks a Cashfree Order's status directly with Cashfree's servers and grants access if
 * it's really been paid and the amount matches the plan. Locked so two near-simultaneous
 * status checks for the same order can never both append an Activations row.
 */
function handleCheckPayment(e) {
  var orderId = cleanInput(e.parameter.linkId, 100);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();

  if (!orderId || !deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var expectedAmountRupees = PLAN_AMOUNTS[planType];
  if (!expectedAmountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  return withLock(function () {
    // Idempotency: never grant the same payment twice (e.g. if the app retries).
    var actSheet = getSheet('Activations');
    var existing = actSheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (String(existing[i][1]) === orderId) {
        return jsonResponse({ status: 'ok', expiryAt: Number(existing[i][3]), planType: existing[i][4] });
      }
    }

    var response;
    try {
      response = UrlFetchApp.fetch(cashfreeBaseUrl() + '/pg/orders/' + encodeURIComponent(orderId), {
        headers: cashfreeHeaders(),
        muteHttpExceptions: true
      });
    } catch (err) {
      return jsonResponse({ status: 'error', message: 'Cashfree se contact nahi ho paya' });
    }

    var order;
    try {
      order = JSON.parse(response.getContentText());
    } catch (err) {
      return jsonResponse({ status: 'error', message: 'Cashfree se galat response mila' });
    }

    if (order.order_status !== 'PAID') {
      return jsonResponse({ status: 'error', message: 'Payment abhi complete nahi hua (status: ' + (order.order_status || 'unknown') + ')' });
    }
    // Small tolerance for float rounding (e.g. Cashfree returning 9.999999 for 10).
    if (Number(order.order_amount) < expectedAmountRupees - 0.5) {
      return jsonResponse({ status: 'error', message: 'Payment amount plan se match nahi karta' });
    }

    var now = Date.now();
    var expiryAt = grantExpiry(planType, now);

    actSheet.appendRow([safeCell(deviceId), safeCell(orderId), now, expiryAt, planType, false]);

    return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: planType });
  });
}


/**
 * ---- Razorpay (in-app payment via Razorpay Android SDK) ----
 * Script Properties needed: RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET (Project Settings -> Script Properties).
 * The secret never goes into the app.
 *
 * Why a payment ALWAYS ends up as access:
 *  1. The app asks THIS script to create the order (price decided here, never by the app). The order
 *     is written to the "Orders" tab together with the device it belongs to BEFORE the payment
 *     screen even opens.
 *  2. After paying, the app asks us to confirm the order. We ask Razorpay itself whether it is paid
 *     (the app cannot fake that) and activate the plan.
 *  3. If anything goes wrong in between (app killed, no internet, phone restarted, app data
 *     cleared...), every normal status check tells the app "you have an unclaimed order", and the
 *     app then calls razorpaySync, which re-checks all this device's recent orders with Razorpay and
 *     activates every paid one. A paid order can only ever be activated once.
 */
var RAZORPAY_PLANS = {
  HOURLY12: { amount: 900, label: '12 Hour Access' },     // Rs 9
  MONTHLY: { amount: 29900, label: '1 Month Access' },    // Rs 299
  YEARLY: { amount: 99900, label: '1 Year Access' }       // Rs 999
};
// Every amount (paise) that activates a plan. The OLD prices stay listed on purpose, so an order that
// was created before a price change is still honoured when it gets paid.
var RAZORPAY_AMOUNT_TO_PLAN = {
  900: 'HOURLY12', 1000: 'HOURLY12',
  29900: 'MONTHLY', 30000: 'MONTHLY',
  99900: 'YEARLY', 100000: 'YEARLY'
};
var ORDERS_HEADERS = ['OrderId', 'DeviceId', 'Plan', 'AmountPaise', 'CreatedAt', 'Status'];
var ORDERS_SCAN_ROWS = 300;                    // only the newest rows are ever scanned (keeps it fast)
var ORDER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

function razorpayKeys() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('RAZORPAY_KEY_ID');
  var secret = props.getProperty('RAZORPAY_KEY_SECRET');
  if (!id || !secret) return null;
  return { id: id, secret: secret, auth: 'Basic ' + Utilities.base64Encode(id + ':' + secret) };
}

/** Newest ORDERS_SCAN_ROWS rows of the Orders tab as {row, orderId, deviceId, plan, createdAt, status}. */
function recentOrders() {
  var sheet = getOrCreateSheet('Orders', ORDERS_HEADERS);
  var last = sheet.getLastRow();
  if (last < 2) return { sheet: sheet, list: [] };
  var first = Math.max(2, last - ORDERS_SCAN_ROWS + 1);
  var values = sheet.getRange(first, 1, last - first + 1, 6).getValues();
  var list = [];
  for (var i = 0; i < values.length; i++) {
    list.push({
      row: first + i,
      orderId: String(values[i][0]),
      deviceId: String(values[i][1]).trim(),
      plan: String(values[i][2]),
      createdAt: Number(values[i][4]) || 0,
      status: String(values[i][5])
    });
  }
  return { sheet: sheet, list: list };
}

function setOrderStatus(orderId, status) {
  var r = recentOrders();
  for (var i = 0; i < r.list.length; i++) {
    if (r.list[i].orderId === orderId) {
      r.sheet.getRange(r.list[i].row, 6).setValue(status);
      return;
    }
  }
}

/** True if this device has an order that is not activated/expired yet and is still young enough
 * to be worth re-checking. Cheap (reads the sheet only, never calls Razorpay). */
function deviceHasUnclaimedOrders(deviceId) {
  try {
    var now = Date.now();
    var list = recentOrders().list;
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      if (o.deviceId === deviceId && o.status !== 'activated' && o.status !== 'expired' && now - o.createdAt < ORDER_MAX_AGE_MS) return true;
    }
  } catch (err) {
    // never let this optional hint break a normal status check
  }
  return false;
}

/** Latest non-revoked expiry for a device across the Activations tab: {expiryAt, planType}. */
function latestActivationFor(deviceId) {
  var data = getSheet('Activations').getDataRange().getValues();
  var latestExpiry = 0;
  var latestType = '';
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    if (String(row[0]).trim() !== deviceId) continue;
    var revoked = row[5];
    if (revoked === true || String(revoked).toUpperCase() === 'TRUE') continue;
    var expiryAt = Number(row[3]) || 0;
    if (expiryAt > latestExpiry) {
      latestExpiry = expiryAt;
      latestType = row[4];
    }
  }
  return { expiryAt: latestExpiry, planType: latestType };
}

/** App asks us to create the order, so the price can never be chosen by the app. */
function handleRazorpayCreateOrder(e) {
  var planKey = cleanInput(e.parameter.plan, 20);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var plan = RAZORPAY_PLANS[planKey];
  if (!plan || !deviceId) return jsonResponse({ status: 'error', message: 'Invalid plan' });
  var keys = razorpayKeys();
  if (!keys) return jsonResponse({ status: 'error', message: 'Payment server is not configured' });

  var order;
  try {
    var res = UrlFetchApp.fetch('https://api.razorpay.com/v1/orders', {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: keys.auth },
      payload: JSON.stringify({
        amount: plan.amount,
        currency: 'INR',
        receipt: 'app_' + planKey + '_' + Date.now(),
        notes: { plan: planKey, deviceId: deviceId }
      }),
      muteHttpExceptions: true
    });
    if (res.getResponseCode() === 401) return jsonResponse({ status: 'error', message: 'Razorpay authentication failed' });
    order = JSON.parse(res.getContentText());
    if (res.getResponseCode() !== 200 || !order || !order.id) {
      return jsonResponse({ status: 'error', message: 'Could not create the order. Please try again.' });
    }
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Could not reach the payment server. Please try again.' });
  }

  return withLock(function () {
    // Written BEFORE the payment screen opens - this is what makes recovery possible later.
    getOrCreateSheet('Orders', ORDERS_HEADERS).appendRow([safeCell(order.id), safeCell(deviceId), planKey, plan.amount, Date.now(), 'created']);
    return jsonResponse({ status: 'ok', orderId: order.id, amount: order.amount, keyId: keys.id, planLabel: plan.label });
  });
}

/**
 * Activates one Razorpay order for a device. MUST be called with the script lock held.
 * Returns {ok, expiryAt, planType, message, orderStatus}. Safe to call any number of times: an order
 * is only ever activated once, and only ever for the device it was created for.
 */
function activateRazorpayOrder(orderId, deviceId, keys) {
  var actSheet = getSheet('Activations');
  var rows = actSheet.getDataRange().getValues();

  // Already activated? (idempotency - the same paid order can never grant time twice)
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][1]) === orderId) {
      if (String(rows[i][0]).trim() !== deviceId) {
        return { ok: false, message: 'Ye payment kisi aur device par activate ho chuka hai', orderStatus: 'paid' };
      }
      return { ok: true, expiryAt: Number(rows[i][3]), planType: rows[i][4] };
    }
  }

  // An order created for one device can only be claimed by that device.
  var recent = recentOrders().list;
  for (var k = 0; k < recent.length; k++) {
    if (recent[k].orderId === orderId && recent[k].deviceId !== deviceId) {
      return { ok: false, message: 'Ye order kisi aur device ka hai', orderStatus: 'foreign' };
    }
  }

  var response;
  try {
    response = UrlFetchApp.fetch('https://api.razorpay.com/v1/orders/' + encodeURIComponent(orderId), {
      headers: { Authorization: keys.auth },
      muteHttpExceptions: true
    });
  } catch (err) {
    return { ok: false, message: 'Razorpay se contact nahi ho paya', orderStatus: 'unreachable' };
  }
  var order;
  try {
    order = JSON.parse(response.getContentText());
  } catch (err) {
    return { ok: false, message: 'Razorpay se galat response mila', orderStatus: 'unreachable' };
  }
  if (response.getResponseCode() !== 200 || !order || order.id !== orderId) {
    return { ok: false, message: 'Order nahi mila', orderStatus: 'unknown' };
  }
  // SECURITY: ownership comes from the notes the SERVER wrote when it created the order - not from the
  // newest-300-rows window of the Orders tab, which an attacker can push an old order out of.
  var owner = order.notes && order.notes.deviceId ? String(order.notes.deviceId).trim() : '';
  if (owner && owner !== deviceId) {
    return { ok: false, message: 'Ye order kisi aur device ka hai', orderStatus: 'foreign' };
  }
  if (!owner) {
    var known = false;
    for (var q = 0; q < recent.length; q++) { if (recent[q].orderId === orderId && recent[q].deviceId === deviceId) known = true; }
    if (!known) return { ok: false, message: 'Order nahi mila', orderStatus: 'unknown' };
  }
  if (order.status !== 'paid' || Number(order.amount_paid) < Number(order.amount)) {
    return { ok: false, message: 'Payment abhi complete nahi hua (status: ' + (order.status || 'unknown') + ')', orderStatus: order.status || 'unknown' };
  }
  var planType = RAZORPAY_AMOUNT_TO_PLAN[Number(order.amount)];
  if (!planType || order.currency !== 'INR') {
    return { ok: false, message: 'Payment amount kisi plan se match nahi karta', orderStatus: 'unknown' };
  }

  // Money-safety: extend from whichever is later - now or this device's current expiry - so a payment
  // can only ever ADD time, never replace/shorten what is already paid for.
  var now = Date.now();
  var base = now;
  for (var j = 1; j < rows.length; j++) {
    if (String(rows[j][0]).trim() !== deviceId) continue;
    var revoked = rows[j][5];
    if (revoked === true || String(revoked).toUpperCase() === 'TRUE') continue;
    if (String(rows[j][4]).toUpperCase() === 'FREE') continue;
    var exp = Number(rows[j][3]) || 0;
    if (exp > base) base = exp;
  }
  var expiryAt = grantExpiry(planType, base);
  actSheet.appendRow([safeCell(deviceId), safeCell(orderId), now, expiryAt, planType, false]);
  try { setOrderStatus(orderId, 'activated'); } catch (err) { /* the activation itself is what matters */ }
  return { ok: true, expiryAt: expiryAt, planType: planType };
}

function handleRazorpayVerify(e) {
  var orderId = cleanInput(e.parameter.orderId, 100);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!orderId || !deviceId || !/^order_[A-Za-z0-9]+$/.test(orderId)) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }
  var keys = razorpayKeys();
  if (!keys) return jsonResponse({ status: 'error', message: 'Payment server is not configured' });

  return withLock(function () {
    var r = activateRazorpayOrder(orderId, deviceId, keys);
    if (r.ok) return jsonResponse({ status: 'ok', expiryAt: r.expiryAt, planType: r.planType });
    return jsonResponse({ status: 'error', message: r.message, orderStatus: r.orderStatus });
  });
}

/**
 * Safety net: re-checks every recent unclaimed order of this device with Razorpay and activates the
 * paid ones. Called by the app whenever a status check reports hasUnclaimed.
 */
function handleRazorpaySync(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing deviceId' });
  var keys = razorpayKeys();
  if (!keys) return jsonResponse({ status: 'error', message: 'Payment server is not configured' });

  return withLock(function () {
    var now = Date.now();
    var mine = [];
    var list = recentOrders().list;
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      if (o.deviceId === deviceId && o.status !== 'activated' && o.status !== 'expired' && now - o.createdAt < ORDER_MAX_AGE_MS) mine.push(o);
    }
    mine.sort(function (a, b) { return a.createdAt - b.createdAt; }); // oldest first, so extensions stack in order
    mine = mine.slice(-5);

    var activated = 0;
    for (var m = 0; m < mine.length; m++) {
      var r = activateRazorpayOrder(mine[m].orderId, deviceId, keys);
      if (r.ok) {
        activated++;
        continue;
      }
      // Stop re-checking orders that were clearly never paid: an order nobody even tried to pay for
      // after 2 hours, or one still unpaid after a day.
      var age = now - mine[m].createdAt;
      if ((r.orderStatus === 'created' && age > 2 * 60 * 60 * 1000) || (r.orderStatus !== 'unreachable' && age > 24 * 60 * 60 * 1000)) {
        setOrderStatus(mine[m].orderId, 'expired');
      }
    }
    var latest = latestActivationFor(deviceId);
    if (latest.expiryAt === 0) return jsonResponse({ status: 'not_found', activated: activated });
    return jsonResponse({ status: 'ok', expiryAt: latest.expiryAt, planType: latest.planType, activated: activated });
  });
}

function grantExpiry(type, now) {
  if (type === 'FREE') return now + (100 * 365 * 24 * 60 * 60 * 1000);
  if (type === 'YEARLY') return now + (365 * 24 * 60 * 60 * 1000);
  if (type === 'HOURLY12') return now + (12 * 60 * 60 * 1000);
  return now + (30 * 24 * 60 * 60 * 1000); // MONTHLY
}

/** Locked so two devices redeeming the same limited-use code at the exact same
 * instant can never both succeed and push its use count past the limit. */
var REDEEM_MAX_FAILS_PER_DEVICE_PER_HOUR = 10;
var REDEEM_MAX_FAILS_GLOBAL_PER_HOUR = 300;

function handleRedeem(e) {
  var code = cleanInput(e.parameter.code, 50).toUpperCase();
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!code || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  return withLock(function () {
    var cache = CacheService.getScriptCache();
    var failKeyD = 'rdm_d_' + deviceId, failKeyG = 'rdm_g';
    if (readCounter(cache, failKeyD) >= REDEEM_MAX_FAILS_PER_DEVICE_PER_HOUR || readCounter(cache, failKeyG) >= REDEEM_MAX_FAILS_GLOBAL_PER_HOUR) {
      return jsonResponse({ status: 'error', message: 'Too many wrong codes. Try again after some time.' });
    }
    var codesSheet = getSheet('Codes');
    var data = codesSheet.getDataRange().getValues();

    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var rowCode = String(row[0]).trim().toUpperCase();
      if (rowCode !== code) continue;

      var type = String(row[1]).trim().toUpperCase();
      var maxUses = Number(row[2]) || 0;
      var usedCount = Number(row[3]) || 0;
      var active = row[4];

      if (active !== true && String(active).toUpperCase() !== 'TRUE') {
        return jsonResponse({ status: 'error', message: 'Ye code disabled hai' });
      }
      if (maxUses > 0 && usedCount >= maxUses) {
        return jsonResponse({ status: 'error', message: 'Is code ki limit khatam ho gayi' });
      }

      var now = Date.now();
      var expiryAt = grantExpiry(type, now);

      codesSheet.getRange(i + 1, 4).setValue(usedCount + 1);

      var actSheet = getSheet('Activations');
      actSheet.appendRow([safeCell(deviceId), safeCell(code), now, expiryAt, type, false]);

      return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: type });
    }

    bumpCounter(cache, failKeyD, 3600);
    bumpCounter(cache, failKeyG, 3600);
    return jsonResponse({ status: 'error', message: 'Invalid code' });
  });
}

function handleCheck(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing deviceId' });

  var actSheet = getSheet('Activations');
  var data = actSheet.getDataRange().getValues();

  var latestExpiry = 0;
  var latestType = '';

  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    if (String(row[0]).trim() !== deviceId) continue;
    var revoked = row[5];
    if (revoked === true || String(revoked).toUpperCase() === 'TRUE') continue; // skip revoked rows
    var expiryAt = Number(row[3]) || 0;
    if (expiryAt > latestExpiry) {
      latestExpiry = expiryAt;
      latestType = row[4];
    }
  }

  var unclaimed = deviceHasUnclaimedOrders(deviceId);
  if (latestExpiry === 0) return jsonResponse({ status: 'not_found', hasUnclaimed: unclaimed });
  return jsonResponse({ status: 'ok', expiryAt: latestExpiry, planType: latestType, hasUnclaimed: unclaimed });
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

/** ---- Accounts: the Users tab (column A = canonical email, C = the one active device) ---- */

function hashPin(pin) {
  var raw = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, pin);
  var hex = raw.map(function (b) {
    var v = (b < 0) ? b + 256 : b;
    var h = v.toString(16);
    return h.length === 1 ? '0' + h : h;
  }).join('');
  return hex;
}

function getUsersSheet() {
  return getSheet('Users');
}

function findUserRow(sheet, account) {
  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (String(data[i][0]).trim() === account) return i + 1; // 1-indexed sheet row
  }
  return -1;
}

/**
 * ---- Email + OTP login (free: the OTP is mailed from the Apps Script owner's Gmail) ----
 * Script Properties needed: none. The first run asks for the one-time "send email" permission
 * (run testEmailOtp once from the editor). See OTP_SETUP.md.
 *
 * The OTP is generated and checked only here on the server (kept 10 minutes in the script cache,
 * stored hashed). One email = one device: a correct OTP writes this device's id as the account's
 * ActiveDeviceId, so any other device logged in with that email is signed out the next time it
 * checks its session (handleCheckSession).
 *
 * Abuse protection: 60 s between OTPs per email, 5 per email per hour, 10 per device per hour,
 * a global cap for brand-new addresses, and the last 20 of the daily Gmail quota are reserved for
 * existing users. A wrong code is allowed 5 tries per OTP. The Users tab column A now holds the
 * canonical email (older rows keep their phone number and simply never match).
 */
var OTP_TTL_SECONDS = 600;
var OTP_MAX_VERIFY_ATTEMPTS = 5;
var OTP_RESEND_COOLDOWN_SECONDS = 60;
var OTP_MAX_PER_EMAIL_PER_HOUR = 5;
var OTP_MAX_PER_DEVICE_PER_HOUR = 10;
var OTP_MAX_GLOBAL_PER_6H = 30;        // brand-new addresses
var OTP_MAX_KNOWN_PER_6H = 100;        // addresses that already have an account
// Gmail allows only ~100 script emails a day (Workspace ~1500). The last few are kept for people who
// ALREADY have an account, so a flood of fake new addresses can never lock real users out.
var OTP_UNKNOWN_QUOTA_RESERVE = 20;

function otpHash(account, otp) {
  return hashPin(account + ':' + otp);
}

function randomOtp() {
  var bytes = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    Utilities.getUuid() + ':' + Date.now() + ':' + Math.random()
  );
  var n = 0;
  for (var i = 0; i < 6; i++) n = n * 256 + ((bytes[i] + 256) % 256);
  return String(100000 + (n % 900000));
}

function readCounter(cache, key) {
  return Number(cache.get(key)) || 0;
}

function bumpCounter(cache, key, ttlSeconds) {
  cache.put(key, String(readCounter(cache, key) + 1), ttlSeconds);
}

// ---------------------------------------------------------------------------------------------
// Email identity
// ---------------------------------------------------------------------------------------------

// Plain, linear-time pattern (no nested quantifiers) and the input is length-capped before it runs.
var EMAIL_RE = /^[a-z0-9._%+\-]{1,64}@([a-z0-9\-]+\.)+[a-z]{2,24}$/;

// Throw-away mailbox services. Not complete (new ones appear daily) - it only raises the bar for
// someone farming free trials with disposable addresses.
var DISPOSABLE_DOMAINS = {
  'mailinator.com': 1, 'guerrillamail.com': 1, 'guerrillamail.net': 1, 'guerrillamail.org': 1, 'sharklasers.com': 1,
  '10minutemail.com': 1, '10minutemail.net': 1, 'tempmail.com': 1, 'temp-mail.org': 1, 'tempmailo.com': 1,
  'yopmail.com': 1, 'yopmail.net': 1, 'throwawaymail.com': 1, 'getnada.com': 1, 'nada.email': 1, 'trashmail.com': 1,
  'maildrop.cc': 1, 'dispostable.com': 1, 'fakeinbox.com': 1, 'mintemail.com': 1, 'mohmal.com': 1,
  'emailondeck.com': 1, 'tempail.com': 1, 'moakt.com': 1, 'mailnesia.com': 1, 'burnermail.io': 1
};

/**
 * Returns {display, key, domain} or null.
 *  display = what the user typed (lower-cased) - the OTP is mailed here.
 *  key     = the canonical account id. Two spellings of the SAME inbox must be the same account, otherwise
 *            one person could farm unlimited free trials / extra logins:
 *            - "+tag" is dropped      (rahul+1@x.com  == rahul@x.com)
 *            - Gmail ignores dots     (r.a.h.u.l@gmail.com == rahul@gmail.com), googlemail.com == gmail.com
 */
function normalizeEmail(raw) {
  var s = String(raw == null ? '' : raw).trim().toLowerCase();
  if (s.length < 6 || s.length > 254 || !EMAIL_RE.test(s)) return null;
  var at = s.lastIndexOf('@');
  var local = s.substring(0, at);
  var domain = s.substring(at + 1);
  if (local.charAt(0) === '.' || local.charAt(local.length - 1) === '.' || local.indexOf('..') >= 0) return null;
  var labels = domain.split('.');
  for (var i = 0; i < labels.length; i++) {
    if (!labels[i] || labels[i].charAt(0) === '-' || labels[i].charAt(labels[i].length - 1) === '-') return null;
  }
  var plus = local.indexOf('+');
  var base = plus >= 0 ? local.substring(0, plus) : local;
  var canonDomain = domain === 'googlemail.com' ? 'gmail.com' : domain;
  if (canonDomain === 'gmail.com') base = base.replace(/\./g, '');
  if (!base) return null;
  return { display: s, key: base + '@' + canonDomain, domain: canonDomain };
}

/** Reads the `email` parameter. Returns {error: <response>} or the normalized account. */
function parseAccount(e) {
  var raw = cleanInput(e.parameter.email, 260);
  if (!raw) {
    if (e.parameter.phone) {
      return { error: jsonResponse({ status: 'error', message: 'Login now uses email - please update the app.' }) };
    }
    return { error: jsonResponse({ status: 'error', message: 'Enter a valid email address' }) };
  }
  var acct = normalizeEmail(raw);
  if (!acct) return { error: jsonResponse({ status: 'error', message: 'Enter a valid email address' }) };
  return acct;
}

/** Sends the OTP from the Apps Script owner's Gmail. Returns true if Gmail accepted it. */
function sendOtpEmail(to, otp) {
  try {
    MailApp.sendEmail({
      to: to,
      subject: 'AutoDialer login code: ' + otp,
      name: 'AutoDialer',
      noReply: true,
      body: 'Your AutoDialer login code is ' + otp + '.\n\n' +
        'It is valid for 10 minutes and works only once.\n' +
        'Do not share it with anyone. If you did not ask for it, just ignore this email.\n'
    });
    return true;
  } catch (err) {
    console.error('OTP email failed: ' + err);      // never log the address itself
    return false;
  }
}

/** Run once from the Apps Script editor (put your own address in): it also triggers the one-time
 * "allow this script to send email" permission prompt, which the deployed web app needs. */
function testEmailOtp() {
  var ok = sendOtpEmail('PUT_YOUR_EMAIL_HERE@gmail.com', '123456');
  Logger.log(ok ? 'Sent - check the inbox (and spam). Emails left today: ' + MailApp.getRemainingDailyQuota()
                : 'Failed - see the Executions log');
}

function handleSendOtp(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing params' });
  if (DISPOSABLE_DOMAINS[acct.domain]) {
    return jsonResponse({ status: 'error', message: 'Please use your permanent email address (temporary mail services are not allowed)' });
  }

  return withLock(function () {
    var cache = CacheService.getScriptCache();
    var now = Date.now();
    var otpKey = 'otp_' + acct.key;

    var existing = cache.get(otpKey);
    if (existing) {
      var prev = JSON.parse(existing);
      var waitS = OTP_RESEND_COOLDOWN_SECONDS - Math.floor((now - prev.sentAt) / 1000);
      if (waitS > 0) {
        return jsonResponse({ status: 'error', message: 'Please wait ' + waitS + ' sec before requesting another OTP' });
      }
    }
    if (readCounter(cache, 'otpcnt_p_' + acct.key) >= OTP_MAX_PER_EMAIL_PER_HOUR) {
      return jsonResponse({ status: 'error', message: 'Too many OTP requests for this email. Try again after some time.' });
    }
    if (readCounter(cache, 'otpcnt_d_' + deviceId) >= OTP_MAX_PER_DEVICE_PER_HOUR) {
      return jsonResponse({ status: 'error', message: 'Too many OTP requests from this device. Try again after some time.' });
    }
    // Two tiers: unknown addresses share a small pool, existing customers have their own reserve - so a
    // flood of fake addresses (rotating deviceIds) cannot lock real, registered users out of logging in.
    var known = findUserRow(getUsersSheet(), acct.key) !== -1;
    var poolKey = known ? 'otpcnt_gk' : 'otpcnt_g';
    if (readCounter(cache, poolKey) >= (known ? OTP_MAX_KNOWN_PER_6H : OTP_MAX_GLOBAL_PER_6H)) {
      return jsonResponse({ status: 'error', message: 'OTP service is busy right now. Please try again later.' });
    }
    var quota = MailApp.getRemainingDailyQuota();
    if (quota < 1 || (!known && quota <= OTP_UNKNOWN_QUOTA_RESERVE)) {
      return jsonResponse({ status: 'error', message: 'OTP service is busy right now. Please try again later.' });
    }

    var otp = randomOtp();
    if (!sendOtpEmail(acct.display, otp)) {
      return jsonResponse({ status: 'error', message: 'Could not send the OTP email. Please try again in a minute.' });
    }

    cache.put(otpKey, JSON.stringify({ h: otpHash(acct.key, otp), sentAt: now, attempts: 0 }), OTP_TTL_SECONDS);
    bumpCounter(cache, 'otpcnt_p_' + acct.key, 3600);
    bumpCounter(cache, 'otpcnt_d_' + deviceId, 3600);
    bumpCounter(cache, poolKey, 21600);
    return jsonResponse({ status: 'ok', resendAfter: OTP_RESEND_COOLDOWN_SECONDS });
  });
}

function handleVerifyOtp(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var otp = cleanInput(e.parameter.otp, 10);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!/^[0-9]{6}$/.test(otp) || !deviceId) {
    return jsonResponse({ status: 'error', message: 'Enter the 6-digit OTP' });
  }

  return withLock(function () {
    var cache = CacheService.getScriptCache();
    var otpKey = 'otp_' + acct.key;
    var raw = cache.get(otpKey);
    if (!raw) {
      return jsonResponse({ status: 'error', message: 'OTP expired or not requested. Please request a new OTP.' });
    }
    var rec = JSON.parse(raw);
    var now = Date.now();

    if (rec.h !== otpHash(acct.key, otp)) {
      rec.attempts = (rec.attempts || 0) + 1;
      if (rec.attempts >= OTP_MAX_VERIFY_ATTEMPTS) {
        cache.remove(otpKey);
        return jsonResponse({ status: 'error', message: 'Too many wrong attempts. Please request a new OTP.' });
      }
      var ttlLeft = OTP_TTL_SECONDS - Math.floor((now - rec.sentAt) / 1000);
      if (ttlLeft < 1) {
        cache.remove(otpKey);
        return jsonResponse({ status: 'error', message: 'OTP expired. Please request a new OTP.' });
      }
      cache.put(otpKey, JSON.stringify(rec), ttlLeft);
      return jsonResponse({ status: 'error', message: 'Wrong OTP (' + (OTP_MAX_VERIFY_ATTEMPTS - rec.attempts) + ' tries left)' });
    }

    cache.remove(otpKey); // one-time: this OTP can never be used again

    // Correct OTP - this device becomes the one and only active device for this email.
    // (Whatever device was logged in before is signed out the next time it checks its session.)
    var sheet = getUsersSheet();
    var rowNum = findUserRow(sheet, acct.key);
    if (rowNum === -1) {
      sheet.appendRow([safeCell(acct.key), '', safeCell(deviceId), now, 0, 0]);
      return jsonResponse({ status: 'ok', registered: true });
    }
    sheet.getRange(rowNum, 3, 1, 4).setValues([[safeCell(deviceId), now, 0, 0]]);
    return jsonResponse({ status: 'ok', registered: false });
  });
}

function handleCheckSession(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing params' });

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, acct.key);
  if (rowNum === -1) return jsonResponse({ status: 'not_found' });

  var activeDeviceId = sheet.getRange(rowNum, 3).getValue();
  return jsonResponse({ status: 'ok', active: String(activeDeviceId) === deviceId });
}

/**
 * Grants the one-time 24-hour free trial for a phone number - checked and recorded here on
 * the server (Users sheet columns G/H: TrialUsed, TrialStartedAt), NOT just on the phone
 * itself. A purely on-device trial flag gets wiped the instant someone uninstalls and
 * reinstalls the app, letting the same phone number claim endless "free 24 hours" - keying it
 * to the phone number's row on the server instead closes that off completely, since the
 * phone number (and its login) is what survives a reinstall, not anything stored locally.
 * Locked so two rapid taps (or two devices) can never both start a trial for the same number.
 */
var TRIAL_MS = 24 * 60 * 60 * 1000;
var TRIAL_DEVICE_HEADERS = ['DeviceId', 'Account', 'StartedAt'];

/**
 * Starts the 24-hour free trial. Strict rules, all checked here on the server (never on the phone):
 *  - once per phone number (Users tab)
 *  - once per device (TrialDevices tab) - a second SIM / number on the same phone does not get another one
 *  - if the number's trial is still running (e.g. after a reinstall or a new login), it is simply
 *    RESUMED with its original start time - it is never restarted or extended.
 */
function handleStartTrial(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing params' });

  return withLock(function () {
    var sheet = getUsersSheet();
    var devSheet = getOrCreateSheet('TrialDevices', TRIAL_DEVICE_HEADERS);
    var rowNum = findUserRow(sheet, acct.key);
    var now = Date.now();

    // SECURITY: a trial may only be started by the device that just passed the email OTP for this account.
    // Rows in Users are only ever created by handleVerifyOtp, so an unverified address has no row.
    if (rowNum === -1 || String(sheet.getRange(rowNum, 3).getValue()).trim() !== deviceId) {
      return jsonResponse({ status: 'error', message: 'Please log in with OTP first' });
    }

    var row = sheet.getRange(rowNum, 1, 1, 8).getValues()[0];
    var trialUsed = row[6] === true || String(row[6]).toUpperCase() === 'TRUE';
    var usedAt = Number(row[7]) || 0;

    if (trialUsed) {
      var started = usedAt || now;
      if (now - started < TRIAL_MS) {
        return jsonResponse({ status: 'ok', startedAt: started, resumed: true });
      }
      return jsonResponse({ status: 'error', message: 'Free trial already used on this account', startedAt: started });
    }

    // This account is new to the trial - but has THIS DEVICE already had one (with any email)?
    var devData = devSheet.getDataRange().getValues();
    for (var i = 1; i < devData.length; i++) {
      if (String(devData[i][0]).trim() === deviceId) {
        var devStarted = Number(devData[i][2]) || now;
        if (String(devData[i][1]).trim() === acct.key) {
          return jsonResponse({ status: 'ok', startedAt: devStarted, resumed: true });
        }
        return jsonResponse({ status: 'error', message: 'Free trial already used on this device', startedAt: devStarted });
      }
    }

    sheet.getRange(rowNum, 7, 1, 2).setValues([[true, now]]);
    devSheet.appendRow([safeCell(deviceId), safeCell(acct.key), now]);
    return jsonResponse({ status: 'ok', startedAt: now });
  });
}

/** Lets a freshly installed / newly logged-in app learn whether this account already has a trial. */
function handleTrialInfo(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, acct.key);
  // SECURITY: identical answer for unknown accounts and for callers that are not this account's
  // logged-in device, so the endpoint cannot be used to discover who has an account / trial.
  if (rowNum === -1 || !deviceId || String(sheet.getRange(rowNum, 3).getValue()).trim() !== deviceId) {
    return jsonResponse({ status: 'ok', used: false });
  }
  var row = sheet.getRange(rowNum, 1, 1, 8).getValues()[0];
  var used = row[6] === true || String(row[6]).toUpperCase() === 'TRUE';
  if (!used) return jsonResponse({ status: 'ok', used: false });
  return jsonResponse({ status: 'ok', used: true, startedAt: Number(row[7]) || 0 });
}
