export function renderAbout(container: HTMLElement): void {
  container.innerHTML = `
    <div class="about">
      <h1>About</h1>

      <section class="about-section">
        <h2>What is this?</h2>
        <p>
          SWAPI (Star Wars API) is a free, open-source REST API that serves all the Star Wars data you could ever want:
          People, Films, Planets, Species, Starships, and Vehicles. All available in JSON format, ready to be consumed
          by your applications.
        </p>
      </section>

      <section class="about-section">
        <h2>Why build another one?</h2>
        <p>
          If you've ever relied on a third-party API to power your conference demos, you know the pain:
          the API goes down, the service shuts off, and suddenly your carefully crafted live demo is broken
          minutes before you go on stage.
        </p>
        <p>
          That happened more than once to <a href="https://www.linkedin.com/in/eldermoraes/" target="_blank" rel="noopener">Elder Moraes</a>,
          a Java Champion who's also a lifelong Star Wars fan. After years of rewriting demos every time yet another
          Star Wars API disappeared, he decided to build one that would <strong>never go offline</strong>.
        </p>
        <p>
          And since he was building it from scratch, why not use the technologies he loves and admires?
          The result is a blazing-fast API built with
          <a href="https://quarkus.io" target="_blank" rel="noopener">Quarkus</a> and
          <a href="https://www.graalvm.org/" target="_blank" rel="noopener">GraalVM</a>,
          serving as both a reliable data source for demos and a living showcase of what modern Java can do.
        </p>
      </section>

      <section class="about-section">
        <h2>What makes it different?</h2>
        <div class="features-grid">
          <div class="feature-card">
            <div class="feature-icon">&#9889;</div>
            <h3>Quarkus Performance</h3>
            <p>Out-of-the-box performance that speaks for itself. Supersonic, subatomic Java with no tuning required.</p>
          </div>
          <div class="feature-card">
            <div class="feature-icon">&#x1F9EC;</div>
            <h3>Native Compilation</h3>
            <p>Compiled ahead-of-time with GraalVM native image for instant startup and minimal memory footprint.</p>
          </div>
          <div class="feature-card">
            <div class="feature-icon">&#x1F9F5;</div>
            <h3>Virtual Threads</h3>
            <p>Powered by Java 21+ Virtual Threads for lightweight, high-throughput concurrency without the complexity.</p>
          </div>
        </div>
      </section>

      <section class="about-section">
        <h2>Want to contribute?</h2>
        <p>
          This project is fully open source and lives on
          <a href="https://github.com/eldermoraes/swapi.build" target="_blank" rel="noopener">GitHub</a>.
          Pull requests are always welcome, whether it's fixing a bug, improving the docs, or adding new features.
          Jump in and help make the best Star Wars API in the galaxy even better.
        </p>
      </section>

      <section class="about-section about-credits">
        <h2>Credits &amp; Acknowledgements</h2>
        <p>
          This project was inspired by the original <a href="https://swapi.dev" target="_blank" rel="noopener">SWAPI</a>,
          created by <strong>Paul Hallett</strong> and currently maintained by <strong>Juriy Bura</strong>.
          The Star Wars data comes from open, community-driven sources such as
          <a href="https://starwars.fandom.com/" target="_blank" rel="noopener">Wookieepedia</a>.
        </p>
        <p>
          All Star Wars content and imagery are the property of
          <strong>Lucasfilm Ltd.</strong> and <strong>Disney</strong>.
          This project is not affiliated with or endorsed by Lucasfilm or Disney.
        </p>
      </section>
    </div>
  `;
}
