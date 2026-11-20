// Proves the service client reaches a table the device identity cannot.
// `said_log` has RLS enabled and no policy (0001 + 0003), so the device sees
// nothing there by design. The secret key bypasses RLS entirely — if this
// test reads back a row it wrote, the client is genuinely privileged and the
// Edge Functions can rely on it.

import { assertEquals, assertExists } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";

Deno.test("serviceClient reads a row the device cannot", async () => {
  const db = serviceClient();
  const topicKey = `db-test-${crypto.randomUUID()}`;

  const { data: inserted, error: insertError } = await db
    .from("said_log")
    .insert({ said: "probe", reason: "db.test.ts", topic_key: topicKey })
    .select()
    .single();

  assertEquals(insertError, null);
  assertExists(inserted);

  try {
    const { data, error } = await db
      .from("said_log")
      .select("id, said, topic_key")
      .eq("topic_key", topicKey);

    assertEquals(error, null);
    assertEquals(data?.length, 1);
    assertEquals(data?.[0].said, "probe");
  } finally {
    await db.from("said_log").delete().eq("topic_key", topicKey);
  }
});
