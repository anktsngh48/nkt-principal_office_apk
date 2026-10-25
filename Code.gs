/**
 * Principal's Office - Google Apps Script backend (Code.gs)
 *
 * Paste this into the spreadsheet's own script editor (Extensions > Apps Script),
 * then Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone).
 * Paste the Web app URL into CONFIG.SCRIPT_URL in index.html.
 * See README.md for the sheet layout.
 */

const NOTICES_SHEET = 'Notices';
const SETTINGS_SHEET = 'Settings';
const UPLOAD_FOLDER = 'Principal Office Uploads';   // Drive folder that holds all uploads (reused if it exists)
const UPLOAD_FOLDER_ID = '';                         // optional: paste a folder id here to pin the exact folder
const MAX_FILE_BYTES = 7 * 1024 * 1024;
const KINDS_ = {
  text:'text', short:'text', string:'text', name:'text',
  long:'textarea', longtext:'textarea', paragraph:'textarea', textarea:'textarea',
  number:'number', numeric:'number', email:'email', phone:'tel', mobile:'tel', tel:'tel',
  date:'date', time:'time', url:'url', link:'url',
  dropdown:'select', select:'select', radio:'radio', choice:'radio',
  checkbox:'checkbox', checkboxes:'checkbox', multiselect:'checkbox',
  picture:'image', image:'image', photo:'image', pdf:'pdf', file:'file', document:'file'
};

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
function formSheets_(ss) {
  return ss.getSheets().filter(function (s) {
    const n = s.getName();
    return /active/i.test(n) && n !== NOTICES_SHEET && n !== SETTINGS_SHEET;
  });
}

// Responses go to the sheet named by the text before "Active" in the form sheet's name:
// "Aadhar collection - Active"  ->  "Aadhar collection".
// That name never contains "Active", so a response sheet can never be mistaken for a form.
function responseSheetName_(formName) {
  var clean = function (s) {
    return String(s).replace(/[\[\]*?:\/\\]/g, ' ').replace(/\s+/g, ' ').replace(/^[\s\-–—|_]+|[\s\-–—|_]+$/g, '');
  };
  var m = /active/i.exec(formName);
  var name = clean(m ? formName.slice(0, m.index) : formName);
  if (!name && m) name = clean(formName.slice(m.index + m[0].length).replace(/active/ig, ''));  // "Active - Leave" -> "Leave"
  if (!name) name = 'Form Responses';
  name = name.slice(0, 100).trim();
  var lower = name.toLowerCase();
  if (lower === NOTICES_SHEET.toLowerCase() || lower === SETTINGS_SHEET.toLowerCase()) name += ' Responses';
  return name;
}

// Sheet names are unique ignoring case, so look the sheet up the same way.
function findSheetCI_(ss, name) {
  var n = name.toLowerCase();
  return ss.getSheets().filter(function (s) { return s.getName().toLowerCase() === n; })[0] || null;
}

/* ---------- Drive folders for uploads ----------
 * Layout:  <Principal Office Uploads> / <form name> / <Name of the person> / <Name>.<ext>
 * Folder ids are remembered in Script Properties, so the same folder is reused every time
 * (searching Drive by name alone can lag behind and create duplicates).
 */
