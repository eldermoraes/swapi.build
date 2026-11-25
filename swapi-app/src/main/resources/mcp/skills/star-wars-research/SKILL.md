---
name: star-wars-research
description: Research Star Wars questions about Episodes I-VI that take more than one call to this server's tools, such as who piloted a starship, where characters were born, or which planets appear in a film. Shows how to chain sw_search, sw_get, sw_list and sw_random, embed related records with expand, shorten lists with fields, and avoid taking a film id for an episode number.
---

# Star Wars research

This server answers from a fixed data set: the people, films, planets,
species, starships and vehicles of the films of Episodes I-VI. Nothing from
later films, series, books or games is in it. Its four tools only read.

## The tools

Every tool takes `resource`: one of PEOPLE, FILMS, PLANETS, SPECIES,
STARSHIPS, VEHICLES.

- `sw_search(resource, query, fields?)`: finds records whose name (the title,
  for FILMS) contains `query`, in any case. Returns an array of records; `[]`
  means no match. Start here when the question names something.
- `sw_get(resource, id, expand?)`: one record by id. An unknown id returns a
  tool error.
- `sw_list(resource, fields?)`: every record of one type. Large; pass
  `fields` unless you need whole records.
- `sw_random(resource)`: one random record, for demos and quizzes only.

## Ids and relations

Every record has a `url`. The id `sw_get` takes is its last segment: the
record at `.../people/1` is PEOPLE id 1. Relations (`homeworld`, `films`,
`pilots`, `residents`...) are URLs of the same form, so the segment before
the id names the type of the related record.

Film ids are record ids, not episode numbers. FILMS id 1 is A New Hope
(Episode IV), and The Phantom Menace (Episode I) is not id 1. To find a film
by episode, read the `episode_id` field (for example `sw_list` FILMS with
`fields` ["title", "episode_id"]), or search its title. Never pass an episode
number as an id.

## Follow relations in one call

Pass `expand` to `sw_get` to embed the related records instead of one call
per URL. The record comes back with a final `expanded` object, one key per
relation asked for.

- Names per type are in references/relations.md; ["*"] alone means every
  relation of the type.
- At most 8 names. An unknown name returns a tool error.
- One level deep: an embedded record keeps its own relations as URLs.
- `homeworld` is one record or null. Every other relation is an array in the
  order of the URLs, with null where a URL matches no record.
- Ask only for the relations you need: ["*"] on a film is very large.

## Keep lists small

Pass `fields` to `sw_list` or `sw_search` to keep only some fields of each
record, for example ["name"] (["title"] for FILMS).

- At most 24 names. `url` is always kept.
- An unknown name returns a tool error that lists the valid ones; the names
  per type are also in references/relations.md.
- `sw_get` and `sw_random` do not take `fields`.

## Workflow

1. Find: `sw_search` with a short name fragment. Several matches: pick by
   name, or ask the user. No match: try a shorter fragment, or `sw_list`
   with `fields` ["name"].
2. Read: `sw_get` with the id from the `url`, expanding only the relations
   the question needs.
3. Go one level further only when needed, with another `sw_get` on an id
   taken from an embedded record's URLs.
4. Answer.

Example: "Which pilots of the Millennium Falcon were born on Tatooine?"

1. `sw_search` STARSHIPS "falcon" with `fields` ["name"]: the id is the last
   segment of the `url`.
2. `sw_get` STARSHIPS with that id and `expand` ["pilots"]: each pilot
   carries a `homeworld` URL.
3. `sw_search` PLANETS "tatooine" with `fields` ["name"], and keep the pilots
   whose `homeworld` URL ends with the same `/planets/<id>` as that planet's
   `url`.

Read references/relations.md before using `expand` or `fields` on a type
whose names you do not know.

## Answer from the tools only

Answer only from what the tools returned in this conversation. When the data
has no answer (an empty search, a value such as "unknown" or "n/a", something
outside Episodes I-VI), say so instead of filling the gap from memory. Name
the records the answer rests on.
