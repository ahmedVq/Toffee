// Whether Toffee is allowed to speak unprompted.
//
// A companion that comments every time you look up gets muted within a day,
// so silence is the default and speech has to earn its way past two gates:
//
//   annoyance — has it spoken recently, has it already made this point today
//   cost      — how much of today's free-tier LLM budget is left
//
// The distinction that matters: it suppresses *opinions*, never *obligations*.
// A reminder Ahmed set is something he asked for, and a timer that stays quiet
// because the agent was feeling chatty earlier is a timer he cannot trust.

import { serviceClient } from "./db.ts";

/** Minimum gap between unprompted remarks. */
export const COOLDOWN_MINUTES = 20;

/** Ceiling on LLM calls per day, so a loop cannot quietly drain the free tier. */
export const DAILY_CALL_BUDGET = 200;

/** Topic keys with this prefix are obligations and bypass every gate. */
export const ALWAYS_SPEAK_PREFIX = "reminder:";

export type Verdict = { ok: boolean; why: string };

const dayOf = (d: Date) => d.toISOString().slice(0, 10);

export async function maySpeak(
  topicKey: string,
  now: Date,
): Promise<Verdict> {
  if (topicKey.startsWith(ALWAYS_SPEAK_PREFIX)) {
    return { ok: true, why: "obligation: reminders always speak" };
  }

  const db = serviceClient();
  const today = dayOf(now);

  const [spendRes, sameTopicRes, recentRes] = await Promise.all([
    db.from("llm_spend").select("calls").eq("day", today).maybeSingle(),
    db.from("said_log").select("id").eq("topic_key", topicKey).gte("at", `${today}T00:00:00Z`)
      .limit(1),
    db.from("said_log").select("at").order("at", { ascending: false }).limit(1),
  ]);

  const calls = (spendRes.data as { calls: number } | null)?.calls ?? 0;
  if (calls >= DAILY_CALL_BUDGET) {
    return { ok: false, why: `daily budget spent (${calls}/${DAILY_CALL_BUDGET})` };
  }

  if ((sameTopicRes.data ?? []).length > 0) {
    return { ok: false, why: `already made this point today (${topicKey})` };
  }

  const lastAt = (recentRes.data?.[0] as { at: string } | undefined)?.at;
  if (lastAt) {
    const minutes = (now.getTime() - new Date(lastAt).getTime()) / 60_000;
    if (minutes < COOLDOWN_MINUTES) {
      return {
        ok: false,
        why: `spoke ${Math.round(minutes)}m ago, cooldown is ${COOLDOWN_MINUTES}m`,
      };
    }
  }

  return { ok: true, why: "clear" };
}

/** Record what was said, so the same point is not made twice. */
export async function recordSaid(
  said: string,
  topicKey: string,
  reason: string,
): Promise<void> {
  const { error } = await serviceClient()
    .from("said_log")
    .insert({ said, topic_key: topicKey, reason });
  if (error) throw new Error(`recording what was said failed: ${error.message}`);
}

/** One row per day, accumulated. Without this the cost gate never trips and
 * the free tier drains silently. */
export async function noteSpend(inTok: number, outTok: number): Promise<void> {
  const db = serviceClient();
  const today = dayOf(new Date());

  const { data: existing } = await db
    .from("llm_spend")
    .select("id, calls, tokens_in, tokens_out")
    .eq("day", today)
    .maybeSingle();

  const row = existing as
    | { id: string; calls: number; tokens_in: number; tokens_out: number }
    | null;

  const { error } = row
    ? await db.from("llm_spend").update({
      calls: row.calls + 1,
      tokens_in: row.tokens_in + inTok,
      tokens_out: row.tokens_out + outTok,
    }).eq("id", row.id)
    : await db.from("llm_spend").insert({
      day: today,
      calls: 1,
      tokens_in: inTok,
      tokens_out: outTok,
    });

  if (error) throw new Error(`recording spend failed: ${error.message}`);
}
