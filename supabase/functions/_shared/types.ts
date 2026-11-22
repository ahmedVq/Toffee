// Shared vocabulary for the whole backend.
//
// These are the TypeScript shapes. Postgres columns are snake_case; the
// translation between the two lives in db.ts and nowhere else, so field names
// cannot drift apart table by table.

export type Task = {
  id: string;
  title: string;
  status: "open" | "done";
  dueAt: string | null;
  project: string | null;
};

export type Reminder = {
  id: string;
  text: string;
  dueAt: string;
  firedAt: string | null;
  recurring: string | null;
};

export type Memory = {
  id: string;
  fact: string;
  category: string;
};

/** One turn of conversation. "model" is Gemini's word for the assistant, and
 * it is what `conversation.role` stores — see migration 0002. */
export type Msg = { role: "user" | "model"; content: string };

/** What the face shows. The tablet renders each of these as a colour plus an
 * eye shape; colour alone cannot distinguish sad from sleepy. */
export type Mood =
  | "neutral"
  | "happy"
  | "sad"
  | "frustrated"
  | "loving"
  | "smug"
  | "sleepy"
  | "listening";

/** Injected into every prompt rather than exposed as a tool, so the agent
 * cannot forget to look and we do not pay a round trip for it. */
export type Ctx = {
  now: string;
  present: boolean;
  atDeskMinutes: number | null;
  tabletBattery: number | null;
  openTaskCount: number;
  lastSaid: string | null;

  /**
   * What he knows about Ahmed regardless of what is being discussed —
   * the seeded history, injected on every turn and every event.
   *
   * Kept apart from [relevantFacts] because these are claims that outlive
   * the week they were written in: "he finds me too slow" is a fact today
   * and a lie after the next release. Seeded facts carry one category so
   * they stay addressable and correctable as a set.
   */
  coreFacts: string[];

  /** Recalled semantically against whatever he just said. Empty when
   *  nothing was said, which is every event. */
  relevantFacts: string[];
};
