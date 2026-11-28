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
 * Tab "Users" (row 1 = headers) - for email + OTP login
 *   Email | PinHash | ActiveDeviceId | UpdatedAt | FailedAttempts | LockedUntil | TrialUsed | TrialStartedAt
 *   (filled automatically by the script - don't edit rows manually. TrialUsed/TrialStartedAt
 *    track the one-time 24-hour free trial per account, so it can never be repeated by
 *    uninstalling and reinstalling the app.)
 */

function doGet(e) {
  return routeRequest(e);
}

// POST is supported for clients migrating state-changing actions away from query-string requests.
function doPost(e) {
  return routeRequest(e);
}

function routeRequest(e) {
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
    return jsonResponse({ status: 'error', message: 'Unknown action.' });
  } catch (err) {
    console.error('doGet failed: ' + (err && err.message ? err.message : err));
    return jsonResponse({ status: 'error', message: 'Server error: ' + String(err && err.message ? err.message : err).substring(0, 160) });
  }
}

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

function safeCell(value) {
  var s = String(value);
  if (/^[=+\-@]/.test(s)) return "'" + s;
  return s;
}

function cleanInput(value, maxLen) {
  var s = String(value == null ? '' : value).trim();
  if (s.length > maxLen) s = s.substring(0, maxLen);
  return s;
}

var PLAN_AMOUNTS = { HOURLY12: 10, MONTHLY: 300, YEARLY: 1000 };

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

var CASHFREE_ORDERS_HEADERS = ['OrderId', 'DeviceId', 'PlanType', 'AmountRupees', 'CreatedAt', 'Status', 'Account'];
function findCashfreeOrder(orderId) {
  var sheet = getOrCreateSheet('CashfreeOrders', CASHFREE_ORDERS_HEADERS);
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0]) === orderId) {
      return { sheet: sheet, row: i + 1, values: rows[i] };
    }
  }
  return { sheet: sheet, row: -1, values: null };
}

function handleCreatePaymentLink(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();
  var phone = cleanInput(e.parameter.phone, 20);
  var accountKey = optionalAccountKey(e);
  if (accountKey && !deviceOwnsAccount(accountKey, deviceId)) accountKey = '';

  if (!deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'Missing required parameters' });
  }

  var amountRupees = PLAN_AMOUNTS[planType];
  if (!amountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('CASHFREE_APP_ID') || !props.getProperty('CASHFREE_SECRET_KEY')) {
    return jsonResponse({ status: 'error', message: 'Payment gateway keys are not configured on the server' });
  }
  var checkoutPageUrl = props.getProperty('GITHUB_PAGES_URL');
  if (!checkoutPageUrl) {
    return jsonResponse({ status: 'error', message: 'Checkout URL is not configured on the server' });
  }

  if (!/^[0-9]{10}$/.test(phone)) phone = '9999999999';

  var orderId = 'AD' + Date.now() + Utilities.getUuid().replace(/-/g, '').slice(0, 16);
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
    return jsonResponse({ status: 'error', message: 'Could not reach the payment gateway' });
  }

  var order;
  try {
    order = JSON.parse(orderResponse.getContentText());
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Invalid response from the payment gateway' });
  }

  if (!order.payment_session_id) {
    return jsonResponse({ status: 'error', message: 'Could not create order: ' + (order.message || JSON.stringify(order)) });
  }

  var saved = withLock(function () {
    var sheet = getOrCreateSheet('CashfreeOrders', CASHFREE_ORDERS_HEADERS);
    sheet.appendRow([safeCell(orderId), safeCell(deviceId), planType, amountRupees, Date.now(), 'created', safeCell(accountKey)]);
    return true;
  });
  if (!saved || (saved.getContent && JSON.parse(saved.getContent()).status !== 'ok')) return saved;

  var isProd = PropertiesService.getScriptProperties().getProperty('CASHFREE_ENV') === 'PROD';
  var payUrl = checkoutPageUrl +
    '?session=' + encodeURIComponent(order.payment_session_id) +
    '&mode=' + (isProd ? 'production' : 'sandbox');

  return jsonResponse({ status: 'ok', linkUrl: payUrl, linkId: orderId, planType: planType });
}

