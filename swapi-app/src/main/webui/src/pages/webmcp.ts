import type { RegistrationStatus } from '../webmcp';

const STATUS: Record<RegistrationStatus, string> = {
  unavailable: 'WebMCP is not available in this browser. The site remains fully usable.',
  registering: 'Registering browser tools…',
  ready: 'All four browser tools are registered. This does not mean an agent is connected.',
  failed: 'Browser tool registration failed. The site remains fully usable.',
  stopped: 'Browser tools are stopped.',
};

export function updateWebMcpStatus(container: HTMLElement, status: RegistrationStatus): void {
  const element = container.querySelector('[data-webmcp-status]');
  if (element) element.textContent = STATUS[status];
}

export function renderWebMcp(container: HTMLElement, status: RegistrationStatus): void {
  container.innerHTML = `
    <section class="docs sw-inner-prose webmcp-guide">
      <h1 class="sw-page-title">WebMCP in the browser</h1>
      <p class="docs-intro">Explore Star Wars together with an agent. WebMCP lets a compatible browser agent use the same explorer you see, with explicit tools instead of guessing where to click.</p>
      <p data-webmcp-status role="status"></p>
      <h2>One project, three interfaces</h2>
      <p><a href="/docs">REST</a> serves application data. The <a href="/docs/mcp">remote MCP server</a> gives agents access outside the browser. WebMCP runs in the open page and updates its explorer. All use the same Star Wars data.</p>
      <h2>Experimental browser support</h2>
      <div class="webmcp-copy"><p>WebMCP is an evolving browser proposal. Use a compatible browser with WebMCP enabled; it is not available in every browser or assistant. See the <a href="https://developer.chrome.com/docs/ai/webmcp" target="_blank" rel="noopener noreferrer">Chrome setup and inspector guide</a>. For local testing, enable <code>chrome://flags/#enable-webmcp-testing</code> and relaunch the browser.</p>
      <p>This integration targets <code>document.modelContext</code>. A browser exposing an older API will leave these tools unavailable. No model, API key or third-party runtime script is added to this site.</p>
      <p>Validated with Chrome 152 and the WebMCP testing flag. In this version the browser may omit execution cancellation options; user navigation and typing still supersede pending page actions.</p></div>
      <h2>Tools</h2>
      <div class="sw-table-wrap"><table class="sw-table tools-table webmcp-tools">
        <thead><tr><th scope="col">Name</th><th scope="col">Arguments</th><th scope="col">Visible result</th></tr></thead>
        <tbody>
          <tr><td><code>sw_list</code></td><td data-label="Arguments"><code>resource</code></td><td>Show all records and clear the previous search.</td></tr>
          <tr><td><code>sw_get</code></td><td data-label="Arguments"><code>resource, id</code></td><td>Open a record's details.</td></tr>
          <tr><td><code>sw_search</code></td><td data-label="Arguments"><code>resource, query</code></td><td>Fill the search and show matching records.</td></tr>
          <tr><td><code>sw_random</code></td><td data-label="Arguments"><code>resource</code></td><td>Open the selected random record.</td></tr>
        </tbody>
      </table></div>
      <div class="webmcp-notes"><p><strong>Resources.</strong> Use <code>PEOPLE</code>, <code>FILMS</code>, <code>PLANETS</code>, <code>SPECIES</code>, <code>STARSHIPS</code> or <code>VEHICLES</code>. </p><p><strong>Record IDs.</strong> IDs come from record URLs: <code>FILMS</code> ID <code>1</code> is A New Hope, not episode I. </p><p><strong>Search.</strong> Search matches name fragments (titles for films), ignores case and accepts an empty string to match all records.</p>
      <p><strong>Response.</strong> Names and arguments match the remote MCP tools. WebMCP additionally changes this page and returns a JSON string with <code>ok</code>, complete records in <code>data</code>, <code>resource</code>, <code>path</code> and either <code>count</code> or <code>id</code>. Its response envelope is specific to this browser integration.</p></div>
      <h2>Try a demonstration</h2>
      <div class="webmcp-copy"><ol>
        <li>Open this site in the configured browser and use the inspector to discover the four tools. Manual calls do not require a model API key.</li>
        <li>Call <code>sw_list</code> with <code>{"resource":"PEOPLE"}</code>.</li>
        <li>Call <code>sw_search</code> with <code>{"resource":"PEOPLE","query":"Luke"}</code>, then <code>sw_get</code> with <code>{"resource":"PEOPLE","id":1}</code>.</li>
        <li>Call <code>sw_random</code> with <code>{"resource":"STARSHIPS"}</code>. The returned ship is the one displayed.</li>
      </ol>
      <p>With a compatible agent connected, try “List the characters”, “Find Luke Skywalker and open his details”, or “Show me a random starship”. Agent availability and any model configuration belong to your browser or inspector.</p></div>
      <h2>You control the page</h2>
      <div class="webmcp-copy"><p>All tools only read server data, but they update the visible page. Only one agent action runs at a time; another receives <code>BUSY</code>. Typing or navigating takes priority over pending work. A cancelled browser call may end without a JSON response. Other errors include <code>INVALID_ARGUMENT</code>, <code>NOT_FOUND</code>, <code>NETWORK_ERROR</code>, <code>HTTP_ERROR</code> and <code>INVALID_RESPONSE</code>.</p>
      <p>Search filters are temporary and are not restored by reloading a list URL. The integration needs no separate server and is bundled with the Quarkus native application; its JavaScript executes in your browser.</p>
      <a class="sw-pill sw-pill--solid" href="/resource/people">Open the explorer</a></div>
    </section>`;
  updateWebMcpStatus(container, status);
}
