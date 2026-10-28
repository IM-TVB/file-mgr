let currentPath = "";
// Identifies this browser tab, so the server can drop its old folder wait when it asks again.
const CLIENT_ID = Math.random().toString(36).slice(2, 12);
let currentEntries = [];
let sortKey = "name";
let sortDir = 1; // 1 asc, -1 desc
let selectedPaths = new Set();
let lastCheckedPath = null;

const tableBody = document.getElementById("fileTableBody");
const breadcrumb = document.getElementById("breadcrumb");
const itemCount = document.getElementById("itemCount");
const toast = document.getElementById("toast");
const searchInput = document.getElementById("searchInput");
const searchView = document.getElementById("searchView");
const navPlugins = document.getElementById("navPlugins");
const pluginsView = document.getElementById("pluginsView");
const pluginSearchInput = document.getElementById("pluginSearchInput");
const pluginGrid = document.getElementById("pluginGrid");
const pluginEmptyState = document.getElementById("pluginEmptyState");
const pluginEmptyTitle = document.getElementById("pluginEmptyTitle");
const pluginEmptySub = document.getElementById("pluginEmptySub");
const pluginLoadMore = document.getElementById("pluginLoadMore");
const pluginResultCount = document.getElementById("pluginResultCount");
const pluginVersionsPanel = document.getElementById("pluginVersionsPanel");
const pluginVersionsTitle = document.getElementById("pluginVersionsTitle");
const pluginVersionsSub = document.getElementById("pluginVersionsSub");
const pluginVersionsList = document.getElementById("pluginVersionsList");
const pluginVersionsClose = document.getElementById("pluginVersionsClose");
const searchResultsTitle = document.getElementById("searchResultsTitle");
const searchResultsList = document.getElementById("searchResultsList");
const emptyState = document.getElementById("emptyState");
const emptyTitle = document.getElementById("emptyTitle");
const emptySub = document.getElementById("emptySub");
const loadingState = document.getElementById("loadingState");
const table = document.getElementById("fileTable");
const dropzone = document.getElementById("dropzone");
const uploadProgress = document.getElementById("uploadProgress");
const selectAllCheckbox = document.getElementById("selectAllCheckbox");
const bulkBar = document.getElementById("bulkBar");
const bulkCount = document.getElementById("bulkCount");
const bulkCompressBtn = document.getElementById("bulkCompressBtn");
const bulkExtractBtn = document.getElementById("bulkExtractBtn");
const bulkDownloadBtn = document.getElementById("bulkDownloadBtn");
const bulkRenameBtn = document.getElementById("bulkRenameBtn");
const bulkDeleteBtn = document.getElementById("bulkDeleteBtn");
const bulkClearBtn = document.getElementById("bulkClearBtn");

function showToast(msg, isError) {
  toast.textContent = msg;
  toast.className = "toast show" + (isError ? " error" : "");
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => { toast.className = "toast"; }, 2500);
}

async function api(path, opts) {
  const res = await fetch(path, { credentials: "include", ...opts });
  if (res.status === 401) {
    document.body.innerHTML = "<div style='display:flex;align-items:center;justify-content:center;min-height:100vh;color:#8b939b;font-family:-apple-system,sans-serif;text-align:center;padding:20px'><div><div style='font-size:16px;font-weight:600;color:#eef1f3;margin-bottom:8px'>Session expired</div><div style='font-size:13px'>Run <code style='color:#29d6c8'>/filemgr</code> in-game again to get a new link.</div></div></div>";
    throw new Error("unauthorized");
  }
  return res;
}

function joinPath(base, name) {
  return base ? base + "/" + name : name;
}

function renderBreadcrumbInto(el, path, extraSegment) {
  const parts = path.split("/").filter(Boolean);
  const hasExtra = !!extraSegment;
  el.textContent = "";
  const addSep = () => {
    const sep = document.createElement("span");
    sep.className = "sep";
    sep.textContent = "/";
    el.appendChild(sep);
  };
  // Folder names are set as text and as dataset values, never parsed as HTML.
  const addLink = (label, dataPath, isCurrent) => {
    const a = document.createElement("a");
    a.textContent = label;
    a.className = isCurrent ? "current" : "";
    if (dataPath !== undefined) a.dataset.path = dataPath;
    el.appendChild(a);
  };
  addLink("root", "", parts.length === 0 && !hasExtra);
  let acc = "";
  parts.forEach((part, i) => {
    acc = acc ? acc + "/" + part : part;
    addSep();
    addLink(part, acc, i === parts.length - 1 && !hasExtra);
  });
  if (hasExtra) {
    addSep();
    addLink(extraSegment, undefined, true);
  }
  el.querySelectorAll("a:not(.current)").forEach(a => {
    a.addEventListener("click", () => {
      if (!editorView.classList.contains("hidden")) closeEditor();
      loadDir(a.dataset.path);
    });
  });
}

function renderBreadcrumb(path) {
  renderBreadcrumbInto(breadcrumb, path);
}

function escapeHtml(s) {
  const d = document.createElement("div");
  d.textContent = s;
  return d.innerHTML;
}

function formatDate(ms) {
  const diffSec = Math.floor((Date.now() - ms) / 1000);
  if (diffSec < 5) return "just now";
  if (diffSec < 60) return diffSec + (diffSec === 1 ? " second ago" : " seconds ago");
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return diffMin + (diffMin === 1 ? " minute ago" : " minutes ago");
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return diffHour + (diffHour === 1 ? " hour ago" : " hours ago");
  const diffDay = Math.floor(diffHour / 24);
  if (diffDay === 1) return "Yesterday";
  if (diffDay < 7) return diffDay + " days ago";
  const d = new Date(ms);
  const now = new Date();
  return d.toLocaleDateString([], { month: "short", day: "numeric", year: d.getFullYear() !== now.getFullYear() ? "numeric" : undefined });
}

function formatExactDate(ms) {
  const d = new Date(ms);
  return d.toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" }) +
    ", " + d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

const TEXT_EXT = [".txt", ".yml", ".yaml", ".json", ".properties", ".log", ".cfg", ".conf",
                  ".java", ".js", ".css", ".html", ".xml", ".md", ".sh", ".toml"];

// Known binary types always download. Unknown or extension-less files open in
// the editor when they are small enough; the server still rejects binary content.
const BINARY_EXT = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "zip", "jar", "tar", "gz",
                    "rar", "7z", "class", "exe", "dll", "so", "dat", "mca", "nbt", "db", "sqlite",
                    "mp3", "mp4", "ogg", "wav", "pdf"];
const UNKNOWN_EDIT_MAX_BYTES = 512 * 1024;

function isTextEditable(name, size) {
  const lower = name.toLowerCase();
  if (TEXT_EXT.some(ext => lower.endsWith(ext))) return true;
  if (BINARY_EXT.includes(extOf(name))) return false;
  return size === undefined || size <= UNKNOWN_EDIT_MAX_BYTES;
}

function extOf(name) {
  const i = name.lastIndexOf(".");
  return i === -1 ? "" : name.slice(i + 1).toLowerCase();
}

// ---- File type icons ----
const ICON_ARCHIVE = ["zip", "jar", "tar", "gz", "rar", "7z"];
const ICON_IMAGE = ["png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp"];
const ICON_CODE = ["java", "js", "ts", "py", "sh", "css", "html", "xml", "json", "yml", "yaml", "toml", "properties"];
const ICON_DOC = ["txt", "md", "log", "conf", "cfg"];

function iconSvg(kind, color) {
  const c = `color:${color}`;
  switch (kind) {
    case "folder":
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><path d="M2.5 5.5a1 1 0 011-1h4l1.5 2h7.5a1 1 0 011 1v7.5a1 1 0 01-1 1h-13a1 1 0 01-1-1v-9.5z" fill="currentColor" opacity=".18" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>`;
    case "archive":
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><rect x="4" y="3" width="12" height="14" rx="1.2" stroke="currentColor" stroke-width="1.3"/><path d="M9 3v2M11 5v2M9 7v2M11 9v2M9 11v2" stroke="currentColor" stroke-width="1.2"/><circle cx="10" cy="14" r="1.1" stroke="currentColor" stroke-width="1.1"/></svg>`;
    case "image":
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><rect x="3" y="4" width="14" height="12" rx="1.2" stroke="currentColor" stroke-width="1.3"/><circle cx="7.2" cy="8" r="1.2" stroke="currentColor" stroke-width="1.1"/><path d="M4 14.5l4-4 2.5 2.5 2-2L16 15" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>`;
    case "code":
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><path d="M7 6L3.5 10 7 14M13 6l3.5 4-3.5 4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    case "doc":
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><path d="M5 3h6l4 4v10a1 1 0 01-1 1H5a1 1 0 01-1-1V4a1 1 0 011-1z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M11 3v4h4" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M6.5 11h7M6.5 13.5h7M6.5 8.5h3" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>`;
    default:
      return `<svg viewBox="0 0 20 20" fill="none" style="${c}"><path d="M5 3h6l4 4v10a1 1 0 01-1 1H5a1 1 0 01-1-1V4a1 1 0 011-1z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M11 3v4h4" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>`;
  }
}

function iconFor(entry) {
  if (entry.isDir) return iconSvg("folder", "var(--folder)");
  const ext = extOf(entry.name);
  if (ICON_ARCHIVE.includes(ext)) return iconSvg("archive", "var(--warning)");
  if (ICON_IMAGE.includes(ext)) return iconSvg("image", "var(--accent-b)");
  if (ICON_CODE.includes(ext)) return iconSvg("code", "var(--accent-a)");
  if (ICON_DOC.includes(ext)) return iconSvg("doc", "var(--text-dim)");
  return iconSvg("file", "var(--text-dim)");
}

// ---- Load / render ----

// Loads a folder. Reloading the folder you are already in (after an upload, delete,
// rename, etc.) keeps the scroll position, selection and filter, and does not hide the
// table. Opening a different folder starts at the top with a clean state.
let hasLoadedDir = false;

let loadSeq = 0;
// Fingerprint and names of the folder currently shown.
let currentListEtag = null;
let currentNames = null;

