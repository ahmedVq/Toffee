// Called once a minute by pg_cron. Fires anything due and pushes it to the
// tablet over Realtime, so a reminder arrives even when nothing is polling.
//
// The local alarm on the tablet is the other half of this: whichever lands
// first wins. Neither path alone is reliable — Realtime needs a live socket,
// and a local alarm cannot know about a reminder set from somewhere else.

import { createClient } from "@supabase/supabase-js";
import { sweepDue } from "../_shared/sweep.ts";

const CHANNEL = "toffee";

Deno.serve(async () => {
  try {
    const fired = await sweepDue();

    if (fired.length) {
      const db = createClient(
        Deno.env.get("SUPABASE_URL")!,
        Deno.env.get("SUPABASE_SECRET_KEY")!,
      );
      const channel = db.channel(CHANNEL);
      await channel.subscribe();
      for (const reminder of fired) {
        await channel.send({
          type: "broadcast",
          event: "reminder",
          payload: reminder,
        });
      }
      await db.removeChannel(channel);
    }

    return Response.json({ fired });
  } catch (e) {
    // Never 500 at cron: a failed sweep must be visible in logs and retried
    // next minute, not turn into a silent gap in the schedule.
    console.error("sweep failed:", e instanceof Error ? e.name : "unknown");
    return Response.json({ fired: [], error: "sweep failed" }, { status: 200 });
  }
});
