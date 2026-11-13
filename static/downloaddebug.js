(() => {
  const row = document.getElementById("downloadDebug");
  if (!row) return;
  const ar = { share: "اضغط لمشاركة تشخيص آخر تنزيل" };
  const en = { share: "Tap to share the last download diagnostics" };
  const fr = { share: "Appuyez pour partager le diagnostic du dernier téléchargement" };
  function strings() {
    const lang = localStorage.getItem("nada-lang") || document.documentElement.lang || "ar";
    return lang.startsWith("fr") ? fr : lang.startsWith("en") ? en : ar;
  }
  function bridge() {
    return window.NadaBridge && typeof window.NadaBridge.lastDownloadRun === "function" ? window.NadaBridge : null;
  }
  function render() {
    let line = "";
    try {
      const b = bridge();
      line = b ? String(b.lastDownloadRun() || "") : "";
    } catch (e) { /* optional debug row */ }
    row.hidden = !line;
    row.textContent = line;
    row.title = line ? strings().share : "";
  }
  row.setAttribute("role", "button");
  row.tabIndex = 0;
  const share = () => {
    try {
      const b = bridge();
      if (b && typeof b.shareDownloadRun === "function") b.shareDownloadRun();
    } catch (e) { /* keep the About card usable */ }
  };
  row.onclick = share;
  row.onkeydown = (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); share(); } };
  document.addEventListener("visibilitychange", () => { if (!document.hidden) render(); });
  window.addEventListener("focus", render);
  render();
  document.getElementById("langBtn")?.addEventListener("click", () => setTimeout(render, 0));
})();