// Loads a folder listing. 
async function loadDir(path, prefetched, fromWatch) {
  const mySeq = ++loadSeq;
  const isRefresh = hasLoadedDir && path === currentPath;
  if (!isRefresh) {
    searchInput.value = "";
    selectedPaths.clear();
    lastCheckedPath = null;
    table.style.display = "none";
    emptyState.classList.add("hidden");
    loadingState.classList.remove("hidden");
  }
  try {
    let res = prefetched;
    if (!res) {
      try {
        res = await api("/api/list?path=" + encodeURIComponent(path) + "&cid=" + CLIENT_ID);
      } catch {
        // Offline or the server is unreachable: say so, instead of an uncaught error.
        if (mySeq === loadSeq) showToast("Can't reach the server. Check your connection and try again.", true);
        return;
      }
    }
    // A newer navigation started while this one was loading: drop this (older) reply, so
    // the list and the breadcrumb always show the same folder.
    if (mySeq !== loadSeq) return;
    // A server error while opening a folder: try once more before giving up.
    if (!res.ok && !prefetched && !isRefresh && res.status >= 500) {
      await new Promise(r => setTimeout(r, 500));
      if (mySeq !== loadSeq) return;
      try { res = await api("/api/list?path=" + encodeURIComponent(path) + "&cid=" + CLIENT_ID); } catch { /* handled below */ }
      if (mySeq !== loadSeq) return;
    }
    if (!res.ok) {
      showToast("Failed to open folder (error " + res.status + ")", true);
      // Keep showing the folder we were in, instead of an empty page.
      if (hasLoadedDir && currentEntries && currentEntries.length) table.style.display = "";
      return;
    }
    const data = await res.json();
    if (mySeq !== loadSeq) return;
    const scrollBefore = browserView.scrollTop;
    const previousNames = currentNames;
    currentPath = path;
    currentEntries = data.entries;
    currentListEtag = res.headers.get("ETag");
    currentNames = new Set(data.entries.map(e => e.name));
    if (isRefresh) {
      // Drop selections for items that no longer exist; keep the rest.
      const existing = new Set(currentEntries.map(e => joinPath(currentPath, e.name)));
      for (const p of Array.from(selectedPaths)) {
        if (!existing.has(p)) selectedPaths.delete(p);
      }
      if (lastCheckedPath && !existing.has(lastCheckedPath)) lastCheckedPath = null;
    }
    renderBreadcrumb(data.path);
    applySortAndRender();
    hasLoadedDir = true;
    browserView.scrollTop = isRefresh ? scrollBefore : 0;
    if (fromWatch && isRefresh && previousNames) {
      const added = [...currentNames].filter(n => !previousNames.has(n)).length;
      if (added > 0) showToast(added === 1 ? "1 new item in this folder" : `${added} new items in this folder`);
    }
  } finally {
    if (mySeq === loadSeq) loadingState.classList.add("hidden");
  }
}

// ---- Presence: requests only while someone is actually using the page ----
// The page counts as in use when its tab is visible AND either the window has focus or
// the user touched the page within the last minute. A background tab, another window on
// top, or an unattended page sends nothing until you come back, like the Pterodactyl
// panel does.
const PRESENCE_IDLE_MS = 60000;
const PRESENCE_CHECK_MS = 5000;
let lastUserInputAt = Date.now();
let presenceActive = null;

function pageActive() {
  if (document.hidden) return false;
  return document.hasFocus() || Date.now() - lastUserInputAt < PRESENCE_IDLE_MS;
}

// Runs whenever presence may have changed. Stops the requests on the way out and starts
// them again on the way back, with an immediate catch-up.
function updatePresence() {
  const active = pageActive();
  if (active === presenceActive) return;
  presenceActive = active;
  if (active) {
    if (consoleIsShowing()) {
      consolePollDelay = CONSOLE_POLL_FAST_MS;
      scheduleConsolePoll(0);
    }
  } else {
    abortConsolePoll();
    clearTimeout(consolePollTimer);
    consolePollTimer = null;
  }
}

["pointerdown", "keydown", "touchstart", "wheel"].forEach(evt =>
  window.addEventListener(evt, () => {
    lastUserInputAt = Date.now();
    updatePresence();
  }, { passive: true })
);
window.addEventListener("focus", updatePresence);
window.addEventListener("blur", updatePresence);
document.addEventListener("visibilitychange", updatePresence);
// Catches the "unattended for a minute" case, which fires no event.
setInterval(updatePresence, PRESENCE_CHECK_MS);


function sortedEntries(entries) {
  const dirs = entries.filter(e => e.isDir);
  const files = entries.filter(e => !e.isDir);
  const cmp = (a, b) => {
    let av, bv;
    if (sortKey === "size") { av = a.size; bv = b.size; }
    else if (sortKey === "modified") { av = a.modified; bv = b.modified; }
    else { av = a.name.toLowerCase(); bv = b.name.toLowerCase(); }
    if (av < bv) return -1 * sortDir;
    if (av > bv) return 1 * sortDir;
    return 0;
  };
  dirs.sort(cmp);
  files.sort(cmp);
  return sortKey === "name" ? [...dirs, ...files] : [...dirs, ...files].sort(cmp);
}

function applySortAndRender() {
  document.querySelectorAll("th.sortable").forEach(th => {
    th.classList.toggle("active", th.dataset.sort === sortKey);
    th.querySelector(".sort-arrow").textContent = th.dataset.sort === sortKey ? (sortDir === 1 ? "▲" : "▼") : "";
  });
  const filter = searchInput.value.trim().toLowerCase();
  const entries = sortedEntries(currentEntries);
  renderTable(entries, filter);
}

document.querySelectorAll("th.sortable").forEach(th => {
  th.addEventListener("click", () => {
    const key = th.dataset.sort;
    if (sortKey === key) sortDir *= -1; else { sortKey = key; sortDir = 1; }
    applySortAndRender();
  });
});

searchInput.addEventListener("input", applySortAndRender);

// Rows are kept in the DOM and updated in place instead of being torn down and rebuilt
// every render. A folder that changes often (logs, world saves, files this plugin writes)
// can trigger a render several times a second rebuilding the
// whole table each time would destroy and recreate the row under the mouse on every
// update, which breaks hover and can make a click land on nothing. Reusing the same <tr>
// for unchanged rows avoids that.
const rowByPath = new Map(); // fullPath -> { tr, state, isDir, checkbox, nameText, iconTd, sizeTd, modTd }
let currentVisiblePaths = []; // kept current for shift-click range selection in row listeners

function renderTable(entries, filter) {
  const visibleFullPaths = [];
  const orderedRows = [];
  const touched = new Set();

  for (const entry of entries) {
    const matches = !filter || entry.name.toLowerCase().includes(filter);
    if (!matches) continue;

    const fullPath = joinPath(currentPath, entry.name);
    visibleFullPaths.push(fullPath);

    let row = rowByPath.get(fullPath);
    if (row && row.isDir !== entry.isDir) {
      // Same name, but a file became a folder (or the reverse): treat as a different row.
      row.tr.remove();
      rowByPath.delete(fullPath);
      row = undefined;
    }

    if (!row) {
      row = buildRow(entry, fullPath);
      rowByPath.set(fullPath, row);
    } else {
      updateRow(row, entry, fullPath);
    }
    touched.add(fullPath);
    orderedRows.push(row.tr);
  }

  // Rows for entries that no longer exist here (deleted, renamed, or filtered out).
  for (const [path, row] of rowByPath) {
    if (!touched.has(path)) {
      row.tr.remove();
      rowByPath.delete(path);
    }
  }

  // Match the sorted/filtered order. Rows already in the right place are left untouched;
  // inserting an existing node elsewhere moves it rather than recreating it.
  for (let i = 0; i < orderedRows.length; i++) {
    if (tableBody.children[i] !== orderedRows[i]) {
      tableBody.insertBefore(orderedRows[i], tableBody.children[i] || null);
    }
  }

  currentVisiblePaths = visibleFullPaths;
  const visibleCount = visibleFullPaths.length;

  itemCount.textContent = entries.length ? `${visibleCount} of ${entries.length} item${entries.length === 1 ? "" : "s"}` : "";
  updateSelectAllState(visibleFullPaths);
  updateBulkBar();

  if (visibleCount === 0) {
    table.style.display = "none";
    emptyState.classList.remove("hidden");
    if (filter) {
      emptyTitle.textContent = "No matches";
      emptySub.textContent = `Nothing in this folder matches "${filter}".`;
    } else {
      emptyTitle.textContent = "This folder is empty";
      emptySub.textContent = "Upload a file or create a folder to get started.";
    }
  } else {
    table.style.display = "";
    emptyState.classList.add("hidden");
  }
}

// Builds a new row. Interactive handlers read row.state.entry / row.state.fullPath (never
// the entry/fullPath captured here directly), so a later updateRow() can refresh what a
// reused row's handlers see without re-attaching any listeners.
function buildRow(entry, fullPath) {
  const state = { entry, fullPath };
  const tr = document.createElement("tr");

  const checkTd = document.createElement("td");
  checkTd.className = "col-check";
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.className = "row-checkbox";
  checkbox.setAttribute("aria-label", "Select " + state.entry.name);
  checkbox.addEventListener("click", (e) => {
    e.stopPropagation();
    const path = state.fullPath;
    const newState = checkbox.checked;
    if (e.shiftKey && lastCheckedPath !== null) {
      const i1 = currentVisiblePaths.indexOf(lastCheckedPath);
      const i2 = currentVisiblePaths.indexOf(path);
      if (i1 !== -1 && i2 !== -1) {
        const [start, end] = i1 < i2 ? [i1, i2] : [i2, i1];
        for (let i = start; i <= end; i++) {
          if (newState) selectedPaths.add(currentVisiblePaths[i]); else selectedPaths.delete(currentVisiblePaths[i]);
        }
      }
    } else if (newState) {
      selectedPaths.add(path);
    } else {
      selectedPaths.delete(path);
    }
    lastCheckedPath = path;
    applySortAndRender();
  });
  checkTd.appendChild(checkbox);
  tr.appendChild(checkTd);

  const iconTd = document.createElement("td");
  iconTd.className = "col-icon";
  tr.appendChild(iconTd);

  const nameTd = document.createElement("td");
  const nameCell = document.createElement("div");
  nameCell.className = "name-cell";
  nameCell.tabIndex = 0;
  const nameText = document.createElement("span");
  nameText.className = "name-text";
  nameText.textContent = state.entry.name;
  nameCell.appendChild(nameText);
  const open = () => {
    const e = state.entry, p = state.fullPath;
    if (e.isDir) {
      loadDir(p);
    } else if (isTextEditable(e.name, e.size)) {
      openEditor(p);
    } else {
      window.location.href = "/api/download?path=" + encodeURIComponent(p);
    }
  };
  nameCell.addEventListener("click", open);
  nameCell.addEventListener("keydown", (e) => { if (e.key === "Enter") open(); });
  nameTd.appendChild(nameCell);
  tr.appendChild(nameTd);

  const sizeTd = document.createElement("td");
  sizeTd.className = "dim";
  tr.appendChild(sizeTd);

  const modTd = document.createElement("td");
  modTd.className = "dim";
  tr.appendChild(modTd);

  const actionsTd = document.createElement("td");
  actionsTd.className = "row-actions";
  const kebabBtn = document.createElement("button");
  kebabBtn.className = "kebab-btn";
  kebabBtn.innerHTML = `<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4.5" r="1.4" fill="currentColor"/><circle cx="10" cy="10" r="1.4" fill="currentColor"/><circle cx="10" cy="15.5" r="1.4" fill="currentColor"/></svg>`;
  kebabBtn.title = "Actions";
  kebabBtn.setAttribute("aria-label", "Actions for " + state.entry.name);
  kebabBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    openRowMenu(kebabBtn, state.entry, state.fullPath);
  });
  actionsTd.appendChild(kebabBtn);
  tr.appendChild(actionsTd);

  // Right-click opens the same actions menu at the pointer.
  tr.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    openRowMenu(tr, state.entry, state.fullPath, { x: e.clientX, y: e.clientY });
  });

  // Long-press on a touch screen opens the same menu. The tap that ends the press is
  // cancelled, so the file does not also open.
  let pressTimer = null;
  let longPressed = false;
  tr.addEventListener("touchstart", (e) => {
    longPressed = false;
    const t = e.touches[0];
    const x = t.clientX;
    const y = t.clientY;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      longPressed = true;
      openRowMenu(tr, state.entry, state.fullPath, { x, y });
    }, 500);
  }, { passive: true });
  tr.addEventListener("touchmove", () => clearTimeout(pressTimer), { passive: true });
  tr.addEventListener("touchcancel", () => clearTimeout(pressTimer), { passive: true });
  tr.addEventListener("touchend", (e) => {
    clearTimeout(pressTimer);
    if (longPressed) e.preventDefault();
  });

  const row = { tr, state, isDir: entry.isDir, checkbox, nameText, iconTd, sizeTd, modTd };
  applyRowFields(row, entry, fullPath);
  return row;
}

