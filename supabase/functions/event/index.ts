// POST /event — something happened; decide whether to speak.
import { eventHandler } from "../_shared/event.ts";
Deno.serve((req) => eventHandler(req));
