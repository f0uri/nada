(() => {
  const btn = document.getElementById("updateCheckBtn");
  if (!btn) return;
  const ar = {
    title: "تحديثات التطبيق",
    body: "احصل على أحدث إصدار داخل التطبيق مع شريط تقدم.",
    check: "التحقق من التحديثات",
    needApp: "التحديث داخل التطبيق يعمل من تطبيق أندرويد.",
  };
  const en = {
    title: "App updates",
    body: "Get the latest version inside the app with a progress bar.",
    check: "Check for updates",
    needApp: "In-app updates work in the Android app.",
  };
  function render() {
    const t = (localStorage.getItem("nada-lang") || document.documentElement.lang || "ar").startsWith("en") ? en : ar;
    const title = document.getElementById("updateEntryTitle");
    const body = document.getElementById("updateEntryBody");
    if (title) title.textContent = t.title;
    if (body) body.textContent = t.body;
    btn.textContent = t.check;
    btn.onclick = () => {
      if (window.NadaBridge && typeof window.NadaBridge.checkForUpdates === "function") {
        window.NadaBridge.checkForUpdates();
      } else {
        const toast = document.getElementById("toast");
        if (toast) {
          toast.textContent = t.needApp;
          toast.hidden = false;
          setTimeout(() => { toast.hidden = true; }, 2500);
        }
      }
    };
  }
  render();
  document.getElementById("langBtn")?.addEventListener("click", () => setTimeout(render, 0));
})();