// Refreshes a reused row's data and visible fields without touching its DOM identity, so
// hover, focus and any open menu anchored to it stay intact.
function updateRow(row, entry, fullPath) {
  row.state.entry = entry;
  row.state.fullPath = fullPath;
  applyRowFields(row, entry, fullPath);
}

function applyRowFields(row, entry, fullPath) {
  const icon = `<span class="file-icon">${iconFor(entry)}</span>`;
  if (row.iconTd.innerHTML !== icon) row.iconTd.innerHTML = icon;
  if (row.nameText.textContent !== entry.name) row.nameText.textContent = entry.name;
  const size = entry.isDir ? "\u2014" : entry.sizeHuman;
  if (row.sizeTd.textContent !== size) row.sizeTd.textContent = size;
  const modText = formatDate(entry.modified);
  if (row.modTd.textContent !== modText) row.modTd.textContent = modText;
  row.modTd.title = formatExactDate(entry.modified);
  const selected = selectedPaths.has(fullPath);
  row.tr.classList.toggle("selected", selected);
  row.checkbox.checked = selected;
  row.checkbox.setAttribute("aria-label", "Select " + entry.name);
}


// ---- Selection / bulk actions ----

function updateSelectAllState(visibleFullPaths) {
  if (visibleFullPaths.length === 0) {
    selectAllCheckbox.checked = false;
    selectAllCheckbox.indeterminate = false;
    return;
  }
  const selectedCount = visibleFullPaths.filter(p => selectedPaths.has(p)).length;
  selectAllCheckbox.checked = selectedCount === visibleFullPaths.length;
  selectAllCheckbox.indeterminate = selectedCount > 0 && selectedCount < visibleFullPaths.length;
}

function updateBulkBar() {
  const count = selectedPaths.size;
  bulkBar.classList.toggle("hidden", count === 0);
  bulkCount.textContent = count === 1 ? "1 selected" : `${count} selected`;

  const selectedEntries = currentEntries.filter(e => selectedPaths.has(joinPath(currentPath, e.name)));
  const singleFile = count === 1 && selectedEntries.length === 1 && !selectedEntries[0].isDir;
  const singleZip = singleFile && extOf(selectedEntries[0].name) === "zip";
  bulkCompressBtn.disabled = count === 0;
  bulkExtractBtn.disabled = !singleZip;
  bulkDownloadBtn.disabled = !singleFile;
  bulkRenameBtn.disabled = count !== 1;
  bulkDeleteBtn.disabled = count === 0;
}

selectAllCheckbox.addEventListener("change", () => {
  const filter = searchInput.value.trim().toLowerCase();
  const visible = sortedEntries(currentEntries).filter(e => !filter || e.name.toLowerCase().includes(filter));
  if (selectAllCheckbox.checked) {
    visible.forEach(e => selectedPaths.add(joinPath(currentPath, e.name)));
  } else {
    visible.forEach(e => selectedPaths.delete(joinPath(currentPath, e.name)));
  }
  applySortAndRender();
});

bulkClearBtn.addEventListener("click", () => {
  selectedPaths.clear();
  lastCheckedPath = null;
  applySortAndRender();
});

bulkCompressBtn.addEventListener("click", async () => {
  if (selectedPaths.size === 0) return;
  if (await compressPaths(Array.from(selectedPaths))) selectedPaths.clear();
});

// Asks for an archive name, then zips the given paths (files or folders) into the
// current folder. Resolves true when the archive was created.
async function compressPaths(paths) {
  const defaultName = paths.length === 1
    ? paths[0].split("/").pop().replace(/\.[^./]+$/, "") + ".zip"
    : "archive.zip";
  let name = await promptDialog("Archive name", defaultName, "Compress");
  if (!name) return false;
  if (!name.toLowerCase().endsWith(".zip")) name += ".zip";
  const dest = joinPath(currentPath, name);
  const r = await api("/api/zip?dest=" + encodeURIComponent(dest), { method: "POST", body: paths.join("\n") });
  if (r.ok) {
    showToast("Archive created");
    loadDir(currentPath);
    return true;
  }
  const data = await r.json().catch(() => ({}));
  showToast(data.error || "Failed to create archive", true);
  return false;
}

bulkExtractBtn.addEventListener("click", async () => {
  if (bulkExtractBtn.disabled || selectedPaths.size !== 1) return;
  const [path] = selectedPaths;
  if (await extractZip(path)) {
    selectedPaths.clear();
    loadDir(currentPath);
  }
});

// Extracts a zip into a sibling folder. If that folder already exists, asks before
// replacing its files with the same names, then retries with overwrite=true.
// Resolves true when the archive was extracted.
async function extractZip(path, overwrite = false) {
  const url = "/api/unzip?path=" + encodeURIComponent(path) + (overwrite ? "&overwrite=true" : "");
  const r = await api(url, { method: "POST" });
  const data = await r.json().catch(() => ({}));
  if (r.ok) {
    const n = data.extracted;
    showToast(n === undefined ? "Extracted" : `Extracted ${n} file${n === 1 ? "" : "s"}`);
    return true;
  }
  if (r.status === 409 && data.canOverwrite && !overwrite) {
    const ok = await confirmDialog(
      "Replace existing files?",
      "The folder already exists. Files in the zip with the same names will be <strong>replaced</strong>. Other files in the folder are kept."
    );
    return ok ? extractZip(path, true) : false;
  }
  showToast(data.error || "Extract failed", true);
  return false;
}

bulkDownloadBtn.addEventListener("click", () => {
  if (bulkDownloadBtn.disabled || selectedPaths.size !== 1) return;
  const [path] = selectedPaths;
  window.location.href = "/api/download?path=" + encodeURIComponent(path);
});

bulkRenameBtn.addEventListener("click", () => {
  if (bulkRenameBtn.disabled || selectedPaths.size !== 1) return;
  const [path] = selectedPaths;
  const name = path.split("/").pop();
  promptDialog("Rename to", name, "Rename").then(newName => {
    if (!newName || newName === name) return;
    const parent = path.split("/").slice(0, -1).join("/");
    const newPath = joinPath(parent, newName);
    api("/api/rename?path=" + encodeURIComponent(path) + "&newPath=" + encodeURIComponent(newPath), { method: "POST" })
      .then(r => {
        if (r.ok) {
          showToast("Renamed");
          selectedPaths.clear();
          loadDir(currentPath);
        } else {
          showToast("Rename failed", true);
        }
      });
  });
});

bulkDeleteBtn.addEventListener("click", () => {
  const count = selectedPaths.size;
  if (count === 0) return;
  confirmDialog(
    count === 1 ? "Delete item?" : `Delete ${count} items?`,
    count === 1
      ? "This will permanently delete the selected item. This cannot be undone."
      : `This will permanently delete all <strong>${count}</strong> selected items. This cannot be undone.`
  ).then(async ok => {
    if (!ok) return;
    const paths = Array.from(selectedPaths);
    let okCount = 0;
    for (const p of paths) {
      const r = await api("/api/delete?path=" + encodeURIComponent(p), { method: "POST" });
      if (r.ok) okCount++;
    }
    selectedPaths.clear();
    lastCheckedPath = null;
    showToast(okCount === paths.length ? `Deleted ${okCount}` : `Deleted ${okCount} of ${paths.length}`, okCount !== paths.length);
    loadDir(currentPath);
  });
});

// ---- Syntax highlighting engine ----
// Lightweight regex-based tokenizer. No external deps (this ships inside a
// plugin jar and may run with no internet access), tuned for the config
// formats a Minecraft server admin actually opens here.

