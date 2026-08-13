(() => {
  const I18N = {
    ar: {
      app: "ندى",
      eyebrow: "حفظ هادئ",
      lede: "الصق رابط ريلز أو صورة عامة، ونحفظها لك بجودة المنشور الأصلية — بلا علامة مائية.",
      urlLabel: "رابط إنستغرام",
      placeholder: "https://www.instagram.com/reel/…",
      paste: "لصق",
      fetch: "جلب المنشور",
      demo: "جرّب مثالاً عاماً",
      publicOnly: "المنشورات العامة فقط",
      emptyTitle: "مساحة هادئة للحفظ",
      emptyBody: "ريلز، صور، أو ألبومات. نعرض المعاينة أولاً، ثم تحفظ ما تشاء.",
      download: "تنزيل هذا الملف",
      downloadAll: "تنزيل الكل",
      copyCaption: "نسخ النص",
      libraryEyebrow: "ما حفظته هنا",
      library: "المكتبة",
      libEmpty: "لا شيء بعد. المنشورات التي تجلبها تظهر هنا بهدوء.",
      about: "حول ندى",
      a1t: "للمنشورات العامة",
      a1b: "يعمل مع الريلز والصور والألبومات العامة. الحسابات الخاصة والقصص غير مدعومة.",
      a2t: "بلا علامة مائية",
      a2b: "نأخذ الملف كما نشره صاحبه. الجودة هي جودة إنستغرام الأصلية المتاحة.",
      a3t: "احترام المبدعين",
      a3b: "للحفظ الشخصي فقط. حقوق العمل تبقى لصاحب الحساب — لا تعِد النشر دون إذن.",
      tabHome: "الرئيسية",
      tabLib: "المكتبة",
      tabAbout: "حول",
      loading: "نبحث عن المنشور…",
      reel: "ريلز",
      photo: "صورة",
      video: "فيديو",
      carousel: "ألبوم",
      likes: "إعجاب",
      comments: "تعليق",
      files: "ملفات",
      pasted: "تم اللصق",
      copied: "نُسخ النص",
      saved: "بدأ التنزيل",
      shared: "وصل رابط من إنستغرام",
      errUrl: "هذا ليس رابط إنستغرام صالحاً.",
      errRate: "تم الإبطاء قليلاً. انتظر لحظة ثم أعد المحاولة.",
      errMissing: "المنشور غير متاح، أو الحساب خاص.",
      errNet: "تعذّر الاتصال. تحقق من الشبكة ثم أعد المحاولة.",
      errClip: "تعذّر اللصق من الحافظة.",
    },
    en: {
      app: "Nada",
      eyebrow: "Soft saving",
      lede: "Paste a public Reel or photo link. We keep the original file — no watermark.",
      urlLabel: "Instagram link",
      placeholder: "https://www.instagram.com/reel/…",
      paste: "Paste",
      fetch: "Fetch post",
      demo: "Try a public example",
      publicOnly: "Public posts only",
      emptyTitle: "A quiet place to save",
      emptyBody: "Reels, photos, or carousels. Preview first, then keep what you want.",
      download: "Download this file",
      downloadAll: "Download all",
      copyCaption: "Copy caption",
      libraryEyebrow: "Kept on this device",
      library: "Library",
      libEmpty: "Nothing yet. Posts you fetch will rest here.",
      about: "About Nada",
      a1t: "Public posts",
      a1b: "Works with public Reels, photos, and albums. Private accounts and Stories are not supported.",
      a2t: "No watermark",
      a2b: "You get the file as published. Quality is Instagram’s original available version.",
      a3t: "Respect creators",
      a3b: "For personal saving only. Copyright stays with the author — don’t repost without permission.",
      tabHome: "Home",
      tabLib: "Library",
      tabAbout: "About",
      loading: "Looking for the post…",
      reel: "Reel",
      photo: "Photo",
      video: "Video",
      carousel: "Album",
      likes: "likes",
      comments: "comments",
      files: "files",
      pasted: "Pasted",
      copied: "Caption copied",
      saved: "Download started",
      shared: "Got a link from Instagram",
      errUrl: "That doesn’t look like an Instagram link.",
      errMissing: "This post is unavailable, or the account is private.",
      errRate: "Slow down for a moment, then try again.",
      errNet: "Couldn’t reach the server. Check your connection.",
      errClip: "Couldn’t read the clipboard.",
    },
  };

  const DEMO = "https://www.instagram.com/reel/Chunk8-jurw/";
  const $ = (id) => document.getElementById(id);
  const state = {
    lang: localStorage.getItem("nada-lang") || "ar",
    post: null,
    index: 0,
    history: JSON.parse(localStorage.getItem("nada-lib") || "[]"),
  };

  const clock = $("clock");
  const form = $("form");
  const input = $("url");
  const toastEl = $("toast");
  const loader = $("loader");
  const carousel = $("carousel");
  let toastTimer = 0;

  function t(key) {
    return (I18N[state.lang] || I18N.ar)[key] || key;
  }

  function applyLang() {
    const ar = state.lang === "ar";
    document.documentElement.lang = state.lang;
    document.documentElement.dir = ar ? "rtl" : "ltr";
    $("langBtn").textContent = ar ? "EN" : "ع";
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      el.textContent = t(el.dataset.i18n);
    });
    document.querySelectorAll("[data-i18n-ph]").forEach((el) => {
      el.placeholder = t(el.dataset.i18nPh);
    });
    if (state.post) renderResult(state.post, false);
    renderLibrary();
  }

  function tick() {
    const now = new Date();
    clock.textContent = now.toLocaleTimeString(state.lang === "ar" ? "ar-MA" : "en-GB", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    });
  }

  function toast(msg) {
    toastEl.hidden = false;
    toastEl.textContent = msg;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastEl.hidden = true;
    }, 2400);
  }

  function showLoader(on) {
    loader.hidden = !on;
    loader.classList.toggle("on", !!on);
  }

  function showView(name) {
    document.querySelectorAll(".view").forEach((v) => {
      v.hidden = v.dataset.view !== name;
    });
    document.querySelectorAll(".tab").forEach((btn) => {
      btn.classList.toggle("on", btn.dataset.tab === (name === "result" ? "home" : name));
    });
    if (name !== "result") $("view-home").hidden = name !== "home";
  }

  function mediaUrl(token, download, name) {
    const q = new URLSearchParams({ t: token });
    if (download) q.set("download", "1");
    if (name) q.set("name", name);
    return `/api/media?${q}`;
  }

  function fmt(n) {
    if (n == null) return "—";
    return Number(n).toLocaleString(state.lang === "ar" ? "ar" : "en");
  }

  function kindLabel(kind) {
    return t(kind === "reel" ? "reel" : kind === "carousel" ? "carousel" : kind === "video" ? "video" : "photo");
  }

  function currentItem() {
    return state.post?.media[state.index] || state.post?.media[0];
  }

  function fileName(post, item) {
    const user = post.owner.username || "nada";
    return `${user}_${post.shortcode}_${item.index}`;
  }

  function renderResult(post, resetIndex) {
    if (resetIndex) state.index = 0;
    const owner = post.owner || {};
    $("who").innerHTML = `
      ${owner.avatar ? `<img alt="" src="${mediaUrl(owner.avatar)}" />` : ""}
      <div>
        <b>@${escapeHtml(owner.username || "instagram")}</b>
        <span>${escapeHtml(owner.name || "")}</span>
      </div>`;
    $("kindBadge").textContent = kindLabel(post.kind);
    $("caption").textContent = post.caption || "";
    $("caption").classList.toggle("more", false);
    $("stats").innerHTML = `
      <span>${fmt(post.likes)} ${t("likes")}</span>
      <span>${fmt(post.comments)} ${t("comments")}</span>
      <span>${post.media.length} ${t("files")}</span>`;
    $("dlAllBtn").hidden = post.media.length < 2;

    carousel.innerHTML = post.media
      .map((item, i) => {
        const src = mediaUrl(item.kind === "video" ? item.file : item.preview);
        if (item.kind === "video") {
          return `<article class="slide" data-i="${i}">
            <video playsinline preload="metadata" poster="${mediaUrl(item.preview)}" src="${src}"></video>
            <button class="play-fab" type="button" data-play="${i}" aria-label="play">
              <span><svg viewBox="0 0 24 24" fill="currentColor"><path d="M9 7.5v9l8-4.5-8-4.5Z"/></svg></span>
            </button>
          </article>`;
        }
        return `<article class="slide" data-i="${i}"><img alt="" src="${src}" /></article>`;
      })
      .join("");

    $("dots").innerHTML = post.media.map((_, i) => `<i class="${i === state.index ? "on" : ""}"></i>`).join("");
    requestAnimationFrame(() => {
      const slides = carousel.querySelectorAll(".slide");
      if (slides[state.index]) slides[state.index].scrollIntoView({ inline: "center", block: "nearest" });
    });
  }

  function remember(post) {
    const preview = post.media[0];
    const entry = {
      shortcode: post.shortcode,
      kind: post.kind,
      username: post.owner.username,
      name: post.owner.name,
      avatar: post.owner.avatar,
      caption: post.caption,
      likes: post.likes,
      comments: post.comments,
      taken_at: post.taken_at,
      source: post.source,
      media: post.media,
      thumb: preview ? preview.preview : null,
      at: Date.now(),
    };
    state.history = [entry, ...state.history.filter((h) => h.shortcode !== post.shortcode)].slice(0, 24);
    localStorage.setItem("nada-lib", JSON.stringify(state.history));
    renderLibrary();
  }

  function renderLibrary() {
    const empty = $("libEmpty");
    const grid = $("libGrid");
    empty.hidden = state.history.length > 0;
    grid.innerHTML = state.history
      .map((h) => {
        const src = h.thumb ? mediaUrl(h.thumb) : "";
        return `<button class="lib-card" type="button" data-code="${h.shortcode}">
          <figure>
            ${src ? `<img alt="" src="${src}" />` : `<div style="height:148px"></div>`}
            <figcaption>
              <b>@${escapeHtml(h.username || "")}</b>
              <span>${kindLabel(h.kind)} · ${h.media.length} ${t("files")}</span>
            </figcaption>
          </figure>
        </button>`;
      })
      .join("");
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function mapError(status, code) {
    if (code === "url" || status === 400) return t("errUrl");
    if (code === "rate" || status === 429) return t("errRate");
    if (code === "missing" || code === "empty" || status === 404) return t("errMissing");
    return t("errNet");
  }

  async function resolve(url) {
    showLoader(true);
    try {
      const res = await fetch("/api/resolve", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.ok) throw Object.assign(new Error("fail"), { status: res.status, code: data.error });
      state.post = data;
      remember(data);
      renderResult(data, true);
      showView("result");
    } catch (err) {
      toast(mapError(err.status, err.code));
    } finally {
      showLoader(false);
    }
  }

  function downloadItem(item) {
    if (!state.post || !item) return;
    const a = document.createElement("a");
    a.href = mediaUrl(item.file, true, fileName(state.post, item));
    a.download = "";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  async function downloadAll() {
    if (!state.post) return;
    for (const item of state.post.media) {
      downloadItem(item);
      await new Promise((r) => setTimeout(r, 450));
    }
    toast(t("saved"));
  }

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const url = input.value.trim();
    if (!url) {
      input.focus();
      return;
    }
    resolve(url);
  });

  $("pasteBtn").addEventListener("click", async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (!text) throw new Error("empty");
      input.value = text.trim();
      toast(t("pasted"));
    } catch {
      toast(t("errClip"));
    }
  });

  $("demoBtn").addEventListener("click", () => {
    input.value = DEMO;
    resolve(DEMO);
  });

  $("backBtn").addEventListener("click", () => showView("home"));
  $("langBtn").addEventListener("click", () => {
    state.lang = state.lang === "ar" ? "en" : "ar";
    localStorage.setItem("nada-lang", state.lang);
    applyLang();
    tick();
  });

  $("dlBtn").addEventListener("click", () => {
    downloadItem(currentItem());
    toast(t("saved"));
  });
  $("dlAllBtn").addEventListener("click", downloadAll);
  $("copyCapBtn").addEventListener("click", async () => {
    const text = state.post?.caption || "";
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      toast(t("copied"));
    } catch {
      toast(t("errClip"));
    }
  });

  $("caption").addEventListener("click", () => $("caption").classList.toggle("more"));

  carousel.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-play]");
    if (!btn) return;
    const slide = btn.closest(".slide");
    const video = slide.querySelector("video");
    btn.remove();
    video.controls = true;
    video.play().catch(() => {});
  });

  carousel.addEventListener("scroll", () => {
    const slides = [...carousel.querySelectorAll(".slide")];
    if (!slides.length) return;
    const mid = carousel.scrollLeft + carousel.clientWidth / 2;
    let best = 0;
    let dist = Infinity;
    slides.forEach((s, i) => {
      const c = s.offsetLeft + s.offsetWidth / 2;
      const d = Math.abs(c - mid);
      if (d < dist) {
        dist = d;
        best = i;
      }
    });
    state.index = best;
    $("dots").querySelectorAll("i").forEach((dot, i) => dot.classList.toggle("on", i === best));
  });

  $("libGrid").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-code]");
    if (!btn) return;
    resolve(`https://www.instagram.com/p/${btn.dataset.code}/`);
  });

  document.querySelectorAll(".tab").forEach((btn) => {
    btn.addEventListener("click", () => showView(btn.dataset.tab));
  });

  applyLang();
  renderLibrary();
  showLoader(false);
  tick();
  setInterval(tick, 15000);
})();
