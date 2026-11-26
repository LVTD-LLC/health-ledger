(() => {
  const local = document.querySelector('#local-time');
  if (local && local.form) {
    local.form.addEventListener('submit', () => {
      const hidden = document.querySelector('#record-time');
      if (hidden) hidden.value = local.value ? new Date(local.value).toISOString() : '';
    });
  }

  for (const el of document.querySelectorAll('time[datetime]')) {
    const d = new Date(el.dateTime);
    if (Number.isFinite(d.getTime())) {
      el.textContent = d.toLocaleString(undefined, {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    }
  }

  for (const button of document.querySelectorAll('button[data-copy]')) {
    const value = document.getElementById(button.dataset.copy);
    const status = document.getElementById('copy-status');
    if (!value || !status || !navigator.clipboard?.writeText) continue;
    button.hidden = false;
    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(value.textContent);
        status.textContent = value.id === 'export-url' ? 'URL copied.' : 'Header copied.';
      } catch {
        status.textContent = 'Select the text above and copy it manually.';
      }
    });
  }

  // Tabbed journal sections with #trend mapped cleanly into #overview.
  const ledger = document.querySelector('.ledger');
  const nav = document.querySelector('.ledger-nav');
  if (ledger && nav) {
    const sections = [...ledger.querySelectorAll(':scope > section[id]')];
    const links = [...nav.querySelectorAll('a[href^="#"]')];
    const fallback = ledger.dataset.defaultTab || 'overview';
    function resolveTab(hash) {
      const requested = (hash || '').replace(/^#/, '');
      if (requested === 'trend') return 'overview';
      return links.some(link => link.hash === '#' + requested) ? requested : fallback;
    }
    function showSection() {
      const current = resolveTab(location.hash);
      for (const section of sections) {
        section.hidden = section.id !== current;
      }
      for (const link of links) {
        if (link.hash === '#' + current) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
      }
    }
    window.addEventListener('hashchange', showSection);
    showSection();
  }

  // Auto-submit the metric picker when JS is enabled, keeping the submit button as a no-JS fallback.
  for (const form of document.querySelectorAll('form[data-auto-submit]')) {
    const select = form.querySelector('select');
    const submitBtn = form.querySelector('.metric-picker-submit');
    if (submitBtn) submitBtn.hidden = true;
    if (select) {
      select.addEventListener('change', () => {
        if (typeof form.requestSubmit === 'function') form.requestSubmit();
        else form.submit();
      });
    }
  }

  // Interactive hover crosshair and floating tooltip on measurement trend charts.
  for (const stage of document.querySelectorAll('[data-chart-stage]')) {
    const svg = stage.querySelector('svg');
    const crosshair = stage.querySelector('[data-chart-crosshair]');
    const focusDot = stage.querySelector('[data-chart-focus]');
    const tooltip = stage.querySelector('[data-chart-tooltip]');
    if (!svg || !crosshair || !focusDot || !tooltip) continue;

    const points = [...svg.querySelectorAll('[data-chart-point]')].map(node => ({
      x: Number(node.dataset.x),
      y: Number(node.dataset.y),
      val: node.dataset.val || '',
      unit: node.dataset.unit || '',
      date: node.dataset.date || '',
      delta: node.dataset.delta || '',
    })).filter(p => Number.isFinite(p.x) && Number.isFinite(p.y));

    if (!points.length) continue;

    const vb = (svg.getAttribute('viewBox') || '0 0 760 276').split(/\s+/).map(Number);
    const vbW = vb[2] || 760;
    const vbH = vb[3] || 276;

    function hide() {
      crosshair.setAttribute('hidden', '');
      focusDot.setAttribute('hidden', '');
      tooltip.hidden = true;
    }

    function showPoint(pt) {
      crosshair.setAttribute('x1', pt.x.toFixed(1));
      crosshair.setAttribute('x2', pt.x.toFixed(1));
      crosshair.removeAttribute('hidden');
      focusDot.setAttribute('cx', pt.x.toFixed(1));
      focusDot.setAttribute('cy', pt.y.toFixed(1));
      focusDot.removeAttribute('hidden');

      tooltip.textContent = '';
      const valEl = document.createElement('strong');
      valEl.className = 'chart-tooltip-val';
      valEl.textContent = pt.unit ? `${pt.val} ${pt.unit}` : pt.val;
      tooltip.appendChild(valEl);

      const dateEl = document.createElement('span');
      dateEl.className = 'chart-tooltip-date';
      dateEl.textContent = pt.date;
      tooltip.appendChild(dateEl);

      if (pt.delta) {
        const deltaEl = document.createElement('span');
        deltaEl.className = 'chart-tooltip-delta';
        deltaEl.textContent = `${pt.delta} vs prev`;
        tooltip.appendChild(deltaEl);
      }

      tooltip.hidden = false;
      const pctX = (pt.x / vbW) * 100;
      const pctY = (pt.y / vbH) * 100;
      tooltip.style.left = `${Math.min(Math.max(pctX, 10), 90)}%`;
      tooltip.style.top = `${Math.max(pctY - 6, 8)}%`;
      tooltip.dataset.align = pctX > 72 ? 'right' : pctX < 28 ? 'left' : 'center';
    }

    function onPointer(event) {
      const rect = svg.getBoundingClientRect();
      if (!rect.width) return;
      const svgX = ((event.clientX - rect.left) / rect.width) * vbW;
      let nearest = points[0];
      let bestDist = Math.abs(points[0].x - svgX);
      for (let i = 1; i < points.length; i++) {
        const dist = Math.abs(points[i].x - svgX);
        if (dist < bestDist) {
          bestDist = dist;
          nearest = points[i];
        }
      }
      showPoint(nearest);
    }

    svg.addEventListener('pointermove', onPointer);
    svg.addEventListener('pointerdown', onPointer);
    svg.addEventListener('pointerleave', hide);
  }

  // Optional browser speech dictation on the Add entry form.
  const Recognizer = window.SpeechRecognition || window.webkitSpeechRecognition;
  const button = document.querySelector('#dictate');
  const status = document.querySelector('#voice-state');
  if (!Recognizer || !button || !status) return;
  button.hidden = false;
  button.textContent = 'Dictate using browser speech service';
  button.addEventListener('click', () => {
    const recognizer = new Recognizer();
    recognizer.lang = navigator.language;
    recognizer.onstart = () => { button.disabled = true; status.textContent = 'Listening…'; };
    recognizer.onend = () => { button.disabled = false; };
    recognizer.onerror = () => { status.textContent = 'Dictation unavailable. You can type the note.'; };
    recognizer.onresult = event => {
      const text = event.results[0][0].transcript;
      const note = document.querySelector('#record-note');
      note.value = [note.value, text].filter(Boolean).join(' ');
      const kind = document.querySelector('#record-kind');
      if (/\b(took|medicine|pill|tablet)\b/i.test(text)) kind.value = 'Medicine';
      else if (/\b(feel|feeling|mood)\b/i.test(text)) kind.value = 'Check-in';
      else if (/\b(workout|reps|squats)\b/i.test(text)) kind.value = 'Workout';
      const title = note.form.elements.title;
      if (!title.value) title.value = text.slice(0, 160);
      status.textContent = 'Review the category and transcript, then save.';
    };
    recognizer.start();
  });
})();