function handleCheckPayment(e) {
  var orderId = cleanInput(e.parameter.linkId, 100);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();

  if (!orderId || !deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'Missing required parameters' });
  }

  var expectedAmountRupees = PLAN_AMOUNTS[planType];
  if (!expectedAmountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  var requestedAccount = optionalAccountKey(e);
  if (requestedAccount && !deviceOwnsAccount(requestedAccount, deviceId)) requestedAccount = '';

  return withLock(function () {
    var saved = findCashfreeOrder(orderId);
    if (saved.row < 0) return jsonResponse({ status: 'error', message: 'Order not found' });
    var savedDevice = String(saved.values[1]).trim();
    var savedPlan = String(saved.values[2]).trim().toUpperCase();
    var savedAmount = Number(saved.values[3]);
    var savedAccount = String(saved.values[6] == null ? '' : saved.values[6]).trim();
    if (savedDevice !== deviceId || savedPlan !== planType || savedAmount !== expectedAmountRupees) {
      return jsonResponse({ status: 'error', message: 'Order details do not match this device or plan' });
    }
    if (savedAccount && requestedAccount !== savedAccount) {
      return jsonResponse({ status: 'error', message: 'Please log in with the account used to create this order' });
    }

    var actSheet = getSheet('Activations');
    var existing = actSheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (String(existing[i][1]) === orderId) {
        var existingAccount = String(existing[i][6] == null ? '' : existing[i][6]).trim();
        var sameOwner = existingAccount ? existingAccount === savedAccount : String(existing[i][0]).trim() === deviceId;
        if (!sameOwner || String(existing[i][4]).toUpperCase() !== planType) {
          return jsonResponse({ status: 'error', message: 'Payment is already linked to another account' });
        }
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
      return jsonResponse({ status: 'error', message: 'Could not reach the payment gateway' });
    }

    var order;
    try {
      order = JSON.parse(response.getContentText());
    } catch (err) {
      return jsonResponse({ status: 'error', message: 'Invalid response from the payment gateway' });
    }

    if (order.order_status !== 'PAID') {
      return jsonResponse({ status: 'error', message: 'Payment is incomplete (status: ' + (order.order_status || 'unknown') + ')' });
    }
    if (String(order.order_currency || '').toUpperCase() !== 'INR' ||
        !isFinite(Number(order.order_amount)) || Math.abs(Number(order.order_amount) - savedAmount) > 0.001) {
      return jsonResponse({ status: 'error', message: 'Payment amount or currency does not match the order' });
    }

    var now = Date.now();
    var base = now;
    var prior = actSheet.getDataRange().getValues();
    for (var j = 1; j < prior.length; j++) {
      if (!activationBelongs(prior[j], deviceId, savedAccount)) continue;
      if (isRevokedCell(prior[j][5]) || String(prior[j][4]).toUpperCase() === 'FREE') continue;
      var exp = Number(prior[j][3]) || 0;
      if (exp > base) base = exp;
    }
    var expiryAt = grantExpiry(planType, base);
    ensureActivationAccountHeader(actSheet);
    actSheet.appendRow([safeCell(deviceId), safeCell(orderId), now, expiryAt, planType, false, safeCell(savedAccount)]);
    saved.sheet.getRange(saved.row, 6).setValue('activated');

    return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: planType });
  });
}

