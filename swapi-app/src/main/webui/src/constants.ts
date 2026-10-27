import { site } from './site';

export interface ResourceMeta {
  key: string;
  icon: string;
  title: string;
  endpoint: string;
  nameField: string;
  detailField: string;
}

/** The short name of a type, resources[].label in seo/site.json (2.11.0): one copy, shared with the server. */
function labelOf(type: string): string {
  const resource = site.resources.find((r) => r.type === type);
  if (!resource?.label) throw new Error(`seo/site.json: resources: no label for "${type}"`);
  return resource.label;
}

function meta(key: string, icon: string, nameField: string, detailField: string): ResourceMeta {
  return { key, icon, title: labelOf(key), endpoint: `/api/${key}`, nameField, detailField };
}

export const RESOURCES: ResourceMeta[] = [
  meta('people', '\u{1F464}', 'name', 'birth_year'),
  meta('films', '\u{1F3AC}', 'title', 'director'),
  meta('planets', '\u{1FA90}', 'name', 'climate'),
  meta('species', '\u{1F9EC}', 'name', 'classification'),
  meta('starships', '\u{1F680}', 'name', 'model'),
  meta('vehicles', '\u{1F3CE}️', 'name', 'model'),
];

export function getResourceMeta(type: string): ResourceMeta {
  return (
    RESOURCES.find((r) => r.key === type) ?? {
      key: type,
      icon: '',
      title: type,
      endpoint: `/api/${type}`,
      nameField: 'name',
      detailField: '',
    }
  );
}
