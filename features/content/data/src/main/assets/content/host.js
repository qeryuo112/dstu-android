(() => {
  'use strict';
  const params = new URLSearchParams(location.search);
  const nonce = params.get('nonce');
  const send = (type, value = '') => window.dstuContent.postMessage(JSON.stringify([nonce, type, value]));
  Object.defineProperty(window, 'DstuContent', { value: Object.freeze({
    answer: value => send('answer', String(value)),
    openLink: value => send('openLink', String(value)),
    replay: () => send('replay'),
  }), writable: false });
  const theme = event => {
    if (event.origin !== location.origin || event.source !== window) return;
    const data = event.data;
    if (!data || data.kind !== 'theme' || data.nonce !== nonce) return;
    const valid = /^#[0-9a-f]{6}$/i;
    if (![data.background, data.foreground, data.accent].every(x => valid.test(x))) return;
    document.documentElement.style.setProperty('--background', data.background);
    document.documentElement.style.setProperty('--foreground', data.foreground);
    document.documentElement.style.setProperty('--primary', data.accent);
    document.documentElement.style.colorScheme = data.dark ? 'dark' : 'light';
    document.documentElement.classList.toggle('night_mode', data.dark);
    document.body.classList.toggle('night_mode', data.dark);
    window.dispatchEvent(new CustomEvent('dstu-theme-change', { detail: { dark: data.dark } }));
  };
  window.addEventListener('message', theme);
  document.addEventListener('click', event => {
    const answer = event.target.closest('[data-dstu-answer]');
    if (answer) { send('answer', answer.getAttribute('data-dstu-answer')); return; }
    if (event.target.closest('[data-dstu-replay]')) { send('replay'); return; }
    const link = event.target.closest('a[href]');
    if (link) { event.preventDefault(); send('openLink', link.href); }
  });
  document.addEventListener('DOMContentLoaded', async () => {
    const formula = document.querySelector('[data-dstu-formula]');
    if (formula) window.katex.render(formula.textContent, formula, { throwOnError: false, trust: false, displayMode: formula.dataset.display === 'true' });
    const diagram = document.querySelector('[data-dstu-diagram]');
    if (diagram) {
      try {
        window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' });
        const rendered = await window.mermaid.render('dstu-diagram', diagram.textContent);
        diagram.innerHTML = rendered.svg;
      } catch (error) { diagram.textContent = 'Diagram could not be rendered'; }
    }
    document.documentElement.dataset.dstuReady = 'true';
  });
})();
