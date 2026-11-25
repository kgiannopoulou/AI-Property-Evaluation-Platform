import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { POLICY_VERSION, RETENTION } from "./legal.ts";

const repo = new URL("../../", import.meta.url);

describe("legal facts", () => {
  it("retention periods match the backend defaults", () => {
    const yml = readFileSync(new URL("backend/src/main/resources/application.yml", repo), "utf8");
    for (const row of RETENTION.filter((r) => r.key)) {
      const m = yml.match(new RegExp(`${row.key}: \\$\\{[A-Z_]+:(\\d+)\\}`));
      assert.ok(m, `application.yml has app.retention.${row.key}`);
      assert.equal(Number(m[1]), row.value, row.key);
    }
  });

  it("the policy version matches the backend's consent records", () => {
    const java = readFileSync(new URL("backend/src/main/java/com/propertyapp/privacy/ConsentService.java", repo), "utf8");
    assert.ok(java.includes(`POLICY_VERSION = "${POLICY_VERSION}"`));
  });
});
