import { describe, expect, it } from 'vitest';
import golden from '../../../test/resources/seo/golden.json';
import people from '../../resources/data/people.json';
import films from '../../resources/data/films.json';
import planets from '../../resources/data/planets.json';
import species from '../../resources/data/species.json';
import starships from '../../resources/data/starships.json';
import vehicles from '../../resources/data/vehicles.json';
import { getSeoMetadata } from './seo';

/**
 * The client side of src/test/resources/seo/golden.json; SeoGoldenTest is the
 * server side. Both check every case, so the Java and the TS template engines
 * cannot drift apart unseen (2.10.0, replaces seo-parity.test.ts).
 */
const DATA: Record<string, Record<string, unknown>[]> = {
  people,
  films,
  planets,
  species,
  starships,
  vehicles,
};

/** The record of a record address, read from the data by the suffix of its url. */
function recordOf(path: string): Record<string, unknown> | undefined {
  const parts = path.split('/');
  if (parts.length !== 4 || parts[1] !== 'resource') return undefined;
  return DATA[parts[2]]?.find((entity) => entity.url === `/${parts[2]}/${parts[3]}`);
}

describe('golden.json on the client', () => {
  it('has the cases the server checks', () => {
    expect(golden.cases.length).toBeGreaterThanOrEqual(26);
  });

  it.each(golden.cases.map((c) => [c.path, c] as const))('%s', (path, expected) => {
    const record = recordOf(path);
    if (path.split('/').length === 4 && path.startsWith('/resource/')) expect(record).toBeDefined();
    const meta = getSeoMetadata(path, record);
    expect(meta.title).toBe(expected.title);
    expect(meta.description).toBe(expected.description);
    expect(meta.robots).toBe(expected.robots);
    expect(meta.canonicalPath).toBe(expected.canonicalPath);
  });
});