function escapeHtmlText(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function buildHighlighter(rules, flags) {
  const combined = new RegExp(rules.map(r => "(" + r.re.source + ")").join("|"), (flags || "") + "g");
  return function (text) {
    let out = "";
    let lastIndex = 0;
    let m;
    combined.lastIndex = 0;
    while ((m = combined.exec(text))) {
      if (m.index > lastIndex) out += escapeHtmlText(text.slice(lastIndex, m.index));
      for (let i = 0; i < rules.length; i++) {
        if (m[i + 1] !== undefined) {
          out += `<span class="tok-${rules[i].type}">${escapeHtmlText(m[i + 1])}</span>`;
          break;
        }
      }
      lastIndex = combined.lastIndex;
      if (m[0].length === 0) combined.lastIndex++;
    }
    out += escapeHtmlText(text.slice(lastIndex));
    return out;
  };
}

const CLIKE_KEYWORDS = "public|private|protected|static|final|void|class|interface|enum|extends|implements|" +
  "import|package|new|return|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|" +
  "throw|throws|this|super|instanceof|const|let|var|function|export|from|async|await|typeof";

const HIGHLIGHTERS = {
  yaml: buildHighlighter([
    { type: "comment", re: /#.*/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/ },
    { type: "key", re: /^[ \t]*(?:-[ \t]*)?[A-Za-z0-9_.\-]+(?=[ \t]*:(?:\s|$))/ },
    { type: "bool", re: /\b(?:true|false|null|yes|no|~)\b/i },
    { type: "number", re: /\b-?\d+(?:\.\d+)?\b/ },
  ], "m"),

  json: buildHighlighter([
    { type: "key", re: /"(?:[^"\\]|\\.)*"(?=\s*:)/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"/ },
    { type: "bool", re: /\b(?:true|false|null)\b/ },
    { type: "number", re: /-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/ },
    { type: "punct", re: /[{}[\],:]/ },
  ]),

  properties: buildHighlighter([
    { type: "comment", re: /^[ \t]*[#!].*/ },
    { type: "key", re: /^[ \t]*[A-Za-z0-9_.\-]+(?=[ \t]*[:=])/ },
  ], "m"),

  toml: buildHighlighter([
    { type: "comment", re: /#.*/ },
    { type: "section", re: /^[ \t]*\[[^\]]*\]/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/ },
    { type: "key", re: /^[ \t]*[A-Za-z0-9_.\-]+(?=[ \t]*=)/ },
    { type: "bool", re: /\b(?:true|false)\b/ },
    { type: "number", re: /\b-?\d+(?:\.\d+)?\b/ },
  ], "m"),

  markup: buildHighlighter([
    { type: "comment", re: /<!--[\s\S]*?-->/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/ },
    { type: "tag", re: /<\/?[A-Za-z][A-Za-z0-9:-]*/ },
    { type: "attr", re: /\b[A-Za-z_:][A-Za-z0-9_:.\-]*(?=\s*=)/ },
    { type: "punct", re: /[<>/=]/ },
  ]),

  clike: buildHighlighter([
    { type: "comment", re: /\/\/.*|\/\*[\s\S]*?\*\// },
    { type: "string", re: /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`/ },
    { type: "keyword", re: new RegExp("\\b(?:" + CLIKE_KEYWORDS + ")\\b") },
    { type: "bool", re: /\b(?:true|false|null|undefined)\b/ },
    { type: "number", re: /\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?[fFdDlL]?\b/ },
  ]),

  shell: buildHighlighter([
    { type: "comment", re: /#.*/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"|'[^']*'/ },
    { type: "keyword", re: /\b(?:if|then|else|elif|fi|for|in|do|done|function|case|esac|export|local)\b/ },
    { type: "key", re: /\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/ },
  ]),

  markdown: buildHighlighter([
    { type: "heading", re: /^#{1,6}[ \t].*/ },
    { type: "string", re: /`[^`]+`/ },
    { type: "link", re: /\[[^\]]*\]\([^)]*\)/ },
    { type: "keyword", re: /\*\*[^*]+\*\*/ },
  ], "m"),

  generic: buildHighlighter([
    { type: "comment", re: /#.*/ },
    { type: "string", re: /"(?:[^"\\]|\\.)*"/ },
    { type: "number", re: /\b\d+(?:\.\d+)?\b/ },
  ]),
};

const LANG_LABEL = {
  yaml: "YAML", json: "JSON", properties: "Properties", toml: "TOML",
  markup: "Markup", clike: "Code", shell: "Shell", markdown: "Markdown", generic: "Text",
};

function familyFor(name) {
  const ext = extOf(name);
  if (ext === "yml" || ext === "yaml") return "yaml";
  if (ext === "json") return "json";
  if (ext === "properties") return "properties";
  if (ext === "toml") return "toml";
  if (ext === "xml" || ext === "html") return "markup";
  if (ext === "java" || ext === "js" || ext === "css") return "clike";
  if (ext === "sh") return "shell";
  if (ext === "md") return "markdown";
  return "generic";
}

const MAX_HIGHLIGHT_LEN = 400000; // skip highlighting on very large files to stay snappy

// ---- Full-page editor view ----
const browserView = document.getElementById("browserView");
const editorView = document.getElementById("editorView");
const editorBreadcrumb = document.getElementById("editorBreadcrumb");
const editorLang = document.getElementById("editorLang");
const editorLineCount = document.getElementById("editorLineCount");
const editorTextarea = document.getElementById("editorTextarea");
const editorHighlight = document.getElementById("editorHighlight");
const editorHighlightCode = editorHighlight.querySelector("code");
const editorGutter = document.getElementById("editorGutter");
let editingPath = null;
let editingFamily = "generic";

function refreshGutter() {
  const lines = editorTextarea.value.split("\n").length;
  if (refreshGutter._n !== lines) {
    refreshGutter._n = lines;
    let html = "";
    for (let i = 1; i <= lines; i++) html += `<div>${i}</div>`;
    editorGutter.innerHTML = html;
    editorLineCount.textContent = lines === 1 ? "1 line" : `${lines} lines`;
  }
}

function refreshHighlight() {
  const text = editorTextarea.value;
  if (text.length > MAX_HIGHLIGHT_LEN) {
    editorHighlightCode.textContent = "";
  } else {
    const fn = HIGHLIGHTERS[editingFamily] || HIGHLIGHTERS.generic;
    editorHighlightCode.innerHTML = fn(text) + "\n";
  }
  refreshGutter();
}

function syncEditorScroll() {
  editorHighlight.scrollTop = editorTextarea.scrollTop;
  editorHighlight.scrollLeft = editorTextarea.scrollLeft;
  editorGutter.scrollTop = editorTextarea.scrollTop;
  syncFindMarksScroll();
}

editorTextarea.addEventListener("input", refreshHighlight);
editorTextarea.addEventListener("scroll", syncEditorScroll);

async function openEditor(path, line) {
  const res = await api("/api/read?path=" + encodeURIComponent(path));
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    showToast(data.error || "Could not open file", true);
    return;
  }
  const data = await res.json();
  editingPath = path;
  const name = path.split("/").pop();
  editingFamily = familyFor(name);
  const parentPath = path.split("/").slice(0, -1).join("/");
  renderBreadcrumbInto(editorBreadcrumb, parentPath, name);
  editorLang.textContent = LANG_LABEL[editingFamily];
  editorTextarea.value = data.content;
  refreshGutter._n = null;
  refreshHighlight();
  browserView.classList.add("hidden");
  searchView.classList.add("hidden");
  if (pageTitleEl) pageTitleEl.textContent = PAGE_TITLES.editor;
  editorView.classList.remove("hidden");
  navConsole.classList.remove("active");
  navFiles.classList.add("active");
  stopConsolePolling();
  setTimeout(() => {
    editorTextarea.focus();
    const offset = line ? lineStartOffset(editorTextarea.value, line) : 0;
    editorTextarea.setSelectionRange(offset, offset);
    const lineHeight = parseFloat(getComputedStyle(editorTextarea).lineHeight) || 20;
    editorTextarea.scrollTop = line ? Math.max(0, line - 4) * lineHeight : 0;
    editorTextarea.scrollLeft = 0;
    syncEditorScroll();
  }, 0);
}

function closeEditor() {
  findBar.classList.add("hidden");
  clearFindMarks();
  editorView.classList.add("hidden");
  browserView.classList.remove("hidden");
}

document.getElementById("editorCancel").addEventListener("click", closeEditor);

async function saveEditor() {
  const res = await api("/api/write?path=" + encodeURIComponent(editingPath), {
    method: "POST",
    body: editorTextarea.value
  });
  if (res.ok) {
    showToast("Saved");
  } else {
    showToast("Save failed", true);
  }
}
document.getElementById("editorSave").addEventListener("click", saveEditor);
editorTextarea.addEventListener("keydown", (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === "s") {
    e.preventDefault();
    saveEditor();
  }
});

// ---- Editor: auto-indent, Tab / Shift+Tab, bracket auto-close ----
const OPEN_TO_CLOSE = { "(": ")", "[": "]", "{": "}" };
const CLOSERS = new Set([")", "]", "}"]);

function indentUnit() {
  return editingFamily === "clike" ? "    " : "  ";
}

function lineStartAt(text, pos) {
  return text.lastIndexOf("\n", pos - 1) + 1;
}

function lineEndAt(text, pos) {
  const nl = text.indexOf("\n", pos);
  return nl === -1 ? text.length : nl;
}

// Replaces [start, end) through execCommand so each edit stays in the browser's undo history.
function editorReplace(start, end, text, caretStart, caretEnd) {
  editorTextarea.focus();
  editorTextarea.setSelectionRange(start, end);
  if (text === "") {
    document.execCommand("delete");
  } else {
    document.execCommand("insertText", false, text);
  }
  const cs = caretStart;
  const ce = caretEnd === undefined ? caretStart : caretEnd;
  editorTextarea.setSelectionRange(cs, ce);
}

editorTextarea.addEventListener("keydown", (e) => {
  if (e.isComposing || e.metaKey || e.ctrlKey || e.altKey) return;

  const value = editorTextarea.value;
  const s = editorTextarea.selectionStart;
  const end = editorTextarea.selectionEnd;
  const nextChar = value.charAt(end);
  const prevChar = value.charAt(s - 1);

  // Enter: keep the current indent; add one level after an opening bracket or a YAML "key:".
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    const currentLine = value.slice(lineStartAt(value, s), s);
    const indent = currentLine.match(/^[ \t]*/)[0];
    const trimmed = currentLine.trimEnd();
    const unit = indentUnit();
    const afterOpener = "{[(".includes(trimmed.slice(-1));

    if (afterOpener && CLOSERS.has(nextChar)) {
      // Caret sits between "{" and "}": open a middle line and put the closer on its own line.
      const head = "\n" + indent + unit;
      editorReplace(s, end, head + "\n" + indent, s + head.length);
    } else {
      const extra = afterOpener || (editingFamily === "yaml" && trimmed.endsWith(":")) ? unit : "";
      const text = "\n" + indent + extra;
      editorReplace(s, end, text, s + text.length);
    }
    return;
  }

  // Tab inserts an indent; with a multi-line selection it indents every selected line.
  // Shift+Tab removes one indent level from the selected lines.
  if (e.key === "Tab") {
    e.preventDefault();
    const unit = indentUnit();
    const multiLine = value.slice(s, end).includes("\n");
    if (!e.shiftKey && !multiLine) {
      editorReplace(s, end, unit, s + unit.length);
      return;
    }
    const blockStart = lineStartAt(value, s);
    const blockEnd = lineEndAt(value, end);
    const lines = value.slice(blockStart, blockEnd).split("\n");
    const changed = e.shiftKey
      ? lines.map((l) => {
          if (l.startsWith(unit)) return l.slice(unit.length);
          if (l.startsWith("\t")) return l.slice(1);
          const spaces = l.match(/^ */)[0].length;
          return l.slice(Math.min(spaces, unit.length));
        })
      : lines.map((l) => unit + l);
    const block = changed.join("\n");
    editorReplace(blockStart, blockEnd, block, blockStart, blockStart + block.length);
    return;
  }

  // Opening bracket: insert the pair when the next character is whitespace, end of text, or a closer.
  if (OPEN_TO_CLOSE[e.key] && s === end) {
    if (nextChar === "" || /\s/.test(nextChar) || CLOSERS.has(nextChar)) {
      e.preventDefault();
      editorReplace(s, end, e.key + OPEN_TO_CLOSE[e.key], s + 1);
      return;
    }
  }

  // Closing bracket typed right before the same closer: step over it instead of doubling it.
  if (CLOSERS.has(e.key) && s === end && nextChar === e.key) {
    e.preventDefault();
    editorTextarea.setSelectionRange(s + 1, s + 1);
    return;
  }

  // Backspace between an empty pair such as "()" removes both characters.
  if (e.key === "Backspace" && s === end && OPEN_TO_CLOSE[prevChar] === nextChar) {
    e.preventDefault();
    editorReplace(s - 1, s + 1, "", s - 1);
    return;
  }
});

