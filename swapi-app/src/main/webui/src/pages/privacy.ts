export function renderPrivacy(container: HTMLElement): void {
  container.innerHTML = `
    <div class="about">
      <h1>Privacy Policy</h1>

      <section class="about-section">
        <p><em>Last updated: August 2, 2026</em></p>
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
          swapi.build is hosted on Vercel. Like virtually every hosting provider, Vercel keeps standard
          operational logs for requests (such as IP address, user agent, and request path) for
          infrastructure operation and abuse prevention. Those logs are handled and retained under
          Vercel's own policies; we don't enrich them, export them, or use them to identify anyone.
        </p>
      </section>

      <section class="about-section">
        <h2>The MCP server</h2>
        <p>
          The MCP endpoint at /mcp is stateless: each request is processed and answered, and its content
          is not stored. There are no sessions and no server-side state tied to you or your agent.
        </p>
      </section>

      <section class="about-section">
        <h2>Third parties</h2>
        <p>
          Hosting by Vercel; DNS by Cloudflare (DNS-only, no proxying). No data is sold or shared with
          anyone.
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
