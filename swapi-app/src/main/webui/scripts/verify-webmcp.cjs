// Run against a built server: SWAPI_TEST_URL=http://127.0.0.1:5545 npm run test:browser
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const base = process.env.SWAPI_TEST_URL || 'http://127.0.0.1:5545';
(async () => {
  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--enable-features=WebMCP', '--enable-blink-features=WebMCP'],
  });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(`${base}/docs/webmcp`);
    await page.waitForFunction(() =>
      document.querySelector('[data-webmcp-status]')?.textContent.includes('All four'),
    );
    const names = await page.evaluate(async () =>
      (await document.modelContext.getTools()).map((t) => t.name).sort(),
    );
    assert.deepEqual(names, ['sw_get', 'sw_list', 'sw_random', 'sw_search']);
    const call = (name, args) =>
      page.evaluate(
        async ({ name, args }) => {
          const tool = (await document.modelContext.getTools()).find((t) => t.name === name);
          return JSON.parse(await document.modelContext.executeTool(tool, JSON.stringify(args)));
        },
        { name, args },
      );
    for (const resource of ['PEOPLE', 'FILMS', 'PLANETS', 'SPECIES', 'STARSHIPS', 'VEHICLES']) {
      const result = await call('sw_list', { resource });
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(await page.locator('.item-card').count(), result.data.length);
      console.log('list', resource, result.count, 'bytes', JSON.stringify(result).length);
    }
    const search = await call('sw_search', { resource: 'PEOPLE', query: 'Luke' });
    assert.equal(search.data[0].name, 'Luke Skywalker');
    await call('sw_list', { resource: 'PEOPLE' });
    assert.equal(await page.locator('#search-input').inputValue(), '');
    const film = await call('sw_get', { resource: 'FILMS', id: 1 });
    assert.equal(film.data.title, 'A New Hope');
    assert.equal(await page.locator('h1').textContent(), 'A New Hope');
    const requests = [];
    page.on('request', (r) => {
      if (r.url().includes('/api/')) requests.push(r.url());
    });
    const random = await call('sw_random', { resource: 'STARSHIPS' });
    assert.equal(random.ok, true);
    assert.equal(await page.locator('h1').textContent(), random.data.name);
    assert.equal(requests.length, 1);
    const missing = await call('sw_get', { resource: 'PEOPLE', id: 99999 });
    assert.equal(missing.error.code, 'NOT_FOUND');
    await call('sw_search', { resource: 'PEOPLE', query: '' });
    // Delay a real response, then give the user priority through input.
    let release;
    let started;
    const hasStarted = new Promise((resolve) => {
      started = resolve;
    });
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    await page.route('**/api/people', async (route) => {
      started();
      await gate;
      await route.continue().catch(() => {});
    });
    const pending = call('sw_list', { resource: 'PEOPLE' });
    await hasStarted;
    const busy = await call('sw_random', { resource: 'PEOPLE' });
    assert.equal(busy.error.code, 'BUSY');
    await page.locator('#search-input').fill('Leia');
    release();
    const cancelled = await pending;
    assert.equal(cancelled.error.code, 'SUPERSEDED');
    assert.equal(await page.locator('#search-input').inputValue(), 'Leia');
    await page.unroute('**/api/people');
    // Existing terminal and docs controls must also outrank a pending agent.
    for (const action of ['run', 'suggestion', 'enter', 'try-it']) {
      await page.goto(`${base}${action === 'try-it' ? '/docs' : '/'}`);
      const control =
        action === 'try-it'
          ? page.locator('.try-button').first()
          : action === 'suggestion'
            ? page.locator('.sw-term-chips button').first()
            : action === 'enter'
              ? page.locator('#home-term-input')
              : page.locator('.sw-term-exec');
      await control.waitFor();
      if (action === 'run' || action === 'enter')
        await page.locator('#home-term-input').fill('people/1');
      let releaseRequest;
      const requestGate = new Promise((resolve) => {
        releaseRequest = resolve;
      });
      let requestStarted;
      const requestReady = new Promise((resolve) => {
        requestStarted = resolve;
      });
      await page.route('**/api/vehicles', async (route) => {
        requestStarted();
        await requestGate;
        await route.continue().catch(() => {});
      });
      const agentAction = call('sw_list', { resource: 'VEHICLES' });
      await requestReady;
      if (action === 'enter') await control.press('Enter');
      else await control.click();
      releaseRequest();
      assert.equal((await agentAction).error.code, 'SUPERSEDED');
      assert.equal(new URL(page.url()).pathname, action === 'try-it' ? '/docs' : '/');
      await page.waitForFunction(
        (isDocs) =>
          isDocs
            ? document.querySelector('.try-result')?.textContent.includes('HTTP 200')
            : document.querySelector('.sw-term-out')?.textContent.includes('Luke Skywalker'),
        action === 'try-it',
      );
      await page.unroute('**/api/vehicles');
    }
    await page.goto(`${base}/docs/webmcp`);
    await page.setViewportSize({ width: 360, height: 800 });
    await page.waitForFunction(
      () =>
        document.querySelector('h1').getBoundingClientRect().top >=
        document.querySelector('header').getBoundingClientRect().bottom,
    );
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      true,
    );
    await page.screenshot({
      path: require('node:path').join(require('node:os').tmpdir(), 'swapi-webmcp-mobile.png'),
      fullPage: true,
    });
    assert.deepEqual(errors, []);
    console.log(
      'PASS browser tools, schemas, all resources, same-route reset, random identity, cancellation, mobile, no JS errors',
    );
  } finally {
    await browser.close();
  }
  const unsupported = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--disable-blink-features=WebMCP', '--disable-features=WebMCP'],
  });
  try {
    const page = await unsupported.newPage();
    await page.goto(`${base}/docs/webmcp`);
    assert.match(await page.locator('[data-webmcp-status]').innerText(), /not available/);
    await page.locator('a[href="/resource/people"]').click();
    await page.locator('#search-input').fill('Leia');
    await page.locator('#search-btn').click();
    await page.waitForFunction(() => document.querySelectorAll('.item-card').length === 1);
    assert.equal(await page.locator('.item-name').textContent(), 'Leia Organa');
    await page.goBack();
    assert.equal(await page.locator('h1').textContent(), 'WebMCP in the browser');
    console.log('PASS unsupported browser, manual search, history');
  } finally {
    await unsupported.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