// ---- Find in file (Ctrl+F while the editor is open) ----
const findBar = document.getElementById("findBar");
const findInput = document.getElementById("findInput");
const findCount = document.getElementById("findCount");
let findQuery = "";
let findMatches = [];
let findIndex = -1;

function editorFindOpen() {
  return !editorView.classList.contains("hidden") && !findBar.classList.contains("hidden");
}

// Start positions of every case-insensitive match of the query.
function findPositions(query) {
  const needle = query.toLowerCase();
  if (!needle) return [];
  const hay = editorTextarea.value.toLowerCase();
  const out = [];
  let i = hay.indexOf(needle);
  while (i !== -1) {
    out.push(i);
    i = hay.indexOf(needle, i + needle.length);
  }
  return out;
}

// ---- Find match highlights: a yellow box behind every match, orange for the current one ----
const findMarks = document.getElementById("findMarks");
const findMarksInner = document.getElementById("findMarksInner");
const MAX_FIND_MARKS = 2000;

// Moves the marks with the text as it scrolls.
function syncFindMarksScroll() {
  findMarksInner.style.transform = `translate(${-editorTextarea.scrollLeft}px, ${-editorTextarea.scrollTop}px)`;
}

let monoCharWidth = 0;
function measureMonoChar() {
  if (!monoCharWidth) {
    const ctx = document.createElement("canvas").getContext("2d");
    ctx.font = getComputedStyle(editorTextarea).font;
    monoCharWidth = ctx.measureText("0000000000").width / 10;
  }
  return monoCharWidth;
}

// Draws one box per match. Columns account for tab stops, so boxes line up with the text.
function renderFindMarks() {
  findMarksInner.innerHTML = "";
  if (!editorFindOpen() || !findQuery || findMatches.length === 0) return;

  const cs = getComputedStyle(editorTextarea);
  const padLeft = parseFloat(cs.paddingLeft) || 0;
  const padTop = parseFloat(cs.paddingTop) || 0;
  const lineH = parseFloat(cs.lineHeight) || 20;
  const cw = measureMonoChar();
  const tabSize = parseInt(cs.tabSize, 10) || 8;
  const text = editorTextarea.value;
  const len = findQuery.length;

  const frag = document.createDocumentFragment();
  let line = 0;
  let col = 0;
  let idx = 0;
  const count = Math.min(findMatches.length, MAX_FIND_MARKS);
  for (let k = 0; k < count; k++) {
    const pos = findMatches[k];
    // Matches are in ascending order, so walk the text forward once.
    while (idx < pos) {
      const ch = text[idx];
      if (ch === "\n") { line++; col = 0; }
      else if (ch === "\t") { col += tabSize - (col % tabSize); }
      else { col++; }
      idx++;
    }
    const box = document.createElement("div");
    box.className = "find-mark" + (k === findIndex ? " current" : "");
    box.style.left = (padLeft + col * cw) + "px";
    box.style.top = (padTop + line * lineH) + "px";
    box.style.width = (len * cw) + "px";
    box.style.height = lineH + "px";
    frag.appendChild(box);
  }
  findMarksInner.appendChild(frag);
  syncFindMarksScroll();
}

function clearFindMarks() {
  findMarksInner.innerHTML = "";
}

function updateFindCount() {
  if (!findQuery) findCount.textContent = "";
  else if (findMatches.length === 0) findCount.textContent = "No results";
  else findCount.textContent = `${findIndex + 1} of ${findMatches.length}`;
}

// Selects a match and scrolls the editor so its line is in view.
function gotoFindMatch(index) {
  const pos = findMatches[index];
  findIndex = index;
  editorTextarea.setSelectionRange(pos, pos + findQuery.length);
  const lineIdx = editorTextarea.value.slice(0, pos).split("\n").length - 1;
  const lh = parseFloat(getComputedStyle(editorTextarea).lineHeight) || 20;
  const top = lineIdx * lh;
  const bottom = top + lh;
  if (top < editorTextarea.scrollTop || bottom > editorTextarea.scrollTop + editorTextarea.clientHeight) {
    // Put the match about a third of the way down the view, not at the very edge.
    const third = Math.floor(editorTextarea.clientHeight / lh / 3);
    editorTextarea.scrollTop = Math.max(0, lineIdx - third) * lh;
  }
  syncEditorScroll();
  updateFindCount();
  renderFindMarks();
}

// Recomputes matches for the current query. When `keepPosition` is set, the match nearest
// the caret stays selected (used while typing in the file).
function refreshFindMatches(startAtCaret) {
  findQuery = findInput.value;
  findMatches = findPositions(findQuery);
  if (findMatches.length === 0) {
    findIndex = -1;
    updateFindCount();
    renderFindMarks();
    return;
  }
  let index = 0;
  if (startAtCaret) {
    const caret = editorTextarea.selectionStart;
    const found = findMatches.findIndex(p => p >= caret);
    index = found === -1 ? 0 : found;
  }
  gotoFindMatch(index);
}

function stepFind(direction) {
  if (findMatches.length === 0) return;
  const next = (findIndex + direction + findMatches.length) % findMatches.length;
  gotoFindMatch(next);
}

function openFind() {
  // Prefill with a single-line selection, like most editors do.
  const selected = editorTextarea.value.slice(editorTextarea.selectionStart, editorTextarea.selectionEnd);
  if (selected && !selected.includes("\n") && selected.length <= 200) {
    findInput.value = selected;
  }
  findBar.classList.remove("hidden");
  findInput.focus();
  findInput.select();
  refreshFindMatches(true);
}

function closeFind() {
  findBar.classList.add("hidden");
  clearFindMarks();
  editorTextarea.focus();
}

document.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === "f"
      && !editorView.classList.contains("hidden")) {
    e.preventDefault();
    openFind();
  }
});

findInput.addEventListener("input", () => refreshFindMatches(true));

findInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    stepFind(e.shiftKey ? -1 : 1);
  } else if (e.key === "Escape") {
    // Close only the find bar; a second Escape closes the file.
    e.preventDefault();
    e.stopPropagation();
    closeFind();
  }
});

document.getElementById("findNext").addEventListener("click", () => stepFind(1));
document.getElementById("findPrev").addEventListener("click", () => stepFind(-1));
document.getElementById("findClose").addEventListener("click", closeFind);

// Keep match positions correct while the file is edited with the find bar open.
editorTextarea.addEventListener("input", () => {
  if (editorFindOpen() && findQuery) {
    const keep = findIndex;
    findMatches = findPositions(findQuery);
    if (findMatches.length === 0) findIndex = -1;
    else findIndex = Math.min(Math.max(keep, 0), findMatches.length - 1);
    updateFindCount();
    renderFindMarks();
  }
});

// ---- Prompt modal (used for rename / new folder) ----
const promptModal = document.getElementById("promptModal");
const promptTitle = document.getElementById("promptTitle");
const promptInput = document.getElementById("promptInput");
const promptOkBtn = document.getElementById("promptOk");
let promptResolve = null;

function promptDialog(title, defaultValue, okLabel) {
  promptTitle.textContent = title;
  promptInput.value = defaultValue || "";
  promptOkBtn.textContent = okLabel || "OK";
  openModal(promptModal);
  setTimeout(() => { promptInput.focus(); promptInput.select(); }, 0);
  return new Promise(resolve => { promptResolve = resolve; });
}

function resolvePrompt(val) {
  closeModal(promptModal);
  if (promptResolve) { promptResolve(val); promptResolve = null; }
}

document.getElementById("promptClose").addEventListener("click", () => resolvePrompt(null));
document.getElementById("promptCancel").addEventListener("click", () => resolvePrompt(null));
document.getElementById("promptOk").addEventListener("click", () => resolvePrompt(promptInput.value.trim()));
promptInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") resolvePrompt(promptInput.value.trim());
});

// ---- Confirm modal (delete) ----
const confirmModal = document.getElementById("confirmModal");
const confirmTitle = document.getElementById("confirmTitle");
const confirmBody = document.getElementById("confirmBody");
let confirmResolve = null;

function confirmDialog(title, bodyHtml) {
  confirmTitle.textContent = title;
  confirmBody.innerHTML = bodyHtml;
  openModal(confirmModal);
  setTimeout(() => document.getElementById("confirmOk").focus(), 0);
  return new Promise(resolve => { confirmResolve = resolve; });
}

function resolveConfirm(val) {
  closeModal(confirmModal);
  if (confirmResolve) { confirmResolve(val); confirmResolve = null; }
}

document.getElementById("confirmClose").addEventListener("click", () => resolveConfirm(false));
document.getElementById("confirmCancel").addEventListener("click", () => resolveConfirm(false));
document.getElementById("confirmOk").addEventListener("click", () => resolveConfirm(true));

// ---- Generic modal helpers ----
function openModal(modal) { modal.classList.remove("hidden"); }
function closeModal(modal) { modal.classList.add("hidden"); }

document.querySelectorAll(".modal").forEach(modal => {
  modal.addEventListener("mousedown", (e) => {
    if (e.target === modal) {
      if (modal === promptModal) resolvePrompt(null);
      else if (modal === confirmModal) resolveConfirm(false);
    }
  });
});

document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!rowMenu.classList.contains("hidden")) closeRowMenu();
  else if (editorFindOpen()) closeFind();
  else if (!editorView.classList.contains("hidden")) closeEditor();
  else if (!promptModal.classList.contains("hidden")) resolvePrompt(null);
  else if (!confirmModal.classList.contains("hidden")) resolveConfirm(false);
});

// ---- Per-row kebab action menu ----
const rowMenu = document.createElement("div");
rowMenu.className = "row-menu hidden";
document.body.appendChild(rowMenu);

function closeRowMenu() {
  rowMenu.classList.add("hidden");
}

function rowMenuItem(label, iconSvg, onClick, danger) {
  const item = document.createElement("button");
  item.className = "row-menu-item" + (danger ? " danger" : "");
  item.innerHTML = `<span class="row-menu-icon">${iconSvg}</span><span>${escapeHtml(label)}</span>`;
  item.addEventListener("click", (e) => {
    e.stopPropagation();
    closeRowMenu();
    onClick();
  });
  return item;
}

