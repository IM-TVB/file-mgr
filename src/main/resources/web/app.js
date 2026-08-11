let currentPath = "";
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
  let acc = "";
  let html = `<a data-path="" class="${parts.length || hasExtra ? "" : "current"}">root</a>`;
  parts.forEach((part, i) => {
    acc = acc ? acc + "/" + part : part;
    const isLast = i === parts.length - 1 && !hasExtra;
    html += `<span class="sep">/</span><a data-path="${acc}" class="${isLast ? "current" : ""}">${escapeHtml(part)}</a>`;
  });
  if (hasExtra) {
    html += `<span class="sep">/</span><a class="current">${escapeHtml(extraSegment)}</a>`;
  }
  el.innerHTML = html;
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

function isTextEditable(name) {
  const lower = name.toLowerCase();
  return TEXT_EXT.some(ext => lower.endsWith(ext));
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

async function loadDir(path) {
  currentPath = path;
  searchInput.value = "";
  selectedPaths.clear();
  lastCheckedPath = null;
  table.style.display = "none";
  emptyState.classList.add("hidden");
  loadingState.classList.remove("hidden");
  try {
    const res = await api("/api/list?path=" + encodeURIComponent(path));
    if (!res.ok) { showToast("Failed to load directory", true); return; }
    const data = await res.json();
    currentEntries = data.entries;
    renderBreadcrumb(data.path);
    applySortAndRender();
  } finally {
    loadingState.classList.add("hidden");
  }
}

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

function renderTable(entries, filter) {
  tableBody.innerHTML = "";
  let visibleCount = 0;
  const visibleFullPaths = [];

  for (const entry of entries) {
    const matches = !filter || entry.name.toLowerCase().includes(filter);
    if (!matches) continue;
    visibleCount++;

    const fullPath = joinPath(currentPath, entry.name);
    visibleFullPaths.push(fullPath);

    const tr = document.createElement("tr");
    tr.classList.toggle("selected", selectedPaths.has(fullPath));

    const checkTd = document.createElement("td");
    checkTd.className = "col-check";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.className = "row-checkbox";
    checkbox.checked = selectedPaths.has(fullPath);
    checkbox.setAttribute("aria-label", "Select " + entry.name);
    checkbox.addEventListener("click", (e) => {
      e.stopPropagation();
      const newState = checkbox.checked;
      if (e.shiftKey && lastCheckedPath !== null) {
        const i1 = visibleFullPaths.indexOf(lastCheckedPath);
        const i2 = visibleFullPaths.indexOf(fullPath);
        if (i1 !== -1 && i2 !== -1) {
          const [start, end] = i1 < i2 ? [i1, i2] : [i2, i1];
          for (let i = start; i <= end; i++) {
            if (newState) selectedPaths.add(visibleFullPaths[i]); else selectedPaths.delete(visibleFullPaths[i]);
          }
        }
      } else if (newState) {
        selectedPaths.add(fullPath);
      } else {
        selectedPaths.delete(fullPath);
      }
      lastCheckedPath = fullPath;
      applySortAndRender();
    });
    checkTd.appendChild(checkbox);
    tr.appendChild(checkTd);

    const iconTd = document.createElement("td");
    iconTd.className = "col-icon";
    iconTd.innerHTML = `<span class="file-icon">${iconFor(entry)}</span>`;
    tr.appendChild(iconTd);

    const nameTd = document.createElement("td");
    const nameCell = document.createElement("div");
    nameCell.className = "name-cell";
    nameCell.tabIndex = 0;
    const nameText = document.createElement("span");
    nameText.className = "name-text";
    nameText.textContent = entry.name;
    nameCell.appendChild(nameText);
    const open = () => {
      if (entry.isDir) {
        loadDir(fullPath);
      } else if (isTextEditable(entry.name)) {
        openEditor(fullPath);
      } else {
        window.location.href = "/api/download?path=" + encodeURIComponent(fullPath);
      }
    };
    nameCell.addEventListener("click", open);
    nameCell.addEventListener("keydown", (e) => { if (e.key === "Enter") open(); });
    nameTd.appendChild(nameCell);
    tr.appendChild(nameTd);

    const sizeTd = document.createElement("td");
    sizeTd.className = "dim";
    sizeTd.textContent = entry.isDir ? "—" : entry.sizeHuman;
    tr.appendChild(sizeTd);

    const modTd = document.createElement("td");
    modTd.className = "dim";
    modTd.textContent = formatDate(entry.modified);
    modTd.title = formatExactDate(entry.modified);
    tr.appendChild(modTd);

    const actionsTd = document.createElement("td");
    actionsTd.className = "row-actions";

    const kebabBtn = document.createElement("button");
    kebabBtn.className = "kebab-btn";
    kebabBtn.innerHTML = `<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4.5" r="1.4" fill="currentColor"/><circle cx="10" cy="10" r="1.4" fill="currentColor"/><circle cx="10" cy="15.5" r="1.4" fill="currentColor"/></svg>`;
    kebabBtn.title = "Actions";
    kebabBtn.setAttribute("aria-label", "Actions for " + entry.name);
    kebabBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      openRowMenu(kebabBtn, entry, fullPath);
    });
    actionsTd.appendChild(kebabBtn);

    tr.appendChild(actionsTd);
    tableBody.appendChild(tr);
  }

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

