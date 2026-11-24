# Relations and fields

For each resource type, two lists:

- expand: the relation names `sw_get` accepts in `expand`, each with the type
  of the records it embeds. `homeworld` holds one record (or null); every other
  relation holds a list.
- fields: the field names `sw_list` and `sw_search` accept in `fields`. `url`
  is always returned, whether or not `fields` names it.

A name that is not on a type's line returns a tool error. Expand is one level
deep: an embedded record keeps its own relations as URLs, and the next level
is another `sw_get`.

## PEOPLE

- expand: `homeworld` (PLANETS), `films` (FILMS), `species` (SPECIES), `vehicles` (VEHICLES), `starships` (STARSHIPS)
- fields: `birth_year`, `created`, `edited`, `eye_color`, `films`, `gender`, `hair_color`, `height`, `homeworld`, `mass`, `name`, `skin_color`, `species`, `starships`, `url`, `vehicles`

## FILMS

- expand: `characters` (PEOPLE), `planets` (PLANETS), `starships` (STARSHIPS), `vehicles` (VEHICLES), `species` (SPECIES)
- fields: `characters`, `created`, `director`, `edited`, `episode_id`, `opening_crawl`, `planets`, `producer`, `release_date`, `species`, `starships`, `title`, `url`, `vehicles`

## PLANETS

- expand: `residents` (PEOPLE), `films` (FILMS)
- fields: `climate`, `created`, `diameter`, `edited`, `films`, `gravity`, `name`, `orbital_period`, `population`, `residents`, `rotation_period`, `surface_water`, `terrain`, `url`

## SPECIES

- expand: `homeworld` (PLANETS), `people` (PEOPLE), `films` (FILMS)
- fields: `average_height`, `average_lifespan`, `classification`, `created`, `designation`, `edited`, `eye_colors`, `films`, `hair_colors`, `homeworld`, `language`, `name`, `people`, `skin_colors`, `url`

## STARSHIPS

- expand: `pilots` (PEOPLE), `films` (FILMS)
- fields: `MGLT`, `cargo_capacity`, `consumables`, `cost_in_credits`, `created`, `crew`, `edited`, `films`, `hyperdrive_rating`, `length`, `manufacturer`, `max_atmosphering_speed`, `model`, `name`, `passengers`, `pilots`, `starship_class`, `url`

## VEHICLES

- expand: `pilots` (PEOPLE), `films` (FILMS)
- fields: `cargo_capacity`, `consumables`, `cost_in_credits`, `created`, `crew`, `edited`, `films`, `length`, `manufacturer`, `max_atmosphering_speed`, `model`, `name`, `passengers`, `pilots`, `url`, `vehicle_class`