var RAZORPAY_PLANS = {
  HOURLY12: { amount: 900, label: '12 Hour Access' },
  MONTHLY: { amount: 29900, label: '1 Month Access' },
  YEARLY: { amount: 99900, label: '1 Year Access' }
};
var RAZORPAY_AMOUNT_TO_PLAN = {
  900: 'HOURLY12', 1000: 'HOURLY12',
  29900: 'MONTHLY', 30000: 'MONTHLY',
  99900: 'YEARLY', 100000: 'YEARLY'
};
var ORDERS_HEADERS = ['OrderId', 'DeviceId', 'Plan', 'AmountPaise', 'CreatedAt', 'Status'];
var ORDERS_SCAN_ROWS = 300;
var ORDER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

function razorpayKeys() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('RAZORPAY_KEY_ID');
  var secret = props.getProperty('RAZORPAY_KEY_SECRET');
  if (!id || !secret) return null;
  return { id: id, secret: secret, auth: 'Basic ' + Utilities.base64Encode(id + ':' + secret) };
}

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

function deviceHasUnclaimedOrders(deviceId) {
  try {
    var now = Date.now();
    var list = recentOrders().list;
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      if (o.deviceId === deviceId && o.status !== 'activated' && o.status !== 'expired' && now - o.createdAt < ORDER_MAX_AGE_MS) return true;
    }
  } catch (err) {
  }
  return false;
}

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
    getOrCreateSheet('Orders', ORDERS_HEADERS).appendRow([safeCell(order.id), safeCell(deviceId), planKey, plan.amount, Date.now(), 'created']);
    return jsonResponse({ status: 'ok', orderId: order.id, amount: order.amount, keyId: keys.id, planLabel: plan.label });
  });
}

function activateRazorpayOrder(orderId, deviceId, keys, accountKey) {
  var actSheet = getSheet('Activations');
  var rows = actSheet.getDataRange().getValues();

  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][1]) === orderId) {
      var rowAcc = String(rows[i][6] == null ? '' : rows[i][6]).trim();
      var sameOwner = (rowAcc && accountKey) ? rowAcc === accountKey : String(rows[i][0]).trim() === deviceId;
      if (!sameOwner) {
        return { ok: false, message: 'This payment is already activated on another device', orderStatus: 'paid' };
      }
      return { ok: true, expiryAt: Number(rows[i][3]), planType: rows[i][4] };
    }
  }

  var recent = recentOrders().list;
  for (var k = 0; k < recent.length; k++) {
    if (recent[k].orderId === orderId && recent[k].deviceId !== deviceId) {
      return { ok: false, message: 'This order belongs to another device', orderStatus: 'foreign' };
    }
  }

  var response;
  try {
    response = UrlFetchApp.fetch('https://api.razorpay.com/v1/orders/' + encodeURIComponent(orderId), {
      headers: { Authorization: keys.auth },
      muteHttpExceptions: true
    });
  } catch (err) {
    return { ok: false, message: 'Could not reach Razorpay', orderStatus: 'unreachable' };
  }
  var order;
  try {
    order = JSON.parse(response.getContentText());
  } catch (err) {
    return { ok: false, message: 'Invalid response from Razorpay', orderStatus: 'unreachable' };
  }
  if (response.getResponseCode() !== 200 || !order || order.id !== orderId) {
    return { ok: false, message: 'Order not found', orderStatus: 'unknown' };
  }

  var owner = order.notes && order.notes.deviceId ? String(order.notes.deviceId).trim() : '';
  if (owner && owner !== deviceId) {
    return { ok: false, message: 'This order belongs to another device', orderStatus: 'foreign' };
  }
  if (!owner) {
    var known = false;
    for (var q = 0; q < recent.length; q++) {
      if (recent[q].orderId === orderId && recent[q].deviceId === deviceId) known = true;
    }
    if (!known) return { ok: false, message: 'Order not found', orderStatus: 'unknown' };
  }
  if (order.status !== 'paid' || Number(order.amount_paid) < Number(order.amount)) {
    return { ok: false, message: 'Payment is incomplete (status: ' + (order.status || 'unknown') + ')', orderStatus: order.status || 'unknown' };
  }
  var planType = RAZORPAY_AMOUNT_TO_PLAN[Number(order.amount)];
  var notedPlan = order.notes && order.notes.plan ? String(order.notes.plan).trim().toUpperCase() : '';
  if (!planType || order.currency !== 'INR' || (notedPlan && notedPlan !== planType)) {
    return { ok: false, message: 'Payment amount or plan does not match the order', orderStatus: 'unknown' };
  }

  var now = Date.now();
  var base = now;
  for (var j = 1; j < rows.length; j++) {
    if (!activationBelongs(rows[j], deviceId, accountKey)) continue;
    var revoked = rows[j][5];
    if (revoked === true || String(revoked).toUpperCase() === 'TRUE') continue;
    if (String(rows[j][4]).toUpperCase() === 'FREE') continue;
    var exp = Number(rows[j][3]) || 0;
    if (exp > base) base = exp;
  }
  var expiryAt = grantExpiry(planType, base);
  ensureActivationAccountHeader(actSheet);
  actSheet.appendRow([safeCell(deviceId), safeCell(orderId), now, expiryAt, planType, false, safeCell(accountKey || '')]);
  try { setOrderStatus(orderId, 'activated'); } catch (err) {}
  return { ok: true, expiryAt: expiryAt, planType: planType };
}

