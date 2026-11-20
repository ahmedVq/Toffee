// Called once a minute by pg_cron. Fires anything due and pushes it to the
// tablet over Realtime.
//
// The local alarm on the tablet is the other half: whichever lands first
// wins. Neither alone is reliable — Realtime needs a live socket, and a local
// alarm cannot know about a reminder set from elsewhere.
import { sweepHandler } from "../_shared/sweep.ts";
Deno.serve(() => sweepHandler());
