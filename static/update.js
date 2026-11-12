(() => {
  const btn = document.getElementById("updateCheckBtn");
  if (!btn) return;
  const body = document.getElementById("updateEntryBody");
  const ar = {
    title: "تحديثات التطبيق",
    check: "التحقق من التحديثات",
    yes: "يوجد تحديث",
    no: "لا يوجد تحديث",
    needApp: "التحديث داخل التطبيق يعمل من تطبيق أندرويد.",
  };
  const en = {
    title: "App updates",
    check: "Check for updates",
    yes: "Update available",
    no: "No update",
    needApp: "In-app updates work in the Android app.",
  };
  // null = not known yet; the box stays on the single "no update" line until
  // the native side reports a real answer. No extra words are ever added.
  let available = false;
  function strings() {
    const lang = localStorage.getItem("nada-lang") || document.documentElement.lang || "ar";
    return lang.startsWith("en") ? en : ar;
  }
  function render() {
    const t = strings();
    const title = document.getElementById("updateEntryTitle");
    if (title) title.textContent = t.title;
    if (body) body.textContent = available ? t.yes : t.no;
    btn.textContent = t.check;
  }
  window.nadaUpdateState = (on) => {
    available = !!on;
    render();
  };
  btn.onclick = () => {
    if (window.NadaBridge && typeof window.NadaBridge.checkForUpdates === "function") {
      window.NadaBridge.checkForUpdates();
    } else {
      const toast = document.getElementById("toast");
      if (toast) {
        toast.textContent = strings().needApp;
        toast.hidden = false;
        setTimeout(() => { toast.hidden = true; }, 2500);
      }
    }
  };
  try {
    if (window.NadaBridge && typeof window.NadaBridge.updateAvailable === "function") {
      available = !!window.NadaBridge.updateAvailable();
    }
  } catch (e) { /* keep the default line */ }
  render();
  document.getElementById("langBtn")?.addEventListener("click", () => setTimeout(render, 0));
})();
