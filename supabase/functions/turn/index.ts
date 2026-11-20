// POST /turn — Ahmed said something.
import { turnHandler } from "../_shared/turn.ts";
Deno.serve((req) => turnHandler(req));