function openRowMenu(anchorBtn, entry, fullPath, point) {
  // A second click on the same ⋮ button closes the menu. A right-click always (re)opens it.
  if (!point && !rowMenu.classList.contains("hidden") && rowMenu._anchor === anchorBtn) {
    closeRowMenu();
    return;
  }
  rowMenu._anchor = anchorBtn;
  rowMenu.innerHTML = "";

  if (!entry.isDir) {
    rowMenu.appendChild(rowMenuItem("Download",
      `<svg viewBox="0 0 20 20" fill="none"><path d="M10 3v9M10 12l-3.5-3.5M10 12l3.5-3.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 15v1a1 1 0 001 1h10a1 1 0 001-1v-1" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>`,
      () => { window.location.href = "/api/download?path=" + encodeURIComponent(fullPath); }));
  }

  rowMenu.appendChild(rowMenuItem("Compress",
    `<svg viewBox="0 0 20 20" fill="none"><rect x="4" y="3" width="12" height="14" rx="1.2" stroke="currentColor" stroke-width="1.3"/><path d="M9 3v2M11 5v2M9 7v2M11 9v2M9 11v2" stroke="currentColor" stroke-width="1.2"/><circle cx="10" cy="14" r="1.1" stroke="currentColor" stroke-width="1.1"/></svg>`,
    () => { compressPaths([fullPath]); }));

  if (!entry.isDir && extOf(entry.name) === "zip") {
    rowMenu.appendChild(rowMenuItem("Extract",
      `<svg viewBox="0 0 20 20" fill="none"><rect x="3.5" y="8" width="13" height="9" rx="1.2" stroke="currentColor" stroke-width="1.3"/><path d="M10 3v7M10 3l-2.5 2.5M10 3l2.5 2.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
      async () => {
        if (await extractZip(fullPath)) loadDir(currentPath);
      }));
  }

  rowMenu.appendChild(rowMenuItem("Rename",
    `<svg viewBox="0 0 20 20" fill="none"><path d="M13.5 3.5l3 3L7 16H4v-3l9.5-9.5z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>`,
    () => {
      promptDialog("Rename to", entry.name, "Rename").then(newName => {
        if (!newName || newName === entry.name) return;
        const newPath = joinPath(currentPath, newName);
        api("/api/rename?path=" + encodeURIComponent(fullPath) + "&newPath=" + encodeURIComponent(newPath), { method: "POST" })
          .then(r => r.ok ? (showToast("Renamed"), loadDir(currentPath)) : showToast("Rename failed", true));
      });
    }));

  rowMenu.appendChild(rowMenuItem("Delete",
    `<svg viewBox="0 0 20 20" fill="none"><path d="M4.5 5.5h11M8 5.5v-1a1 1 0 011-1h2a1 1 0 011 1v1M8.5 9v5M11.5 9v5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/><path d="M5.5 5.5l.6 9.5a1 1 0 001 .9h5.8a1 1 0 001-.9l.6-9.5" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>`,
    () => {
      confirmDialog(
        entry.isDir ? "Delete folder?" : "Delete file?",
        `This will permanently delete <strong>${escapeHtml(entry.name)}</strong>. This cannot be undone.`
      ).then(ok => {
        if (!ok) return;
        api("/api/delete?path=" + encodeURIComponent(fullPath), { method: "POST" })
          .then(r => r.ok ? (showToast("Deleted"), loadDir(currentPath)) : showToast("Delete failed", true));
      });
    }, true));

  rowMenu.classList.remove("hidden");
  const menuRect = rowMenu.getBoundingClientRect();
  let top;
  let left;
  if (point) {
    // Open at the pointer, flipped left/up when there is no room on that side.
    left = point.x + window.scrollX;
    top = point.y + window.scrollY;
    if (point.x + menuRect.width > window.innerWidth - 8) left = point.x + window.scrollX - menuRect.width;
    if (point.y + menuRect.height > window.innerHeight - 8) top = point.y + window.scrollY - menuRect.height;
    left = Math.max(left, window.scrollX + 8);
    top = Math.max(top, window.scrollY + 8);
  } else {
    const btnRect = anchorBtn.getBoundingClientRect();
    top = btnRect.bottom + window.scrollY + 4;
    left = btnRect.right + window.scrollX - menuRect.width;
    if (left < 8) left = 8;
    if (top + menuRect.height > window.scrollY + window.innerHeight - 8) {
      top = btnRect.top + window.scrollY - menuRect.height - 4;
    }
  }
  rowMenu.style.top = top + "px";
  rowMenu.style.left = left + "px";
}

document.addEventListener("mousedown", (e) => {
  if (!rowMenu.classList.contains("hidden") && !rowMenu.contains(e.target) && e.target !== rowMenu._anchor
      && !(rowMenu._anchor && rowMenu._anchor.contains(e.target))) {
    closeRowMenu();
  }
});
window.addEventListener("scroll", () => closeRowMenu(), true);

// ---- New folder ----
document.getElementById("newFolderBtn").addEventListener("click", async () => {
  const name = await promptDialog("New folder name", "", "Create");
  if (!name) return;
  const res = await api("/api/mkdir?path=" + encodeURIComponent(joinPath(currentPath, name)), { method: "POST" });
  if (res.ok) { showToast("Folder created"); loadDir(currentPath); } else { showToast("Failed to create folder", true); }
});

// ---- New file ----
document.getElementById("newFileBtn").addEventListener("click", async () => {
  const name = await promptDialog("New file name", "", "Create");
  if (!name) return;
  if (currentEntries.some(e => e.name.toLowerCase() === name.toLowerCase())) {
    showToast("A file or folder named \"" + name + "\" already exists", true);
    return;
  }
  const path = joinPath(currentPath, name);
  const res = await api("/api/write?path=" + encodeURIComponent(path), { method: "POST", body: "" });
  if (res.ok) {
    showToast("File created");
    if (isTextEditable(name)) {
      openEditor(path);
    } else {
      loadDir(currentPath);
    }
  } else {
    showToast("Failed to create file", true);
  }
});

// ---- Upload (input + drag & drop, multiple files, sequential with progress) ----
function showUploadProgress(name, pct) {
  uploadProgress.classList.remove("hidden");
  uploadProgress.innerHTML = `<div class="upload-progress-name">Uploading ${escapeHtml(name)}…</div><div class="upload-progress-track"><div class="upload-progress-fill" style="width:${pct}%"></div></div>`;
}
function hideUploadProgress() { uploadProgress.classList.add("hidden"); }

function uploadFile(file) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/upload?path=" + encodeURIComponent(currentPath) + "&name=" + encodeURIComponent(file.name));
    xhr.withCredentials = true;
    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable) showUploadProgress(file.name, Math.round((e.loaded / e.total) * 100));
    });
    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve(); else reject();
    });
    xhr.addEventListener("error", reject);
    xhr.send(file);
  });
}

async function uploadFiles(fileList) {
  const files = Array.from(fileList);
  if (!files.length) return;
  let okCount = 0;
  for (const file of files) {
    showUploadProgress(file.name, 0);
    try {
      await uploadFile(file);
      okCount++;
    } catch {
      showToast(`Failed to upload ${file.name}`, true);
    }
  }
  hideUploadProgress();
  if (okCount) showToast(okCount === 1 ? "Uploaded" : `Uploaded ${okCount} files`);
  loadDir(currentPath);
}

document.getElementById("uploadInput").addEventListener("change", (e) => {
  uploadFiles(e.target.files);
  e.target.value = "";
});

let dragCounter = 0;
window.addEventListener("dragenter", (e) => {
  if (!e.dataTransfer.types.includes("Files")) return;
  dragCounter++;
  dropzone.classList.remove("hidden");
});
window.addEventListener("dragleave", () => {
  dragCounter--;
  if (dragCounter <= 0) { dragCounter = 0; dropzone.classList.add("hidden"); }
});
window.addEventListener("dragover", (e) => e.preventDefault());
window.addEventListener("drop", (e) => {
  e.preventDefault();
  dragCounter = 0;
  dropzone.classList.add("hidden");
  if (e.dataTransfer.files && e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files);
});

// ---- Console view ----
const navFiles = document.getElementById("navFiles");
const navConsole = document.getElementById("navConsole");
const consoleView = document.getElementById("consoleView");
const consoleLog = document.getElementById("consoleLog");
const consoleStatus = document.getElementById("consoleStatus");
const consoleForm = document.getElementById("consoleForm");
const consoleInput = document.getElementById("consoleInput");

let consoleLastSeq = 0;
let consolePollTimer = null;
let consoleAutoScroll = true;
let consoleCmdHistory = [];
let consoleCmdHistoryIdx = -1;