function md5_(s) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, s, Utilities.Charset.UTF_8)
    .map(function (b) { return ('0' + (b & 255).toString(16)).slice(-2); }).join('');
}
function cleanName_(s, max) {
  return String(s == null ? '' : s).replace(/[\\\/:*?"<>|\[\]]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max || 80).trim();
}
function withLock_(fn) {
  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try { return fn(); } finally { lock.releaseLock(); }
}
function getRootFolder_() {
  if (UPLOAD_FOLDER_ID) return DriveApp.getFolderById(UPLOAD_FOLDER_ID);
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('folder:root');
  if (id) {
    try { var f = DriveApp.getFolderById(id); if (!f.isTrashed()) return f; } catch (err) {}
  }
  var it = DriveApp.getFoldersByName(UPLOAD_FOLDER);
  var folder = it.hasNext() ? it.next() : DriveApp.createFolder(UPLOAD_FOLDER);
  props.setProperty('folder:root', folder.getId());
  return folder;
}
function getChildFolder_(parent, name) {
  var props = PropertiesService.getScriptProperties();
  var key = 'sub:' + md5_(parent.getId() + '|' + name.toLowerCase());
  var id = props.getProperty(key);
  if (id) {
    try { var f = DriveApp.getFolderById(id); if (!f.isTrashed()) return f; } catch (err) {}
  }
  var it = parent.getFoldersByName(name);
  var folder = it.hasNext() ? it.next() : parent.createFolder(name);
  props.setProperty(key, folder.getId());
  return folder;
}
// One new folder per entry; a repeated name becomes "Name (2)", "Name (3)", ...
function newEntryFolder_(parent, base) {
  var name = base, n = 1;
  while (parent.getFoldersByName(name).hasNext()) { n++; name = base + ' (' + n + ')'; }
  return parent.createFolder(name);
}
function extFor_(name, type) {
  var m = /\.([A-Za-z0-9]{1,5})$/.exec(String(name || ''));
  if (m) return '.' + m[1].toLowerCase();
  var map = { 'application/pdf': '.pdf', 'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp', 'image/gif': '.gif', 'image/heic': '.heic' };
  return map[type] || '';
}

function doGet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const out = { settings: {}, notices: [], forms: {} };
  const st = ss.getSheetByName(SETTINGS_SHEET);
  if (st && st.getLastRow() >= 1) {
    st.getDataRange().getDisplayValues().forEach(function (r) { if (r[0]) out.settings[r[0]] = r[1]; });
  }
  const ns = ss.getSheetByName(NOTICES_SHEET);
  if (ns && ns.getLastRow() > 1) {
    const g = ns.getDataRange().getDisplayValues();
    const head = g[0];
    out.notices = g.slice(1)
      .filter(function (r) { return r.some(function (c) { return c !== ''; }); })
      .map(function (r) {
        const o = {};
        head.forEach(function (h, i) { if (h) o[h] = r[i]; });
        return o;
      });
  }
  formSheets_(ss).forEach(function (s) {
    if (s.getLastRow() >= 1 && s.getLastColumn() >= 1) out.forms[s.getName()] = s.getDataRange().getDisplayValues();
  });
  return json_(out);
}

function parseForm_(grid) {
  const labels = grid[0] || [], types = grid[1] || [], fields = [];
  labels.forEach(function (lab, i) {
    var label = String(lab || '').trim();
    if (!label) return;
    var required = /\*\s*$/.test(label);
    label = label.replace(/\s*\*\s*$/, '').trim();
    var t = String(types[i] || 'text').trim();
    if (/\*|required/i.test(t)) { required = true; t = t.replace(/\*|\(?required\)?/ig, '').trim(); }
    var parts = t.split(':');
    var kindRaw = (parts.shift() || 'text').trim().toLowerCase().replace(/\s+/g, '') || 'text';
    var options = parts.join(':').split(/[|,;]/).map(function (s) { return s.trim(); }).filter(String);
    if (!options.length) {
      options = grid.slice(2).map(function (r) { return String((r || [])[i] || '').trim(); }).filter(String);
    }
    var kind = KINDS_[kindRaw] || 'text';
    if (['select', 'radio', 'checkbox'].indexOf(kind) >= 0 && !options.length) kind = 'text';
    fields.push({ label: label, required: required, kind: kind, options: options });
  });
  return fields;
}

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    if (data.website) return json_({ ok: true });            // honeypot: bots only
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sheet = formSheets_(ss).filter(function (s) { return s.getName() === data.form; })[0];
    if (!sheet) return json_({ ok: false, error: 'This form is no longer available.' });
    const fields = parseForm_(sheet.getDataRange().getDisplayValues());
    const values = data.values || {};
    const row = {};
    const pending = [];            // uploaded files, saved to Drive only after every field has been validated

    for (var i = 0; i < fields.length; i++) {
      var f = fields[i], v = values[f.label], out = '';
      if (f.kind === 'image' || f.kind === 'pdf' || f.kind === 'file') {
        if (v && v.data) {
          if (v.data.length * 0.75 > MAX_FILE_BYTES) return json_({ ok: false, error: 'A file is too large.' });
          var type = String(v.type || '');
          var okType = f.kind === 'pdf' ? type === 'application/pdf'
            : f.kind === 'image' ? /^image\//.test(type)
            : (type === 'application/pdf' || /^image\//.test(type));
          if (!okType) return json_({ ok: false, error: 'Unsupported file type for "' + f.label + '".' });
          pending.push({ field: f, file: v, type: type });
          out = 'pending';
        }
      } else if (f.kind === 'checkbox') {
        var arr = Array.isArray(v) ? v : [];
        arr = arr.filter(function (x) { return f.options.indexOf(x) >= 0; });
        out = arr.join(', ');
      } else if (f.kind === 'select' || f.kind === 'radio') {
        out = f.options.indexOf(v) >= 0 ? v : '';
      } else {
        out = String(v == null ? '' : v).slice(0, f.kind === 'textarea' ? 5000 : 500);
      }
      if (f.required && !out) return json_({ ok: false, error: 'Please fill in "' + f.label + '".' });
      if (/^[=+\-@]/.test(out)) out = "'" + out;               // stop spreadsheet formula injection
      row[f.label] = out;
    }

    // Drive: <Principal Office Uploads> / <form name> / <first field, e.g. Name> / <Name>.<ext>
    if (pending.length) {
      const nameField = fields.filter(function (x) { return ['image', 'pdf', 'file', 'checkbox'].indexOf(x.kind) < 0; })[0];
      const person = cleanName_(nameField ? values[nameField.label] : '', 80) ||
        ('Entry ' + Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd HH.mm.ss'));
      const entryFolder = withLock_(function () {
        const formFolder = getChildFolder_(getRootFolder_(), responseSheetName_(sheet.getName()));
        return newEntryFolder_(formFolder, person);
      });
      pending.forEach(function (p) {
        const base = pending.length === 1 ? person : person + ' - ' + cleanName_(p.field.label, 40);
        const blob = Utilities.newBlob(Utilities.base64Decode(p.file.data), p.type, base + extFor_(p.file.name, p.type));
        row[p.field.label] = entryFolder.createFile(blob).getUrl();
      });
    }

    withLock_(function () {
      var respName = responseSheetName_(sheet.getName());
      var rs = findSheetCI_(ss, respName) || ss.insertSheet(respName);   // create it on first submission
      var labels = fields.map(function (x) { return x.label; });
      var want = ['Timestamp', 'Notice'].concat(labels);
      var head = rs.getLastColumn() > 0
        ? rs.getRange(1, 1, 1, rs.getLastColumn()).getValues()[0].map(String)
        : [];
      var added = want.filter(function (h) { return head.indexOf(h) < 0; });   // new form fields get new columns
      if (added.length) {
        rs.getRange(1, head.length + 1, 1, added.length).setValues([added]).setFontWeight('bold');
        head = head.concat(added);
      }
      if (rs.getFrozenRows() < 1) rs.setFrozenRows(1);
      var line = head.map(function (h) {
        if (h === 'Timestamp') return new Date();
        if (h === 'Notice') return String(data.notice || '').slice(0, 200);
        return row[h] !== undefined ? row[h] : '';
      });
      rs.appendRow(line);
    });
    return json_({ ok: true });
  } catch (err) {
    console.error(err);
    return json_({ ok: false, error: 'Something went wrong. Please try again.' });
  }
}
