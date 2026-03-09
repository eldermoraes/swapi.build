export interface ResourceMeta {
  key: string;
  icon: string;
  title: string;
  endpoint: string;
  nameField: string;
  detailField: string;
}

export const RESOURCES: ResourceMeta[] = [
  {
    key: 'people',
    icon: '\u{1F464}',
    title: 'People',
    endpoint: '/api/people',
    nameField: 'name',
    detailField: 'birth_year',
  },
  {
    key: 'films',
    icon: '\u{1F3AC}',
    title: 'Films',
    endpoint: '/api/films',
    nameField: 'title',
    detailField: 'director',
  },
  {
    key: 'planets',
    icon: '\u{1FA90}',
    title: 'Planets',
    endpoint: '/api/planets',
    nameField: 'name',
    detailField: 'climate',
  },
  {
    key: 'species',
    icon: '\u{1F9EC}',
    title: 'Species',
    endpoint: '/api/species',
    nameField: 'name',
    detailField: 'classification',
  },
  {
    key: 'starships',
    icon: '\u{1F680}',
    title: 'Starships',
    endpoint: '/api/starships',
    nameField: 'name',
    detailField: 'model',
  },
  {
    key: 'vehicles',
    icon: '\u{1F3CE}\uFE0F',
    title: 'Vehicles',
    endpoint: '/api/vehicles',
    nameField: 'name',
    detailField: 'model',
  },
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