// ---- Console colors: ANSI escapes (ESC[...m) and Minecraft codes (§x) ----
// Colors are lightened slightly where the original is too dark for the dark console.
const MC_COLORS = {
  "0": "#7a8493", "1": "#5555aa", "2": "#55aa55", "3": "#55aaaa",
  "4": "#cc4444", "5": "#aa55aa", "6": "#ffaa00", "7": "#aaaaaa",
  "8": "#666b77", "9": "#7777ff", "a": "#55ff55", "b": "#55ffff",
  "c": "#ff5555", "d": "#ff55ff", "e": "#ffff55", "f": "#ffffff",
};
const ANSI_COLORS = ["#7a8493", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#dcdfe4"];
const ANSI_BRIGHT = ["#666b77", "#ff7b86", "#b5e890", "#ffd98a", "#84c4ff", "#e09bff", "#7fd6e0", "#ffffff"];

function ansi256ToHex(n) {
  if (n < 8) return ANSI_COLORS[n];
  if (n < 16) return ANSI_BRIGHT[n - 8];
  if (n >= 232) {
    const v = 8 + (n - 232) * 10;
    return `rgb(${v},${v},${v})`;
  }
  const levels = [0, 95, 135, 175, 215, 255];
  const i = n - 16;
  return `rgb(${levels[Math.floor(i / 36)]},${levels[Math.floor((i % 36) / 6)]},${levels[i % 6]})`;
}

// Splits a console line into styled pieces. Other escape sequences (cursor moves, etc.) are dropped.
function parseConsoleColors(text) {
  const segments = [];
  const blank = () => ({ color: null, bg: null, bold: false, italic: false, underline: false, strike: false });
  let state = blank();
  let buf = "";
  const flush = () => {
    if (buf) segments.push({ text: buf, style: { ...state } });
    buf = "";
  };
  const applySgr = (params) => {
    const codes = params === "" ? [0] : params.split(";").map(c => (c === "" ? 0 : Number(c)));
    for (let i = 0; i < codes.length; i++) {
      const c = codes[i];
      if (c === 0) state = blank();
      else if (c === 1) state.bold = true;
      else if (c === 3) state.italic = true;
      else if (c === 4) state.underline = true;
      else if (c === 9) state.strike = true;
      else if (c === 22) state.bold = false;
      else if (c === 23) state.italic = false;
      else if (c === 24) state.underline = false;
      else if (c === 29) state.strike = false;
      else if (c >= 30 && c <= 37) state.color = ANSI_COLORS[c - 30];
      else if (c >= 90 && c <= 97) state.color = ANSI_BRIGHT[c - 90];
      else if (c === 39) state.color = null;
      else if (c >= 40 && c <= 47) state.bg = ANSI_COLORS[c - 40];
      else if (c >= 100 && c <= 107) state.bg = ANSI_BRIGHT[c - 100];
      else if (c === 49) state.bg = null;
      else if ((c === 38 || c === 48) && codes[i + 1] === 5) {
        const hex = ansi256ToHex(codes[i + 2] || 0);
        if (c === 38) state.color = hex; else state.bg = hex;
        i += 2;
      } else if ((c === 38 || c === 48) && codes[i + 1] === 2) {
        const rgb = `rgb(${codes[i + 2] || 0},${codes[i + 3] || 0},${codes[i + 4] || 0})`;
        if (c === 38) state.color = rgb; else state.bg = rgb;
        i += 4;
      }
    }
  };
  const applyMc = (c) => {
    if (MC_COLORS[c]) state = { ...blank(), color: MC_COLORS[c] };
    else if (c === "l") state.bold = true;
    else if (c === "o") state.italic = true;
    else if (c === "n") state.underline = true;
    else if (c === "m") state.strike = true;
    else if (c === "r") state = blank();
    // "k" (obfuscated) has no static equivalent and is ignored.
  };

  const re = /\u001b\[([0-9;]*)([A-Za-z])|\u00a7([0-9a-fk-or])/gi;
  let last = 0;
  let m;
  while ((m = re.exec(text)) !== null) {
    buf += text.slice(last, m.index);
    flush();
    if (m[3] !== undefined) applyMc(m[3].toLowerCase());
    else if (m[2] === "m") applySgr(m[1]);
    last = re.lastIndex;
  }
  buf += text.slice(last);
  flush();
  return segments;
}

// Builds DOM nodes for a console line. Text is set with textContent, so nothing in the
// log can inject markup; only the color and style properties are set.
function coloredFragment(text) {
  const frag = document.createDocumentFragment();
  for (const seg of parseConsoleColors(text)) {
    const st = seg.style;
    if (!st.color && !st.bg && !st.bold && !st.italic && !st.underline && !st.strike) {
      frag.appendChild(document.createTextNode(seg.text));
      continue;
    }
    const span = document.createElement("span");
    span.textContent = seg.text;
    if (st.color) span.style.color = st.color;
    if (st.bg) span.style.backgroundColor = st.bg;
    if (st.bold) span.style.fontWeight = "700";
    if (st.italic) span.style.fontStyle = "italic";
    const deco = [st.underline && "underline", st.strike && "line-through"].filter(Boolean).join(" ");
    if (deco) span.style.textDecoration = deco;
    frag.appendChild(span);
  }
  return frag;
}
// ---- End console colors ----

// The page keeps at most this many lines on screen (the server keeps the same number).
const CONSOLE_MAX_VISIBLE = 2000;

function consoleAppendLines(lines) {
  if (!lines.length) return;
  const wasAtBottom = consoleLog.scrollTop + consoleLog.clientHeight >= consoleLog.scrollHeight - 16;
  const frag = document.createDocumentFragment();
  for (const line of lines) {
    const div = document.createElement("div");
    div.className = "line lvl-" + (line.level || "info").toLowerCase();
    div.appendChild(coloredFragment(line.text));
    frag.appendChild(div);
  }
  consoleLog.appendChild(frag);
  // A page left open for days would otherwise keep every line in memory and slow down.
  const extra = consoleLog.childElementCount - CONSOLE_MAX_VISIBLE;
  // The browser's scroll anchoring keeps the lines you are reading in place here.
  for (let i = 0; i < extra; i++) consoleLog.firstElementChild.remove();
  if (consoleAutoScroll && wasAtBottom) {
    consoleLog.scrollTop = consoleLog.scrollHeight;
  }
}

// Console polling. Each request asks only for lines newer than the last one seen, so
// responses stay small. The delay grows while nothing new arrives, and polling pauses
// while the page is in the background. This saves mobile data.
const CONSOLE_POLL_FAST_MS = 1500;
const CONSOLE_POLL_SLOW_MS = 6000;
let consolePollDelay = CONSOLE_POLL_FAST_MS;

// Fetches new console lines. Resolves to how many arrived.
let consoleController = null;

function abortConsolePoll() {
  if (consoleController) {
    consoleController.abort();
    consoleController = null;
  }
}

async function pollConsole() {
  abortConsolePoll();
  const ctrl = new AbortController();
  consoleController = ctrl;
  try {
    const res = await api("/api/console/history?since=" + consoleLastSeq, { signal: ctrl.signal, cache: "no-store" });
    if (!res.ok) { consoleStatus.textContent = "Disconnected"; return 0; }
    const data = await res.json();
    consoleLastSeq = data.lastSeq;
    consoleAppendLines(data.lines);
    consoleStatus.textContent = "Live";
    return data.lines.length;
  } catch {
    if (!ctrl.signal.aborted) consoleStatus.textContent = "Disconnected";
    return 0;
  } finally {
    if (consoleController === ctrl) consoleController = null;
  }
}

function scheduleConsolePoll(delay) {
  clearTimeout(consolePollTimer);
  consolePollTimer = setTimeout(runConsolePoll, delay);
}

function consoleIsShowing() {
  return !consoleView.classList.contains("hidden");
}

async function runConsolePoll() {
  consolePollTimer = null;
  if (!pageActive() || !consoleIsShowing()) return;
  const arrived = await pollConsole();
  consolePollDelay = arrived > 0
    ? CONSOLE_POLL_FAST_MS
    : Math.min(Math.round(consolePollDelay * 1.5), CONSOLE_POLL_SLOW_MS);
  if (pageActive() && consoleIsShowing()) scheduleConsolePoll(consolePollDelay);
}

function startConsolePolling() {
  if (consoleLog.children.length === 0) {
    const hint = document.createElement("div");
    hint.className = "empty-hint";
    hint.textContent = "Loading console history…";
    consoleLog.appendChild(hint);
  }
  consolePollDelay = CONSOLE_POLL_FAST_MS;
  pollConsole().then(() => {
    const hint = consoleLog.querySelector(".empty-hint");
    if (hint) hint.remove();
  });
  scheduleConsolePoll(CONSOLE_POLL_FAST_MS);
  setTimeout(() => consoleInput.focus(), 0);
}

function stopConsolePolling() {
  clearTimeout(consolePollTimer);
  consolePollTimer = null;
}


consoleLog.addEventListener("scroll", () => {
  consoleAutoScroll = consoleLog.scrollTop + consoleLog.clientHeight >= consoleLog.scrollHeight - 16;
});

consoleForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const cmd = consoleInput.value.trim();
  if (!cmd) return;
  consoleInput.value = "";
  consoleCmdHistory.push(cmd);
  consoleCmdHistoryIdx = consoleCmdHistory.length;
  const res = await api("/api/console/send", { method: "POST", body: cmd });
  if (!res.ok) showToast("Failed to send command", true);
});

consoleInput.addEventListener("keydown", (e) => {
  if (e.key === "ArrowUp") {
    if (consoleCmdHistoryIdx > 0) {
      consoleCmdHistoryIdx--;
      consoleInput.value = consoleCmdHistory[consoleCmdHistoryIdx];
    }
    e.preventDefault();
  } else if (e.key === "ArrowDown") {
    if (consoleCmdHistoryIdx < consoleCmdHistory.length - 1) {
      consoleCmdHistoryIdx++;
      consoleInput.value = consoleCmdHistory[consoleCmdHistoryIdx];
    } else {
      consoleCmdHistoryIdx = consoleCmdHistory.length;
      consoleInput.value = "";
    }
    e.preventDefault();
  }
});

const pageTitleEl = document.querySelector(".page-title");
const PAGE_TITLES = { files: "Server files", console: "Console", editor: "Editor", search: "Search results", plugins: "Plugins" };

function showView(view) {
  if (pageTitleEl) pageTitleEl.textContent = PAGE_TITLES[view] || "Server files";
  browserView.classList.toggle("hidden", view !== "files");
  consoleView.classList.toggle("hidden", view !== "console");
  editorView.classList.toggle("hidden", view !== "editor");
  searchView.classList.toggle("hidden", view !== "search");
  pluginsView.classList.toggle("hidden", view !== "plugins");
  navFiles.classList.toggle("active", view === "files");
  navConsole.classList.toggle("active", view === "console");
  navPlugins.classList.toggle("active", view === "plugins");
  if (view === "console") startConsolePolling();
  else stopConsolePolling();
}

navFiles.addEventListener("click", () => {
  if (!editorView.classList.contains("hidden")) closeEditor();
  showView("files");
});
navConsole.addEventListener("click", () => showView("console"));
navPlugins.addEventListener("click", () => {
  showView("plugins");
  if (!pluginSearchStarted) runPluginSearch("", 0, false);
});

// ---- Find in files (Enter in the search box) ----
searchInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    runFindInFiles(searchInput.value.trim());
  }
});

document.getElementById("searchBackBtn").addEventListener("click", () => showView("files"));

async function runFindInFiles(query) {
  if (query.length < 2) {
    showToast("Type at least 2 characters to search all files", true);
    return;
  }
  searchResultsTitle.textContent = `Searching all files for "${query}"…`;
  searchResultsList.innerHTML = "";
  showView("search");
  const res = await api("/api/search?q=" + encodeURIComponent(query));
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    showToast(data.error || "Search failed", true);
    showView("files");
    return;
  }
  renderSearchResults(await res.json());
}

function renderSearchResults(data) {
  const count = data.matches.length;
  let title = `${count} match${count === 1 ? "" : "es"} for "${data.query}"`;
  if (data.truncated) title += " (search stopped early, try a longer phrase)";
  searchResultsTitle.textContent = title;

  if (count === 0) {
    const empty = document.createElement("li");
    empty.className = "search-empty";
    empty.textContent = "No matches in text files.";
    searchResultsList.appendChild(empty);
    return;
  }

  for (const m of data.matches) {
    const li = document.createElement("li");
    li.className = "search-result";

    const head = document.createElement("div");
    head.className = "search-result-head";
    const pathEl = document.createElement("span");
    pathEl.className = "search-result-path";
    pathEl.textContent = m.path;
    const lineEl = document.createElement("span");
    lineEl.className = "search-result-line";
    lineEl.textContent = "line " + m.line;
    head.append(pathEl, lineEl);

    const snippet = document.createElement("div");
    snippet.className = "search-result-snippet";
    snippet.appendChild(highlightSnippet(m.text, data.query));

    li.append(head, snippet);
    li.addEventListener("click", () => openEditor(m.path, m.line));
    searchResultsList.appendChild(li);
  }
}

// Builds text nodes with each case-insensitive occurrence of query wrapped in <mark>.
function highlightSnippet(text, query) {
  const frag = document.createDocumentFragment();
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  let pos = 0;
  let idx;
  while ((idx = lower.indexOf(q, pos)) !== -1) {
    if (idx > pos) frag.appendChild(document.createTextNode(text.slice(pos, idx)));
    const mark = document.createElement("mark");
    mark.textContent = text.slice(idx, idx + q.length);
    frag.appendChild(mark);
    pos = idx + q.length;
  }
  if (pos < text.length) frag.appendChild(document.createTextNode(text.slice(pos)));
  return frag;
}

// Character offset where the given 1-based line starts.
function lineStartOffset(text, line) {
  let offset = 0;
  for (let i = 1; i < line; i++) {
    const nl = text.indexOf("\n", offset);
    if (nl === -1) break;
    offset = nl + 1;
  }
  return offset;
}