function handleRazorpayVerify(e) {
  var orderId = cleanInput(e.parameter.orderId, 100);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!orderId || !deviceId || !/^order_[A-Za-z0-9]+$/.test(orderId)) {
    return jsonResponse({ status: 'error', message: 'Missing required parameters' });
  }
  var keys = razorpayKeys();
  if (!keys) return jsonResponse({ status: 'error', message: 'Payment server is not configured' });

  var vAccount = optionalAccountKey(e);
  if (vAccount && !deviceOwnsAccount(vAccount, deviceId)) vAccount = '';
  return withLock(function () {
    var r = activateRazorpayOrder(orderId, deviceId, keys, vAccount);
    if (r.ok) return jsonResponse({ status: 'ok', expiryAt: r.expiryAt, planType: r.planType });
    return jsonResponse({ status: 'error', message: r.message, orderStatus: r.orderStatus });
  });
}

function handleRazorpaySync(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'Missing deviceId' });
  var keys = razorpayKeys();
  if (!keys) return jsonResponse({ status: 'error', message: 'Payment server is not configured' });

  var sAccount = optionalAccountKey(e);
  if (sAccount && !deviceOwnsAccount(sAccount, deviceId)) sAccount = '';
  return withLock(function () {
    var now = Date.now();
    var mine = [];
    var list = recentOrders().list;
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      if (o.deviceId === deviceId && o.status !== 'activated' && o.status !== 'expired' && now - o.createdAt < ORDER_MAX_AGE_MS) mine.push(o);
    }
    mine.sort(function (a, b) { return a.createdAt - b.createdAt; });
    mine = mine.slice(-5);

    var activated = 0;
    for (var m = 0; m < mine.length; m++) {
      var r = activateRazorpayOrder(mine[m].orderId, deviceId, keys, sAccount);
      if (r.ok) {
        activated++;
        continue;
      }
      var age = now - mine[m].createdAt;
      if ((r.orderStatus === 'created' && age > 2 * 60 * 60 * 1000) || (r.orderStatus !== 'unreachable' && age > 24 * 60 * 60 * 1000)) {
        setOrderStatus(mine[m].orderId, 'expired');
      }
    }
    var latest = sAccount ? latestActivationForAccount(deviceId, sAccount, true) : latestActivationFor(deviceId);
    if (latest.expiryAt === 0) return jsonResponse({ status: 'not_found', activated: activated });
    return jsonResponse({ status: 'ok', expiryAt: latest.expiryAt, planType: latest.planType, activated: activated });
  });
}

