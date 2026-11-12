(() => {
  const btn = document.getElementById("reelWatchBtn");
  if (!btn) return;
  const state = document.getElementById("reelWatchState");
  const ar = {
    title: "تنزيل الريل بضغطة واحدة",
    body: "أنت تشاهد ريل في إنستغرام؟ اضغط الزر العائم مرة واحدة فيُنزَّل نفس الريل، بدون نسخ الرابط وبدون فتح IG SAVE.",
    privacy: "يحتاج إذن «إمكانية الوصول». يعمل داخل إنستغرام فقط، وفقط بعد ضغطتك على الزر: يفتح المشاركة ويضغط «نسخ الرابط». لا يقرأ النص ولا كلمات المرور ولا محتوى أي تطبيق أو شاشة أخرى. مغلق افتراضياً.",
    on: "إيقاف التنزيل بضغطة",
    off: "تفعيل التنزيل بضغطة",
    enabled: "مفعّل. اضغط الزر العائم وأنت داخل إنستغرام.",
    disabled: "غير مفعّل. فعّل «IG SAVE» في إمكانية الوصول.",
    restricted: "إذا كان المفتاح رمادياً: معلومات التطبيق ← القائمة ⋮ ← السماح بالإعدادات المقيّدة.",
    needApp: "هذه الميزة تعمل داخل تطبيق أندرويد فقط.",
  };
  const en = {
    title: "One-tap reel download",
    body: "Watching a reel in Instagram? Tap the floating bubble once and that same reel downloads — no link copying, no opening IG SAVE.",
    privacy: "Needs the Accessibility permission. Runs inside Instagram only, and only after you tap the bubble: it opens Share and presses “Copy link”. It never reads text, passwords, or content from any other app or screen. Off by default.",
    on: "Turn off one-tap download",
    off: "Enable one-tap download",
    enabled: "On. Tap the floating bubble while you are in Instagram.",
    disabled: "Off. Enable “IG SAVE” in Accessibility settings.",
    restricted: "If the switch is greyed out: App info → ⋮ menu → Allow restricted settings.",
    needApp: "This feature only works in the Android app.",
  };
  function bridge() {
    return window.NadaBridge && typeof window.NadaBridge.isReelWatchOn === "function" ? window.NadaBridge : null;
  }
  function isOn() {
    const b = bridge();
    if (!b) return false;
    try { return !!b.isReelWatchOn(); } catch (e) { return false; }
  }
  function strings() {
    const lang = localStorage.getItem("nada-lang") || document.documentElement.lang || "ar";
    return lang.startsWith("en") ? en : ar;
  }
  function render() {
    const t = strings();
    const b = bridge();
    const on = isOn();
    const title = document.getElementById("reelWatchTitle");
    const body = document.getElementById("reelWatchBody");
    const privacy = document.getElementById("reelWatchPrivacy");
    if (title) title.textContent = t.title;
    if (body) body.textContent = t.body;
    if (privacy) privacy.textContent = t.privacy;
    btn.textContent = on ? t.on : t.off;
    btn.setAttribute("aria-pressed", on ? "true" : "false");
    if (state) {
      state.textContent = (on ? t.enabled : t.disabled) + (b && !on ? " " + t.restricted : "");
      state.hidden = false;
    }
  }
  btn.onclick = () => {
    const b = bridge();
    if (!b || typeof b.setReelWatch !== "function") {
      const toast = document.getElementById("toast");
      if (toast) {
        toast.textContent = strings().needApp;
        toast.hidden = false;
        setTimeout(() => { toast.hidden = true; }, 2500);
      }
      return;
    }
    const next = !isOn();
    try { b.setReelWatch(next); } catch (e) { /* keep the card usable */ }
    setTimeout(render, 400);
  };
  window.nadaReelWatch = () => render();
  document.addEventListener("visibilitychange", () => { if (!document.hidden) render(); });
  window.addEventListener("focus", render);
  render();
  document.getElementById("langBtn")?.addEventListener("click", () => setTimeout(render, 0));
})();
