const ENDPOINTS = [
  {
    title: 'Root',
    endpoints: [
      { method: 'GET', path: '/api/', desc: 'List all available resources and their URLs.' },
    ],
  },
  {
    title: 'People',
    endpoints: [
      { method: 'GET', path: '/api/people/', desc: 'Get all people resources.' },
      { method: 'GET', path: '/api/people/:id/', desc: 'Get a specific people resource by ID.' },
      { method: 'GET', path: '/api/people/random/', desc: 'Get a random people resource.' },
      { method: 'GET', path: '/api/people/?search=name', desc: 'Search people by name.' },
    ],
  },
  {
    title: 'Films',
    endpoints: [
      { method: 'GET', path: '/api/films/', desc: 'Get all film resources.' },
      { method: 'GET', path: '/api/films/:id/', desc: 'Get a specific film resource by ID.' },
      { method: 'GET', path: '/api/films/random/', desc: 'Get a random film resource.' },
      { method: 'GET', path: '/api/films/?search=title', desc: 'Search films by title.' },
    ],
  },
  {
    title: 'Planets',
    endpoints: [
      { method: 'GET', path: '/api/planets/', desc: 'Get all planet resources.' },
      { method: 'GET', path: '/api/planets/:id/', desc: 'Get a specific planet resource by ID.' },
      { method: 'GET', path: '/api/planets/random/', desc: 'Get a random planet resource.' },
      { method: 'GET', path: '/api/planets/?search=name', desc: 'Search planets by name.' },
    ],
  },
  {
    title: 'Species',
    endpoints: [
      { method: 'GET', path: '/api/species/', desc: 'Get all species resources.' },
      { method: 'GET', path: '/api/species/:id/', desc: 'Get a specific species resource by ID.' },
      { method: 'GET', path: '/api/species/random/', desc: 'Get a random species resource.' },
      { method: 'GET', path: '/api/species/?search=name', desc: 'Search species by name.' },
    ],
  },
  {
    title: 'Starships',
    endpoints: [
      { method: 'GET', path: '/api/starships/', desc: 'Get all starship resources.' },
      {
        method: 'GET',
        path: '/api/starships/:id/',
        desc: 'Get a specific starship resource by ID.',
      },
      { method: 'GET', path: '/api/starships/random/', desc: 'Get a random starship resource.' },
      { method: 'GET', path: '/api/starships/?search=name', desc: 'Search starships by name.' },
    ],
  },
  {
    title: 'Vehicles',
    endpoints: [
      { method: 'GET', path: '/api/vehicles/', desc: 'Get all vehicle resources.' },
      { method: 'GET', path: '/api/vehicles/:id/', desc: 'Get a specific vehicle resource by ID.' },
      { method: 'GET', path: '/api/vehicles/random/', desc: 'Get a random vehicle resource.' },
      { method: 'GET', path: '/api/vehicles/?search=name', desc: 'Search vehicles by name.' },
    ],
  },
];

export function renderDocumentation(container: HTMLElement): void {
  container.innerHTML = `
    <div class="docs">
      <h1>Documentation</h1>
      <p class="docs-intro">
        The Star Wars API is a RESTful public API that provides data about the Star Wars universe.
        All responses are returned in JSON format.
      </p>

      ${ENDPOINTS.map(
        (section) => `
        <h2>${section.title}</h2>
        ${section.endpoints
          .map(
            (ep) => `
          <div class="endpoint-block">
            <div class="endpoint-method">
              <span class="method-badge">${ep.method}</span>
              <span class="endpoint-path">${ep.path}</span>
            </div>
            <div class="endpoint-desc">${ep.desc}</div>
          </div>
        `,
          )
          .join('')}
      `,
      ).join('')}
    </div>
  `;
}