function grantExpiry(type, now) {
  if (type === 'FREE') return now + (100 * 365 * 24 * 60 * 60 * 1000);
  if (type === 'YEARLY') return now + (365 * 24 * 60 * 60 * 1000);
  if (type === 'HOURLY12') return now + (12 * 60 * 60 * 1000);
  return now + (30 * 24 * 60 * 60 * 1000);
}

var REDEEM_MAX_FAILS_PER_DEVICE_PER_HOUR = 10;
var REDEEM_MAX_FAILS_GLOBAL_PER_HOUR = 300;

function handleRedeem(e) {
  var code = cleanInput(e.parameter.code, 50).toUpperCase();
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!code || !deviceId) {
    return jsonResponse({ status: 'error', message: 'Missing required parameters' });
  }

  var redeemAccount = optionalAccountKey(e);
  if (redeemAccount && !deviceOwnsAccount(redeemAccount, deviceId)) redeemAccount = '';

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
        return jsonResponse({ status: 'error', message: 'This code is disabled' });
      }
      if (maxUses > 0 && usedCount >= maxUses) {
        return jsonResponse({ status: 'error', message: 'This code has reached its usage limit' });
      }

      var now = Date.now();
      var expiryAt = grantExpiry(type, now);

      codesSheet.getRange(i + 1, 4).setValue(usedCount + 1);

      var actSheet = getSheet('Activations');
      ensureActivationAccountHeader(actSheet);
      actSheet.appendRow([safeCell(deviceId), safeCell(code), now, expiryAt, type, false, safeCell(redeemAccount)]);

      return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: type });
    }

    bumpCounter(cache, failKeyD, 3600);
    bumpCounter(cache, failKeyG, 3600);
    return jsonResponse({ status: 'error', message: 'Invalid code' });
  });
}

function handleCheck(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'Missing deviceId' });

  var accountKey = optionalAccountKey(e);
  if (accountKey && !deviceOwnsAccount(accountKey, deviceId)) accountKey = '';

  var latest = accountKey
    ? withLockResult(function () { return latestActivationForAccount(deviceId, accountKey, true); })
    : latestActivationForAccount(deviceId, '', false);

  var unclaimed = deviceHasUnclaimedOrders(deviceId);
  if (latest.expiryAt === 0) return jsonResponse({ status: 'not_found', hasUnclaimed: unclaimed });
  return jsonResponse({ status: 'ok', expiryAt: latest.expiryAt, planType: latest.planType, hasUnclaimed: unclaimed });
}

function withLockResult(fn) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error('Server is busy, try again in a moment');
  try { return fn(); } finally { lock.releaseLock(); }
}

var ACTIVATION_HEADERS = ['DeviceId', 'CodeOrOrder', 'ActivatedAt', 'ExpiryAt', 'PlanType', 'Revoked', 'Account'];

function optionalAccountKey(e) {
  var raw = cleanInput(e.parameter.email, 260);
  if (!raw) return '';
  var a = normalizeEmail(raw);
  return a ? a.key : '';
}

function deviceOwnsAccount(accountKey, deviceId) {
  if (!accountKey || !deviceId) return false;
  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, accountKey);
  if (rowNum === -1) return false;
  return String(sheet.getRange(rowNum, 3).getValue()).trim() === deviceId;
}

function isRevokedCell(v) { return v === true || String(v).toUpperCase() === 'TRUE'; }

function activationBelongs(row, deviceId, accountKey) {
  var rowAccount = String(row[6] == null ? '' : row[6]).trim();
  if (accountKey) {
    if (rowAccount) return rowAccount === accountKey;
    return String(row[0]).trim() === deviceId;
  }
  return String(row[0]).trim() === deviceId && !rowAccount;
}

function ensureActivationAccountHeader(sheet) {
  if (String(sheet.getRange(1, 7).getValue()).trim() === '') sheet.getRange(1, 7).setValue('Account');
}

