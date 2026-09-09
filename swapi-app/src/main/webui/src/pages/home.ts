import { pill, sectionLabel, terminalMarkup, initTerminal, indexRows } from '../ui/components';
import { RESOURCES } from '../constants';

const TERM = {
  idPrefix: 'home',
  suggestions: ['people/1', 'planets/1', 'starships/9', 'films/1'],
};

export function renderHome(container: HTMLElement): void {
  container.innerHTML = `
    <section class="sw-hero">
      <div class="sw-starfield" aria-hidden="true"></div>
      <p class="sw-greeting">A long time ago in a galaxy far, far away…</p>
      <h1>The Star Wars API</h1>
      <p class="sw-sub">All the Star Wars data you've ever wanted. Free, open source, REST + MCP — built with Quarkus and GraalVM.</p>
      <div class="sw-cta-row">
        ${pill('▶ Get started', '/docs', 'solid')}
        ${pill('MCP for agents', '/docs/mcp', 'ghost')}
        ${pill('WebMCP in the browser', '/docs/webmcp', 'ghost')}
      </div>
    </section>
    <div class="sw-term-wrap">${terminalMarkup(TERM)}</div>
    <div class="sw-band">
      ${sectionLabel('The resources')}
      ${indexRows(RESOURCES.map((r) => ({ title: r.title, endpoint: r.endpoint, href: `/resource/${r.key}` })))}
    </div>
  `;
  initTerminal(container, TERM);
}
