-- A memory with no embedding is not a near match for anything.
--
-- The seeded history is selected by category and injected into every prompt,
-- so those rows never need a vector and are written without one. But
-- `m.embedding <=> query_embedding` is NULL for them, and Postgres sorts
-- NULLs last rather than dropping them -- so with few memories in the table
-- an unembedded fact would surface as a "nearest" match and be injected
-- twice, once as history and once as a recollection.
--
-- Unchanged otherwise, including SECURITY INVOKER: this function must not
-- become a way around RLS.

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
  where m.embedding is not null
  order by m.embedding <=> query_embedding
  limit greatest(match_count, 1)
$$;