function latestActivationForAccount(deviceId, accountKey, backfill) {
  var sheet = getSheet('Activations');
  var data = sheet.getDataRange().getValues();
  var latestExpiry = 0, latestType = '', claimed = false;
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    if (!activationBelongs(row, deviceId, accountKey)) continue;
    if (backfill && accountKey && !String(row[6] == null ? '' : row[6]).trim()) {
      ensureActivationAccountHeader(sheet);
      sheet.getRange(i + 1, 7).setValue(safeCell(accountKey));
      claimed = true;
    }
    if (isRevokedCell(row[5])) continue;
    var expiryAt = Number(row[3]) || 0;
    if (expiryAt > latestExpiry) { latestExpiry = expiryAt; latestType = row[4]; }
  }
  return { expiryAt: latestExpiry, planType: latestType, claimed: claimed };
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

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
  return getOrCreateSheet('Users', ['Email', 'PinHash', 'ActiveDeviceId', 'UpdatedAt', 'FailedAttempts', 'LockedUntil', 'TrialUsed', 'TrialStartedAt']);
}

function findUserRow(sheet, account) {
  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (String(data[i][0]).trim() === account) return i + 1;
  }
  return -1;
}

var OTP_TTL_SECONDS = 600;
var OTP_MAX_VERIFY_ATTEMPTS = 5;
var OTP_RESEND_COOLDOWN_SECONDS = 30;
var OTP_MAX_PER_EMAIL_PER_HOUR = 5;
var OTP_MAX_PER_DEVICE_PER_HOUR = 10;
var OTP_MAX_GLOBAL_PER_6H = 30;
var OTP_MAX_KNOWN_PER_6H = 100;
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

var EMAIL_RE = /^[a-z0-9._%+\-]{1,64}@([a-z0-9\-]+\.)+[a-z]{2,24}$/;

var DISPOSABLE_DOMAINS = {
  'mailinator.com': 1, 'guerrillamail.com': 1, 'guerrillamail.net': 1, 'guerrillamail.org': 1, 'sharklasers.com': 1,
  '10minutemail.com': 1, '10minutemail.net': 1, 'tempmail.com': 1, 'temp-mail.org': 1, 'tempmailo.com': 1,
  'yopmail.com': 1, 'yopmail.net': 1, 'throwawaymail.com': 1, 'getnada.com': 1, 'nada.email': 1, 'trashmail.com': 1,
  'maildrop.cc': 1, 'dispostable.com': 1, 'fakeinbox.com': 1, 'mintemail.com': 1, 'mohmal.com': 1,
  'emailondeck.com': 1, 'tempail.com': 1, 'moakt.com': 1, 'mailnesia.com': 1, 'burnermail.io': 1
};

'guerrillamail.biz guerrillamail.de grr.la guerrillamailblock.com 10minutemail.org temp-mail.io tempmail.net tempail.com yopmail.fr dispostable.com fakeinbox.com maildrop.cc mailnesia.com mintemail.com mohmal.com emailondeck.com spamgourmet.com mytemp.email burnermail.io anonbox.net dropmail.me discard.email moakt.com mailcatch.com tmail.ws tmpmail.org tmpmail.net 1secmail.com 1secmail.net 1secmail.org inboxkitten.com harakirimail.com mail.tm mailsac.com spambox.us trbvm.com emailfake.com fakemail.net crazymailing.com gufum.com mailpoof.com tempinbox.com jetable.org owlymail.com cuvox.de tempr.email email-temp.com temporary-mail.net tempm.com fexpost.com smailpro.com emltmp.com mail-temp.com mailforspam.com trashmail.net trashmail.de'.split(' ').forEach(function (d) { DISPOSABLE_DOMAINS[d] = 1; });
function isDisposableDomain(domain) {
  var parts = String(domain || '').toLowerCase().split('.');
  for (var i = 0; i < parts.length - 1; i++) {
    if (DISPOSABLE_DOMAINS[parts.slice(i).join('.')]) return true;
  }
  return false;
}

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
    console.error('OTP email failed: ' + err);
    return false;
  }
}

