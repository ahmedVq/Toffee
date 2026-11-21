// POST /listen — the tablet recorded him; this turns it into words.
import { listenHandler } from "../_shared/listen.ts";
Deno.serve((req) => listenHandler(req));
