export function renderTerms(container: HTMLElement): void {
  container.innerHTML = `
    <div class="about">
      <h1>Terms of Use</h1>

      <section class="about-section">
        <p><em>Last updated: August 2, 2026</em></p>
      </section>

      <section class="about-section">
        <h2>The service</h2>
        <p>
          swapi.build is a free, open-source Star Wars API and MCP server, provided as is, with no
          warranty of any kind and no uptime guarantee. The service scales to zero when idle, so the
          very first request after a quiet period may be slower.
        </p>
      </section>

      <section class="about-section">
        <h2>Fair use</h2>
        <p>
          Use it for apps, demos, learning, and agents as much as you like. Don't abuse it: no flooding,
          scraping at hostile rates, or attempts to disrupt the service. Rate limiting may be applied if
          needed.
        </p>
      </section>

      <section class="about-section">
        <h2>The data</h2>
        <p>
          All data comes from community fan sources and is provided for fun and education. Accuracy is
          not guaranteed &mdash; canon disputes should be settled elsewhere.
        </p>
      </section>

      <section class="about-section">
        <h2>Star Wars</h2>
        <p>
          Star Wars and all associated names are trademarks of Lucasfilm Ltd. / Disney. This project is
          a fan work, not affiliated with, endorsed by, or connected to Lucasfilm or Disney in any way.
        </p>
      </section>

      <section class="about-section">
        <h2>The code</h2>
        <p>
          The source code is available on
          <a href="https://github.com/eldermoraes/swapi.build" target="_blank" rel="noopener">GitHub</a>
          under the Apache 2.0 license.
        </p>
      </section>

      <section class="about-section">
        <h2>Changes</h2>
        <p>These terms may change; the date at the top tells you when they last did.</p>
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