function testEmailOtp() {
  var ok = sendOtpEmail('PUT_YOUR_EMAIL_HERE@gmail.com', '123456');
  Logger.log(ok ? 'Sent - check the inbox (and spam). Emails left today: ' + MailApp.getRemainingDailyQuota()
                : 'Failed - see the Executions log');
}

function handleSendOtp(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'Missing required parameters' });
  if (isDisposableDomain(acct.domain)) {
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

    var known = cache.get('known_' + acct.key) === '1';
    if (!known) {
      known = findUserRow(getUsersSheet(), acct.key) !== -1;
      if (known) cache.put('known_' + acct.key, '1', 21600);
    }
    var poolKey = known ? 'otpcnt_gk' : 'otpcnt_g';
    if (readCounter(cache, poolKey) >= (known ? OTP_MAX_KNOWN_PER_6H : OTP_MAX_GLOBAL_PER_6H)) {
      return jsonResponse({ status: 'error', message: 'OTP service is busy right now. Please try again later.' });
    }
    var quota = 100;
    try { quota = MailApp.getRemainingDailyQuota(); } catch (qe) { console.error('quota check failed: ' + qe); }
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

    cache.remove(otpKey);

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
  if (!deviceId) return jsonResponse({ status: 'error', message: 'Missing required parameters' });

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, acct.key);
  if (rowNum === -1) return jsonResponse({ status: 'not_found' });

  var activeDeviceId = sheet.getRange(rowNum, 3).getValue();
  return jsonResponse({ status: 'ok', active: String(activeDeviceId) === deviceId });
}

var TRIAL_MS = 24 * 60 * 60 * 1000;
var TRIAL_DEVICE_HEADERS = ['DeviceId', 'Account', 'StartedAt'];

function handleStartTrial(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'Missing required parameters' });

  return withLock(function () {
    var sheet = getUsersSheet();
    var devSheet = getOrCreateSheet('TrialDevices', TRIAL_DEVICE_HEADERS);
    var rowNum = findUserRow(sheet, acct.key);
    var now = Date.now();

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

function handleTrialInfo(e) {
  var acct = parseAccount(e);
  if (acct.error) return acct.error;
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, acct.key);
  if (rowNum === -1 || !deviceId || String(sheet.getRange(rowNum, 3).getValue()).trim() !== deviceId) {
    return jsonResponse({ status: 'ok', used: false });
  }
  var row = sheet.getRange(rowNum, 1, 1, 8).getValues()[0];
  var used = row[6] === true || String(row[6]).toUpperCase() === 'TRUE';
  if (!used) return jsonResponse({ status: 'ok', used: false });
  return jsonResponse({ status: 'ok', used: true, startedAt: Number(row[7]) || 0 });
}

function debugSendOtp() {
  var out = doGet({ parameter: { action: 'sendOtp', email: 'PUT_YOUR_EMAIL_HERE@gmail.com', deviceId: 'debug-device' } });
  Logger.log(out.getContent());
  getUsersSheet(); CacheService.getScriptCache(); Logger.log('Quota left: ' + MailApp.getRemainingDailyQuota());
}

function authorizeAll() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  Logger.log('Sheet: ' + (ss ? ss.getName() : 'NULL - script is not bound to a Sheet!'));
  Logger.log('Mail quota left: ' + MailApp.getRemainingDailyQuota());
  UrlFetchApp.getRequest('https://example.com');
  CacheService.getScriptCache().put('auth_test', '1', 60);
  LockService.getScriptLock();
  Logger.log('All permissions granted. Now: Deploy -> Manage deployments -> pencil -> New version -> Deploy.');
}

function keepWarm() { CacheService.getScriptCache().get('ping'); }
function setupKeepWarm() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'keepWarm') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('keepWarm').timeBased().everyMinutes(5).create();
  Logger.log('Keep-warm trigger installed (every 5 min).');
}
