export function renderPrivacy(container: HTMLElement): void {
  container.innerHTML = `
    <div class="about sw-inner-prose">
      <h1 class="sw-page-title">Privacy Policy</h1>

      <section class="about-section">
        <p><em>Last updated: October 19, 2026</em></p>
        <p>
          <strong>The short version: swapi.build has no accounts, sets no cookies, runs no trackers,
          and stores nothing about you.</strong>
        </p>
      </section>

      <section class="about-section">
        <h2>What we don't collect</h2>
        <p>
          The site and the API have no sign-up, no login, and no user profiles. The frontend sets no
          cookies and includes no analytics or tracking scripts of any kind. The application does not
          store any personal data.
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
        <p>
          While the site moves to this server during the last months of 2026, some requests may
          still be served by Vercel, the previous host, which keeps standard operational logs (such
          as IP address, user agent, and request path) under its own policies.
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
          Cloudflare (DNS-only, no proxying: Cloudflare never sees your requests). No data is sold or
          shared with anyone.
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
