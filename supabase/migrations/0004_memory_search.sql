-- Nearest-neighbour recall over memories.
--
-- Cosine distance (<=>) because gte-small vectors are normalised, so cosine
-- and dot product rank identically and cosine reads more obviously.
-- SECURITY INVOKER: callers are Edge Functions holding the secret key, which
-- bypasses RLS anyway; this function must not become a way around it.

create or replace function match_memories(
  query_embedding vector(384),
  match_count int default 5
)
returns table (id uuid, fact text, category text, distance float)
language sql
stable
security invoker
set search_path = public
as $$
  select m.id, m.fact, m.category, m.embedding <=> query_embedding as distance
  from memories m
  order by m.embedding <=> query_embedding
  limit greatest(match_count, 1)
$$;