bulkCompressBtn.addEventListener("click", () => {
  const count = selectedPaths.size;
  if (count === 0) return;
  const paths = Array.from(selectedPaths);
  const defaultName = count === 1
    ? paths[0].split("/").pop().replace(/\.[^./]+$/, "") + ".zip"
    : "archive.zip";
  promptDialog("Archive name", defaultName, "Compress").then(name => {
    if (!name) return;
    if (!name.toLowerCase().endsWith(".zip")) name += ".zip";
    const dest = joinPath(currentPath, name);
    api("/api/zip?dest=" + encodeURIComponent(dest), { method: "POST", body: paths.join("\n") })
      .then(async r => {
        if (r.ok) {
          showToast("Archive created");
          selectedPaths.clear();
          loadDir(currentPath);
        } else {
          const data = await r.json().catch(() => ({}));
          showToast(data.error || "Failed to create archive", true);
        }
      });
  });
});

bulkExtractBtn.addEventListener("click", () => {
  if (bulkExtractBtn.disabled || selectedPaths.size !== 1) return;
  const [path] = selectedPaths;
  api("/api/unzip?path=" + encodeURIComponent(path), { method: "POST" })
    .then(async r => {
      const data = await r.json().catch(() => ({}));
      if (r.ok) {
        const n = data.extracted;
        showToast(n === undefined ? "Extracted" : `Extracted ${n} file${n === 1 ? "" : "s"}`);
        selectedPaths.clear();
        loadDir(currentPath);
      } else {
        showToast(data.error || "Extract failed", true);
      }
    });
});

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
}

editorTextarea.addEventListener("input", refreshHighlight);
editorTextarea.addEventListener("scroll", syncEditorScroll);

async function openEditor(path) {
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
  editorView.classList.remove("hidden");
  navConsole.classList.remove("active");
  navFiles.classList.add("active");
  stopConsolePolling();
  setTimeout(() => {
    editorTextarea.focus();
    editorTextarea.setSelectionRange(0, 0);
    editorTextarea.scrollTop = 0;
    editorTextarea.scrollLeft = 0;
    syncEditorScroll();
  }, 0);
}

function closeEditor() {
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

function openRowMenu(anchorBtn, entry, fullPath) {
  if (!rowMenu.classList.contains("hidden") && rowMenu._anchor === anchorBtn) {
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

  if (!entry.isDir && extOf(entry.name) === "zip") {
    rowMenu.appendChild(rowMenuItem("Extract",
      `<svg viewBox="0 0 20 20" fill="none"><rect x="3.5" y="8" width="13" height="9" rx="1.2" stroke="currentColor" stroke-width="1.3"/><path d="M10 3v7M10 3l-2.5 2.5M10 3l2.5 2.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
      () => {
        api("/api/unzip?path=" + encodeURIComponent(fullPath), { method: "POST" })
          .then(async r => {
            const data = await r.json().catch(() => ({}));
            if (r.ok) {
              const n = data.extracted;
              showToast(n === undefined ? "Extracted" : `Extracted ${n} file${n === 1 ? "" : "s"}`);
              loadDir(currentPath);
            } else {
              showToast(data.error || "Extract failed", true);
            }
          });
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
  const btnRect = anchorBtn.getBoundingClientRect();
  const menuRect = rowMenu.getBoundingClientRect();
  let top = btnRect.bottom + window.scrollY + 4;
  let left = btnRect.right + window.scrollX - menuRect.width;
  if (left < 8) left = 8;
  if (top + menuRect.height > window.scrollY + window.innerHeight - 8) {
    top = btnRect.top + window.scrollY - menuRect.height - 4;
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

function consoleAppendLines(lines) {
  if (!lines.length) return;
  const wasAtBottom = consoleLog.scrollTop + consoleLog.clientHeight >= consoleLog.scrollHeight - 16;
  const frag = document.createDocumentFragment();
  for (const line of lines) {
    const div = document.createElement("div");
    div.className = "line";
    div.textContent = line.text;
    frag.appendChild(div);
  }
  consoleLog.appendChild(frag);
  if (consoleAutoScroll && wasAtBottom) {
    consoleLog.scrollTop = consoleLog.scrollHeight;
  }
}

async function pollConsole() {
  try {
    const res = await api("/api/console/history?since=" + consoleLastSeq);
    if (!res.ok) { consoleStatus.textContent = "Disconnected"; return; }
    const data = await res.json();
    consoleLastSeq = data.lastSeq;
    consoleAppendLines(data.lines);
    consoleStatus.textContent = "Live";
  } catch {
    consoleStatus.textContent = "Disconnected";
  }
}

function startConsolePolling() {
  if (consoleLog.children.length === 0) {
    const hint = document.createElement("div");
    hint.className = "empty-hint";
    hint.textContent = "Loading console history…";
    consoleLog.appendChild(hint);
  }
  pollConsole().then(() => {
    const hint = consoleLog.querySelector(".empty-hint");
    if (hint) hint.remove();
  });
  clearInterval(consolePollTimer);
  consolePollTimer = setInterval(pollConsole, 1500);
  setTimeout(() => consoleInput.focus(), 0);
}

function stopConsolePolling() {
  clearInterval(consolePollTimer);
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

function showView(view) {
  browserView.classList.toggle("hidden", view !== "files");
  consoleView.classList.toggle("hidden", view !== "console");
  editorView.classList.toggle("hidden", view !== "editor");
  navFiles.classList.toggle("active", view !== "console");
  navConsole.classList.toggle("active", view === "console");
  if (view === "console") startConsolePolling();
  else stopConsolePolling();
}

navFiles.addEventListener("click", () => {
  if (!editorView.classList.contains("hidden")) closeEditor();
  showView("files");
});
navConsole.addEventListener("click", () => showView("console"));

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

document.getElementById("refreshBtn").addEventListener("click", async (e) => {
  const btn = e.currentTarget;
  const keepSelection = new Set(selectedPaths);
  btn.classList.remove("spinning");
  void btn.offsetWidth; // restart animation if clicked again quickly
  btn.classList.add("spinning");
  await loadDir(currentPath);
  selectedPaths = keepSelection;
  applySortAndRender();
});

loadDir("");
