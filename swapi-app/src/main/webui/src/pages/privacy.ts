export function renderPrivacy(container: HTMLElement): void {
  container.innerHTML = `
    <div class="about sw-inner-prose">
      <h1 class="sw-page-title">Privacy Policy</h1>

      <section class="about-section">
        <p><em>Last updated: October 19, 2026</em></p>
        <p>
          <strong>The short version: swapi.build has no accounts, sets no cookies, and stores nothing
          about you. Page visits are counted without cookies and without personal data, and the
          server counts requests per route and per hour, without your IP address.</strong>
        </p>
      </section>

      <section class="about-section">
        <h2>What we don't collect</h2>
        <p>
          The site and the API have no sign-up, no login, and no user profiles. The site sets no
          cookies, uses no local storage for tracking, and runs no advertising or cross-site
          tracking. The application does not store any personal data.
        </p>
      </section>

      <section class="about-section">
        <h2>How visits are measured</h2>
        <p>
          Visits to the site's pages are measured with Cloudflare Web Analytics. Each page loads a
          small script from Cloudflare that reports the page address, the referring page and how long
          the page took to load. It sets no cookie, uses no local storage, does not fingerprint your
          browser, and collects no personal data. The API and the MCP endpoint load no script at all.
        </p>
        <p>
          The server also counts requests per route, per hour and per day (UTC), for example how many
          requests reached <code>/api/people/{id}</code> or <code>/mcp</code> in a given hour. Only the
          route, the hour or day and the count are kept: no IP address, no browser or client details,
          no ids or search terms. Hourly counts are kept for 14 days and daily counts for 60 days.
          They live in memory and are copied to a small file on the server, so a restart does not
          reset them; the file holds only those routes, times and counts, nothing about you. The
          counts are public on the <a href="/stats">usage stats page</a> and at
          <a href="/api/stats">/api/stats</a>. One line per day with the daily counts goes to the
          server's technical logs described below.
        </p>
      </section>

      <section class="about-section">
        <h2>What gets processed technically</h2>
        <p>
          swapi.build runs on a server operated by the project maintainer on Oracle Cloud, in the
          São Paulo region. The web server keeps no access log: requests to the site, the API and
          the MCP endpoint are not recorded.
        </p>
        <p>
          The server's operating system keeps technical logs (services starting and stopping,
          errors, and connection attempts to the server's administrative access, which include the
          connecting IP address) for up to 30 days, after which they are deleted. They are used only
          to keep the server running and to block abuse; we don't enrich them, export them, or use
          them to identify anyone.
        </p>
      </section>

      <section class="about-section">
        <h2>The MCP server</h2>
        <p>
          The MCP endpoint at /mcp stores nothing about you, and request contents are not
          stored. Clients on the stateless MCP revision (2026-07-28) send fully
          self-contained requests. Clients on earlier revisions are issued a session id;
          that session exists only in memory and holds only what your own client sent about
          itself during the handshake — chiefly the protocol version, the client name and version,
          and which optional MCP capabilities it declared support for. Nothing is written to
          disk, and the session is dropped once idle.
        </p>
      </section>

      <section class="about-section">
        <h2>Third parties</h2>
        <p>
          Hosting on Oracle Cloud infrastructure, on a server the maintainer operates; DNS by
          Cloudflare (DNS-only, no proxying: your requests to the site, the API and the MCP endpoint
          never pass through Cloudflare). Cloudflare also provides the page-visit measurement described
          above, which only receives what that script reports. No data is sold or shared with anyone.
        </p>
      </section>

      <section class="about-section">
        <h2>Changes</h2>
        <p>If this policy changes, the date at the top changes with it.</p>
      </section>

      <section class="about-section">
        <h2>Contact</h2>
        <p>
          Questions? Open an issue on
          <a href="https://github.com/eldermoraes/swapi.build/issues" target="_blank" rel="noopener"
            >GitHub</a
          >.
        </p>
      </section>

      <section class="about-section about-credits">
        <p>
          <em>This is a plain-language statement written by the project maintainer, not legal advice.</em>
        </p>
      </section>
    </div>
  `;
}
