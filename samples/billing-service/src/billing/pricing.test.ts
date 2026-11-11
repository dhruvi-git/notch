import { describe, expect, it } from "vitest";
import { subtotal, withTax } from "./pricing";

describe("pricing", () => {
  it("adds tax to the subtotal", () => {
    expect(withTax(subtotal([{ quantity: 2, unitCents: 500 }]))).toBe(1180);
  });
});
