import { bindCopyButtons } from '../ui/components';

/**
 * The /docs/mcp page comes drawn by the server (2.11.0, templates/ssr/mcp.html),
 * with the endpoint of the request host. The client only binds the tabs and the
 * copy buttons. Every panel arrives visible, so the guide reads without
 * JavaScript; under the `js` class of <html> (set by the inline script of
 * index.html before the first paint) the panels marked `data-inactive` hide.
 */
export function bindMcp(container: HTMLElement): void {
  const tabs = Array.from(container.querySelectorAll<HTMLButtonElement>('.tab-btn'));
  const panels = Array.from(container.querySelectorAll<HTMLElement>('.tab-panel'));

  function selectTab(idx: number): void {
    tabs.forEach((t, i) => {
      t.classList.toggle('active', i === idx);
      t.setAttribute('aria-selected', String(i === idx));
      t.tabIndex = i === idx ? 0 : -1;
    });
    panels.forEach((p, i) => {
      if (i === idx) p.removeAttribute('data-inactive');
      else p.setAttribute('data-inactive', '');
    });
    tabs[idx].focus();
  }

  tabs.forEach((tab, idx) => {
    tab.addEventListener('click', () => selectTab(idx));
    tab.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight') selectTab((idx + 1) % tabs.length);
      if (e.key === 'ArrowLeft') selectTab((idx - 1 + tabs.length) % tabs.length);
      if (e.key === 'Home') {
        e.preventDefault();
        selectTab(0);
      }
      if (e.key === 'End') {
        e.preventDefault();
        selectTab(tabs.length - 1);
      }
    });
  });

  bindCopyButtons(container);
}
