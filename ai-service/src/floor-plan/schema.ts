import { z } from "zod";

/**
 * What the model must return for one floor plan. Every number is something PRINTED on the
 * drawing; the model never measures pixels. Unprinted values are null.
 */
export const ROOM_TYPES = [
  "living-room", "kitchen", "dining", "bedroom", "bathroom", "wc", "hall", "corridor", "storage",
  "balcony", "terrace", "laundry", "office", "other",
] as const;
export type RoomType = (typeof ROOM_TYPES)[number];

export const FloorPlanResultSchema = z.object({
  is_floor_plan: z.boolean().describe("False if the image is not an architectural floor plan."),
  legibility: z.enum(["clear", "partly-legible", "illegible"]),
  units: z.enum(["metres", "centimetres", "millimetres", "feet", "not-shown"]).describe("Units of the printed dimensions."),
  scale_printed: z.string().nullable().describe("The scale as printed, e.g. 1:50, or null."),
  rooms: z.array(
    z.object({
      label: z.string().describe("The room name as written on the plan, or a short description if unlabelled."),
      room_type: z.enum(ROOM_TYPES),
      area_sqm: z.number().nullable().describe("Area printed for this room in square metres, or null if none is printed."),
      width_m: z.number().nullable().describe("Printed dimension converted to metres, or null."),
      length_m: z.number().nullable().describe("Printed dimension converted to metres, or null."),
      printed_text: z.string().describe("The exact text printed in or next to the room (area or dimensions); empty if none."),
      confidence: z.number().describe("0 to 1: how sure you are of the label and the numbers you read."),
    }),
  ),
  total_area_sqm: z.number().nullable().describe("A total area printed on the plan, or null."),
  total_area_text: z.string().describe("The exact printed text of the total, or empty."),
  notes: z.array(z.string()).describe("Anything a buyer should check: rooms without dimensions, unclear text, annotations."),
});
export type FloorPlanResult = z.infer<typeof FloorPlanResultSchema>;
