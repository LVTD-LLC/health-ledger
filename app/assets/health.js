(() => {
  const local = document.querySelector('#local-time');
  if (!local) return;
  local.form.addEventListener('submit', () => {
    document.querySelector('#record-time').value = local.value ? new Date(local.value).toISOString() : '';
  });
  for (const el of document.querySelectorAll('time[datetime]')) {
    const d = new Date(el.dateTime);
    if (Number.isFinite(d.getTime())) el.textContent = d.toLocaleString();
  }
  const nav = document.querySelector('.ledger-nav');
  const sections = [...document.querySelectorAll('.ledger > section[id]')];
  const links = [...nav.querySelectorAll('a[href^="#"]')];
  function showSection() {
    const requested = location.hash.slice(1);
    const current = links.some(link => link.hash === '#' + requested) ? requested : 'timeline';
    for (const section of sections) {
      section.hidden = section.id !== current && !(section.id === 'trend' && current === 'timeline');
    }
    for (const link of links) {
      if (link.hash === '#' + current) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
  }
  window.addEventListener('hashchange', showSection);
  showSection();
  const Recognizer = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognizer) return;
  const button = document.querySelector('#dictate');
  const status = document.querySelector('#voice-state');
  button.hidden = false;
  button.textContent = 'Dictate using browser speech service';
  button.addEventListener('click', () => {
    const recognizer = new Recognizer();
    recognizer.lang = navigator.language;
    recognizer.onstart = () => {button.disabled = true; status.textContent = 'Listening…';};
    recognizer.onend = () => {button.disabled = false;};
    recognizer.onerror = () => {status.textContent = 'Dictation unavailable. You can type the note.';};
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
