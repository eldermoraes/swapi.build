import { describe, expect, it } from 'vitest';
import { recordValues, renderTemplate, site } from './site';
import { getRoute, isServerRendered } from './routes';
import { RESOURCES } from './constants';
import constantsSource from './constants.ts?raw';

// seo/site.json is the one source of page metadata (2.10.0), read by the server
// (SeoSiteFile.java) and here. The rules of the file itself are checked on the
// server side (SeoSiteFileTest); this checks how the site reads it.
describe('site.json on the client', () => {
  it.each(site.pages.map((page) => [page.path, page.page] as const))(
    '%s is the %s page for getRoute',
    (path, page) => {
      expect(getRoute(path)).toEqual({ page });
    },
  );

  it('has the same six types as the explorer (constants.ts)', () => {
    expect(site.resources.map((resource) => resource.type).sort()).toEqual(
      RESOURCES.map((resource) => resource.key).sort(),
    );
  });

  // Slice E1 (2.11.0): the server draws the <main> of these four, and only these.
  it('isServerRendered is true for home, docs, mcp, faq (2.15.0) and about only', () => {
    const rendered = site.pages
      .filter((page) => isServerRendered(getRoute(page.path)))
      .map((page) => page.page);
    expect(rendered).toEqual(['home', 'docs', 'mcp', 'faq', 'about']);
    expect(isServerRendered({ page: 'resource-detail', type: 'films', id: '1' })).toBe(false);
    expect(isServerRendered({ page: 'resource-list', type: 'films' })).toBe(false);
    expect(isServerRendered({ page: 'not-found' })).toBe(false);
  });

  // One copy of the type names: the label in site.json, not a title in constants.ts.
  it('takes the explorer titles from resources[].label', () => {
    expect(RESOURCES.map((resource) => resource.title)).toEqual(
      site.resources.map((resource) => resource.label),
    );
    expect(constantsSource).not.toMatch(/title:\s*'/);
  });

  it('marks only films as indexable (the first batch)', () => {
    expect(
      site.resources.filter((resource) => resource.detail.indexable).map((r) => r.type),
    ).toEqual(['films']);
  });
});

describe('renderTemplate (same rule as SeoSiteFile.render)', () => {
  it('replaces the keys and leaves text that is not a key alone', () => {
    expect(renderTemplate('{title} #{id} {not a key} | x', { title: 'A New Hope', id: '1' })).toBe(
      'A New Hope #1 {not a key} | x',
    );
  });

  it('is undefined when a value is missing or blank, so the caller falls back', () => {
    expect(renderTemplate('{title} #{id}', { id: '1' })).toBeUndefined();
    expect(renderTemplate('{title}', { title: ' ' })).toBeUndefined();
  });

  it('takes strings and numbers from a record, numbers in plain decimal', () => {
    expect(
      recordValues({ title: 'A New Hope', episode_id: 4, director: null, extra: {} }, [
        'title',
        'episode_id',
        'director',
        'extra',
      ]),
    ).toEqual({ title: 'A New Hope', episode_id: '4' });
  });
});
