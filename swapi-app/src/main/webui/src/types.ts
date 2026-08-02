export interface SWResource {
  created: string;
  edited: string;
  url: string;
}

export interface Person extends SWResource {
  name: string;
  height: string;
  mass: string;
  hair_color: string;
  skin_color: string;
  eye_color: string;
  birth_year: string;
  gender: string;
  homeworld: string;
  films: string[];
  species: string[];
  starships: string[];
}

export interface Film extends SWResource {
  title: string;
  episode_id: number;
  opening_crawl: string;
  director: string;
  producer: string;
  release_date: string;
  characters: string[];
  planets: string[];
  starships: string[];
  vehicles: string[];
  species: string[];
}

export interface Planet extends SWResource {
  name: string;
  rotation_period: string;
  orbital_period: string;
  diameter: string;
  climate: string;
  gravity: string;
  terrain: string;
  surface_water: string;
  population: string;
  residents: string[];
  films: string[];
}

export interface Specie extends SWResource {
  name: string;
  classification: string;
  designation: string;
  average_height: string;
  skin_colors: string;
  hair_colors: string;
  eye_colors: string;
  average_lifespan: string;
  homeworld: string;
  language: string;
  people: string[];
  films: string[];
}

export interface Starship extends SWResource {
  name: string;
  model: string;
  manufacturer: string;
  cost_in_credits: string;
  length: string;
  max_atmosphering_speed: string;
  crew: string;
  passengers: string;
  cargo_capacity: string;
  consumables: string;
  hyperdrive_rating: string;
  MGLT: string;
  starship_class: string;
  pilots: string[];
  films: string[];
}

export interface Vehicle extends SWResource {
  name: string;
  model: string;
  manufacturer: string;
  cost_in_credits: string;
  length: string;
  max_atmosphering_speed: string;
  crew: string;
  passengers: string;
  cargo_capacity: string;
  consumables: string;
  vehicle_class: string;
  pilots: string[];
  films: string[];
}

// Subconjunto do documento OpenAPI 3.x consumido pela página de docs
export interface OpenApiParameter {
  name: string;
  in: 'path' | 'query';
  description?: string;
  example?: string;
}

export interface OpenApiOperation {
  summary?: string;
  description?: string;
  tags?: string[];
  parameters?: OpenApiParameter[];
  responses: Record<string, { description?: string }>;
}

export interface OpenApiSchemaObj {
  description?: string;
  type?: string;
  properties?: Record<string, OpenApiSchemaObj>;
  items?: OpenApiSchemaObj;
}

export interface OpenApiSpec {
  openapi: string;
  info: { title: string; version: string; description?: string };
  tags?: { name: string; description?: string }[];
  paths: Record<string, { get?: OpenApiOperation }>;
  components?: { schemas?: Record<string, OpenApiSchemaObj> };
}
