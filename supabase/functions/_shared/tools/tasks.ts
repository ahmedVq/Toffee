// Task tools.
//
// Everything arrives by voice, so nothing is addressed by id — the agent says
// "the deck thing" and we work out which task that is. The important property
// is that a near-tie refuses rather than guesses: silently ticking off the
// wrong task is worse than asking which one, because the user never finds out.

import { serviceClient, toTask } from "../db.ts";
import type { Task } from "../types.ts";

export type AmbiguousMatch = { ambiguous: true; candidates: Task[] };

/** A query token must appear in at least this share of the title to count as
 * a candidate at all. Below it, we would be matching noise. */
const MIN_SCORE = 0.5;

/** If the runner-up scores within this fraction of the winner, the match is
 * too close to call. */
const TIE_BAND = 0.15;

const STALE_DAYS = 3;

function tokens(s: string): string[] {
  return s
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .split(/\s+/)
    .filter(Boolean);
}

/** Share of the query's words that appear in the title. */
function score(queryTokens: string[], title: string): number {
  if (!queryTokens.length) return 0;
  const inTitle = new Set(tokens(title));
  const hits = queryTokens.filter((t) => inTitle.has(t)).length;
  return hits / queryTokens.length;
}

async function allTasks(): Promise<Task[]> {
  const { data, error } = await serviceClient().from("tasks").select("*");
  if (error) throw new Error(`listing tasks failed: ${error.message}`);
  return (data ?? []).map(toTask);
}

/** The single task a query names, an AmbiguousMatch if it is too close to
 * call, or null if nothing is close enough. */
async function findOne(
  titleQuery: string,
): Promise<Task | AmbiguousMatch | null> {
  const q = tokens(titleQuery);
  const ranked = (await allTasks())
    .map((task) => ({ task, s: score(q, task.title) }))
    .filter((r) => r.s >= MIN_SCORE)
    .sort((a, b) => b.s - a.s);

  if (!ranked.length) return null;
  if (ranked.length === 1) return ranked[0].task;

  const [best, runnerUp] = ranked;
  if ((best.s - runnerUp.s) / best.s <= TIE_BAND) {
    return {
      ambiguous: true,
      candidates: ranked.filter((r) => (best.s - r.s) / best.s <= TIE_BAND)
        .map((r) => r.task),
    };
  }
  return best.task;
}

export async function listTasks(
  filter: "open" | "done" | "stale" | "all",
): Promise<Task[]> {
  let q = serviceClient().from("tasks").select("*");

  if (filter === "open") q = q.eq("status", "open");
  if (filter === "done") q = q.eq("status", "done");
  if (filter === "stale") {
    const cutoff = new Date(Date.now() - STALE_DAYS * 86_400_000).toISOString();
    q = q.eq("status", "open").lt("created_at", cutoff);
  }

  const { data, error } = await q;
  if (error) throw new Error(`listing tasks failed: ${error.message}`);
  return (data ?? []).map(toTask);
}

export async function addTask(title: string, dueAt?: string): Promise<Task> {
  const { data, error } = await serviceClient()
    .from("tasks")
    .insert({ title, status: "open", due_at: dueAt ?? null })
    .select()
    .single();
  if (error) throw new Error(`adding task failed: ${error.message}`);
  return toTask(data);
}

/** Setting a status that is already set is not an error — the agent may be
 * told to finish something twice, and complaining about it would be pedantic. */
async function setStatus(
  titleQuery: string,
  status: "open" | "done",
): Promise<Task | AmbiguousMatch | null> {
  const found = await findOne(titleQuery);
  if (found === null || "ambiguous" in found) return found;

  const { data, error } = await serviceClient()
    .from("tasks")
    .update({ status, done_at: status === "done" ? new Date().toISOString() : null })
    .eq("id", found.id)
    .select()
    .single();
  if (error) throw new Error(`updating task failed: ${error.message}`);
  return toTask(data);
}

export function completeTask(titleQuery: string) {
  return setStatus(titleQuery, "done");
}

export function uncompleteTask(titleQuery: string) {
  return setStatus(titleQuery, "open");
}

export async function deleteTask(titleQuery: string): Promise<boolean> {
  const found = await findOne(titleQuery);
  if (found === null || "ambiguous" in found) return false;

  const { error } = await serviceClient().from("tasks").delete().eq("id", found.id);
  if (error) throw new Error(`deleting task failed: ${error.message}`);
  return true;
}
