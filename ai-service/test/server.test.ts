import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { createApp } from "../src/server.ts";

const server = createApp();
let base = "";

before(async () => {
  await new Promise<void>((resolve) => server.listen(0, resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => server.close());

test("health reports UP", async () => {
  const res = await fetch(`${base}/health`);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).status, "UP");
});

test("inspection validates the request", async () => {
  const res = await fetch(`${base}/v1/inspections`, { method: "POST", body: JSON.stringify({ propertyId: "p1" }) });
  assert.equal(res.status, 400);
});

test("inspection is explicitly not implemented yet", async () => {
  const res = await fetch(`${base}/v1/inspections`, {
    method: "POST",
    body: JSON.stringify({ propertyId: "p1", photos: [{ storageKey: "properties/p1/1.jpg" }] }),
  });
  assert.equal(res.status, 501);
});

test("invalid JSON is a 400", async () => {
  const res = await fetch(`${base}/v1/inspections`, { method: "POST", body: "{" });
  assert.equal(res.status, 400);
});
