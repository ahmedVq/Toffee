import { assert, assertEquals } from "@std/assert";
import { CORE_FACTS, toWrite } from "../scripts/seed-memories.ts";

Deno.test("a fresh database gets every fact", () => {
  assertEquals(toWrite([]), CORE_FACTS);
});

// Running it twice is the normal case -- it is how a new fact gets added to
// an existing tablet -- and it must not leave Toffee believing anything
// twice over.
Deno.test("seeding twice writes nothing the second time", () => {
  assertEquals(toWrite(CORE_FACTS), []);
});

Deno.test("only the facts that are missing get written", () => {
  const already = CORE_FACTS.slice(0, 3);
  assertEquals(toWrite(already), CORE_FACTS.slice(3));
});

// Review Focus 5. Being honest about what this does and does not protect:
// deleting a row from the database is NOT enough, because the next run would
// put it back. This list is the authority, so the only thing the script
// guarantees is that it writes nothing that is not in it.
Deno.test("nothing outside the list can ever be written", () => {
  for (const f of toWrite([])) {
    assert(CORE_FACTS.includes(f), `would write a fact that is not in the list: ${f}`);
  }
});

// The pronoun trap that made this file first person. Toffee is a he and so
// is Ahmed; in Toffee's own voice "he" is unambiguously Ahmed, and the
// moment a fact says "him" about Toffee the whole set becomes unreadable.
Deno.test("the facts never call Toffee he", () => {
  for (const f of CORE_FACTS) {
    assert(
      !/\b(built him|made him|told him|finds him)\b/i.test(f),
      `ambiguous pronoun, Toffee must be "me" in his own memory: ${f}`,
    );
  }
});

Deno.test("every fact is about Ahmed, in the first person", () => {
  for (const f of CORE_FACTS) {
    assert(/^He\b/.test(f), `a core fact must start from Ahmed: ${f}`);
  }
});
