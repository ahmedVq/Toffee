// The tool surface the model sees, and the dispatcher that runs it.
//
// Everything is matched by description rather than id, because every one of
// these arrives by voice. A tool that needs a UUID is a tool the agent can
// never call.

import type { ToolSpec } from "../llm.ts";
import type { Mood } from "../types.ts";
import {
  addTask,
  completeTask,
  deleteTask,
  listTasks,
  uncompleteTask,
} from "./tasks.ts";
import {
  cancelReminder,
  listReminders,
  nag,
  setReminder,
  startTimer,
} from "./time.ts";
import { recall, remember, updateProfile } from "./memory.ts";

const str = (description: string) => ({ type: "string", description });

export const ALL_TOOLS: ToolSpec[] = [
  {
    name: "list_tasks",
    description: "List Ahmed's tasks. 'stale' means open and untouched for over three days.",
    parameters: {
      type: "object",
      properties: {
        filter: { type: "string", enum: ["open", "done", "stale", "all"] },
      },
      required: ["filter"],
    },
  },
  {
    name: "add_task",
    description: "Add a task to Ahmed's list.",
    parameters: {
      type: "object",
      properties: {
        title: str("What the task is, in his words"),
        dueAt: str("ISO timestamp, if he gave a deadline"),
      },
      required: ["title"],
    },
  },
  {
    name: "complete_task",
    description:
      "Tick off a task by description. If it returns ambiguous, ask which one — never guess.",
    parameters: {
      type: "object",
      properties: { title: str("Roughly what he called it") },
      required: ["title"],
    },
  },
  {
    name: "uncomplete_task",
    description: "Reopen a task he said was done.",
    parameters: {
      type: "object",
      properties: { title: str("Roughly what he called it") },
      required: ["title"],
    },
  },
  {
    name: "delete_task",
    description: "Remove a task entirely.",
    parameters: {
      type: "object",
      properties: { title: str("Roughly what he called it") },
      required: ["title"],
    },
  },
  {
    name: "set_reminder",
    description: "Remind him of something later. Phrases like 'in 50 minutes' or 'at 3pm'.",
    parameters: {
      type: "object",
      properties: {
        text: str("What to remind him about"),
        when: str("When, in his words"),
      },
      required: ["text", "when"],
    },
  },
  {
    name: "start_timer",
    description: "Start a countdown of a given number of minutes.",
    parameters: {
      type: "object",
      properties: {
        minutes: { type: "number", description: "How long" },
        label: str("What it is for"),
      },
      required: ["minutes", "label"],
    },
  },
  {
    name: "list_reminders",
    description: "What is still pending, soonest first.",
    parameters: { type: "object", properties: {} },
  },
  {
    name: "cancel_reminder",
    description: "Cancel a pending reminder.",
    parameters: {
      type: "object",
      properties: { query: str("Roughly what it said") },
      required: ["query"],
    },
  },
  {
    name: "nag",
    description:
      "Promise to come back to a specific task later, and actually do it. Use when he is avoiding something.",
    parameters: {
      type: "object",
      properties: {
        task: str("Roughly what the task is called"),
        when: str("When to bring it up again"),
      },
      required: ["task", "when"],
    },
  },
  {
    name: "remember",
    description:
      "Keep a durable fact about Ahmed — a preference, a habit, a person, something he told you once.",
    parameters: {
      type: "object",
      properties: {
        fact: str("The fact, stated plainly"),
        category: str("preference | habit | person | project | other"),
      },
      required: ["fact", "category"],
    },
  },
  {
    name: "recall",
    description: "Search what you remember about him.",
    parameters: {
      type: "object",
      properties: { query: str("What you are trying to remember") },
      required: ["query"],
    },
  },
  {
    name: "update_profile",
    description: "Update a standing fact: name, role, working hours, timezone.",
    parameters: {
      type: "object",
      properties: { field: str("Which field"), value: str("New value") },
      required: ["field", "value"],
    },
  },
  {
    name: "set_mood",
    description:
      "Set your face. Call this whenever your mood changes — it is how Ahmed sees what you feel.",
    parameters: {
      type: "object",
      properties: {
        mood: {
          type: "string",
          enum: [
            "neutral",
            "happy",
            "sad",
            "frustrated",
            "loving",
            "smug",
            "sleepy",
            "listening",
          ],
        },
      },
      required: ["mood"],
    },
  },
];

const MOODS = new Set<Mood>([
  "neutral",
  "happy",
  "sad",
  "frustrated",
  "loving",
  "smug",
  "sleepy",
  "listening",
]);

export type Dispatcher = {
  dispatch: (name: string, args: unknown) => Promise<unknown>;
  mood: () => Mood;
};

/** One dispatcher per turn, because mood is per-turn state. */
export function makeDispatcher(): Dispatcher {
  let mood: Mood = "neutral";

  const dispatch = async (name: string, rawArgs: unknown): Promise<unknown> => {
    const a = (rawArgs ?? {}) as Record<string, unknown>;
    const s = (k: string) => String(a[k] ?? "");

    switch (name) {
      case "list_tasks":
        return await listTasks((s("filter") || "open") as "open" | "done" | "stale" | "all");
      case "add_task":
        return await addTask(s("title"), a.dueAt ? s("dueAt") : undefined);
      case "complete_task":
        return await completeTask(s("title"));
      case "uncomplete_task":
        return await uncompleteTask(s("title"));
      case "delete_task":
        return await deleteTask(s("title"));
      case "set_reminder":
        return await setReminder(s("text"), s("when"));
      case "start_timer":
        return await startTimer(Number(a.minutes ?? 0), s("label"));
      case "list_reminders":
        return await listReminders();
      case "cancel_reminder":
        return await cancelReminder(s("query"));
      case "nag":
        return await nag(s("task"), s("when"));
      case "remember":
        return await remember(s("fact"), s("category") || "other");
      case "recall":
        return await recall(s("query"));
      case "update_profile":
        return await updateProfile(s("field"), s("value"));
      case "set_mood": {
        const asked = s("mood") as Mood;
        // An unknown mood leaves the face alone rather than blanking it.
        if (MOODS.has(asked)) mood = asked;
        return { mood };
      }
      default:
        throw new Error(`unknown tool: ${name}`);
    }
  };

  return { dispatch, mood: () => mood };
}