document.getElementById("logoutBtn").addEventListener("click", async () => {
  const ok = await confirmDialog("Log out?", "You'll need a fresh <strong>/filemgr</strong> link in-game to log back in.");
  if (!ok) return;
  try {
    await api("/api/logout", { method: "POST" });
  } catch {
    // ignore — we're navigating away regardless
  }
  window.location.reload();
});

// ---- Theme: dark by default; the choice is remembered on this device ----
function applyTheme(theme) {
  if (theme === "light") document.documentElement.setAttribute("data-theme", "light");
  else document.documentElement.removeAttribute("data-theme");
  const label = document.getElementById("themeLabel");
  if (label) label.textContent = theme === "light" ? "Dark mode" : "Light mode";
}
let savedTheme = "dark";
try { if (localStorage.getItem("kpefm-theme") === "light") savedTheme = "light"; } catch { /* storage blocked */ }
applyTheme(savedTheme);
document.getElementById("themeBtn").addEventListener("click", () => {
  savedTheme = savedTheme === "light" ? "dark" : "light";
  applyTheme(savedTheme);
  try { localStorage.setItem("kpefm-theme", savedTheme); } catch { /* storage blocked */ }
});

document.getElementById("refreshBtn").addEventListener("click", async (e) => {
  const btn = e.currentTarget;
  btn.classList.remove("spinning");
  void btn.offsetWidth; // restart animation if clicked again quickly
  btn.classList.add("spinning");
  await loadDir(currentPath); // keeps selection, filter and scroll position
});

loadDir("");

// Keep the page files on this device for faster repeat visits. Works over HTTPS or localhost.
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

// ---- Plugin installer (Modrinth) ----
// Lets the user search Modrinth and install a server plugin straight into plugins/, without
// leaving the browser. The backend never trusts a URL from here: it re-fetches the chosen
// version from Modrinth itself and verifies the file's checksum before keeping it.
let pluginSearchStarted = false;
let pluginSearchQuery = "";
let pluginSearchOffset = 0;
let pluginSearchTotal = 0;
let pluginSearchTimer = null;

function formatDownloads(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(n >= 10000000 ? 0 : 1) + "M";
  if (n >= 1000) return (n / 1000).toFixed(n >= 10000 ? 0 : 1) + "K";
  return String(n);
}

function formatBytes(n) {
  if (n >= 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + " MB";
  if (n >= 1024) return (n / 1024).toFixed(1) + " KB";
  return n + " B";
}

function loaderBadges(loaders) {
  const known = ["paper", "spigot", "bukkit", "purpur", "folia"];
  const shown = loaders.filter(l => known.includes(l));
  const list = shown.length ? shown : loaders.slice(0, 3);
  return list.map(l => `<span class="loader-badge">${escapeHtmlText(l)}</span>`).join("");
}

pluginSearchInput.addEventListener("input", () => {
  clearTimeout(pluginSearchTimer);
  pluginSearchTimer = setTimeout(() => runPluginSearch(pluginSearchInput.value.trim(), 0, false), 350);
});

async function runPluginSearch(query, offset, append) {
  pluginSearchStarted = true;
  if (!append) {
    pluginGrid.innerHTML = "";
    pluginEmptyState.classList.add("hidden");
    pluginLoadMore.classList.add("hidden");
  } else {
    pluginLoadMore.disabled = true;
    pluginLoadMore.textContent = "Loading\u2026";
  }

  let res;
  try {
    res = await api("/api/plugins/search?q=" + encodeURIComponent(query) + "&offset=" + offset);
  } catch {
    showPluginError("Can't reach the server. Check your connection and try again.");
    return;
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    showPluginError(data.error || "Could not search Modrinth.");
    return;
  }

  pluginSearchQuery = query;
  pluginSearchOffset = offset + data.hits.length;
  pluginSearchTotal = data.total;

  if (!append && data.hits.length === 0) {
    pluginEmptyTitle.textContent = "No plugins found";
    pluginEmptySub.textContent = query ? `Nothing on Modrinth matches "${query}".` : "Try a different search.";
    pluginEmptyState.classList.remove("hidden");
  } else {
    pluginEmptyState.classList.add("hidden");
  }

  const frag = document.createDocumentFragment();
  for (const hit of data.hits) frag.appendChild(buildPluginCard(hit));
  pluginGrid.appendChild(frag);

  pluginResultCount.textContent = pluginSearchTotal
    ? `${pluginSearchOffset} of ${pluginSearchTotal.toLocaleString()} plugins`
    : "";
  pluginLoadMore.disabled = false;
  pluginLoadMore.textContent = "Load more";
  pluginLoadMore.classList.toggle("hidden", pluginSearchOffset >= pluginSearchTotal);
}

function showPluginError(message) {
  pluginEmptyTitle.textContent = "Couldn't load plugins";
  pluginEmptySub.textContent = message;
  pluginEmptyState.classList.remove("hidden");
  pluginLoadMore.classList.add("hidden");
}

function buildPluginCard(hit) {
  const card = document.createElement("div");
  card.className = "plugin-card";
  card.tabIndex = 0;

  // A plugin with no icon, or an icon that fails to load, gets a letter tile instead of a gap.
  const makeFallback = () => {
    const tile = document.createElement("div");
    tile.className = "plugin-icon plugin-icon-fallback";
    tile.textContent = (hit.title || "?").trim().charAt(0).toUpperCase() || "?";
    return tile;
  };
  if (hit.icon) {
    const icon = document.createElement("img");
    icon.className = "plugin-icon";
    icon.alt = "";
    icon.loading = "lazy";
    icon.src = hit.icon;
    icon.addEventListener("error", () => icon.replaceWith(makeFallback()));
    card.appendChild(icon);
  } else {
    card.appendChild(makeFallback());
  }

  const body = document.createElement("div");
  body.className = "plugin-card-body";

  const title = document.createElement("div");
  title.className = "plugin-title";
  title.textContent = hit.title;
  body.appendChild(title);

  const desc = document.createElement("div");
  desc.className = "plugin-desc";
  desc.textContent = hit.description;
  body.appendChild(desc);

  const meta = document.createElement("div");
  meta.className = "plugin-meta";
  meta.innerHTML = `<span class="plugin-downloads">${formatDownloads(hit.downloads)} downloads</span>` + loaderBadges(hit.loaders);
  body.appendChild(meta);

  card.appendChild(body);
  const open = () => openPluginVersions(hit.id, hit.title, hit.icon);
  card.addEventListener("click", open);
  card.addEventListener("keydown", (e) => { if (e.key === "Enter") open(); });
  return card;
}

pluginLoadMore.addEventListener("click", () => runPluginSearch(pluginSearchQuery, pluginSearchOffset, true));

async function openPluginVersions(projectId, title, iconUrl) {
  pluginVersionsPanel.classList.remove("hidden");
  pluginVersionsTitle.textContent = "";
  if (iconUrl) {
    const ic = document.createElement("img");
    ic.className = "plugin-icon plugin-icon-small";
    ic.alt = "";
    ic.src = iconUrl;
    ic.addEventListener("error", () => ic.remove());
    pluginVersionsTitle.appendChild(ic);
  }
  pluginVersionsTitle.appendChild(document.createTextNode(title));
  pluginVersionsSub.textContent = "Loading versions\u2026";
  pluginVersionsList.innerHTML = "";
  pluginVersionsPanel.scrollIntoView({ behavior: "smooth", block: "start" });

  let res;
  try {
    res = await api("/api/plugins/versions?project=" + encodeURIComponent(projectId));
  } catch {
    pluginVersionsSub.textContent = "Can't reach the server.";
    return;
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    pluginVersionsSub.textContent = data.error || "Could not load versions.";
    return;
  }
  if (!data.versions.length) {
    pluginVersionsSub.textContent = "This plugin has no published versions.";
    return;
  }
  pluginVersionsSub.textContent = `${data.versions.length} version${data.versions.length === 1 ? "" : "s"}`;

  const frag = document.createDocumentFragment();
  for (const v of data.versions) frag.appendChild(buildVersionRow(v));
  pluginVersionsList.appendChild(frag);
}

function buildVersionRow(version) {
  const row = document.createElement("div");
  row.className = "plugin-version-row";

  const info = document.createElement("div");
  info.className = "plugin-version-info";
  const nameLine = document.createElement("div");
  nameLine.className = "plugin-version-name";
  nameLine.textContent = version.name || version.versionNumber;
  info.appendChild(nameLine);

  const sub = document.createElement("div");
  sub.className = "plugin-version-sub";
  const gv = version.gameVersions.slice(0, 4).join(", ") + (version.gameVersions.length > 4 ? " \u2026" : "");
  const date = version.datePublished ? new Date(version.datePublished).toLocaleDateString() : "";
  const file = version.files.find(f => f.primary) || version.files[0];
  const sizeText = file ? formatBytes(file.size) : "";
  sub.textContent = [gv, date, sizeText].filter(Boolean).join(" \u00b7 ");
  info.appendChild(sub);

  const badges = document.createElement("div");
  badges.className = "plugin-version-badges";
  badges.innerHTML = loaderBadges(version.loaders);
  info.appendChild(badges);

  row.appendChild(info);

  const installBtn = document.createElement("button");
  installBtn.className = "btn btn-primary";
  installBtn.textContent = "Install";
  if (!file) {
    installBtn.disabled = true;
    installBtn.title = "This version has no installable file";
  } else {
    installBtn.addEventListener("click", () => installPluginVersion(version.id, file.filename, installBtn));
  }
  row.appendChild(installBtn);
  return row;
}

async function installPluginVersion(versionId, filename, button, overwrite = false) {
  button.disabled = true;
  const original = button.textContent;
  button.textContent = overwrite ? "Replacing\u2026" : "Installing\u2026";
  try {
    const url = "/api/plugins/install?versionId=" + encodeURIComponent(versionId) + (overwrite ? "&overwrite=true" : "");
    const r = await api(url, { method: "POST" });
    const data = await r.json().catch(() => ({}));
    if (r.ok) {
      showToast(`Installed ${data.filename}. Restart the server to load it.`);
      button.textContent = "Installed";
      return;
    }
    if (r.status === 409 && data.canOverwrite && !overwrite) {
      const ok = await confirmDialog(
        "Replace existing plugin?",
        `A plugin named <strong>${escapeHtmlText(filename)}</strong> is already installed. Replacing it won't take effect until the server restarts.`
      );
      if (ok) {
        await installPluginVersion(versionId, filename, button, true);
        return;
      }
      button.textContent = original;
      button.disabled = false;
      return;
    }
    showToast(data.error || "Install failed", true);
    button.textContent = original;
    button.disabled = false;
  } catch {
    showToast("Can't reach the server. Check your connection and try again.", true);
    button.textContent = original;
    button.disabled = false;
  }
}

pluginVersionsClose.addEventListener("click", () => {
  pluginVersionsPanel.classList.add("hidden");
});
