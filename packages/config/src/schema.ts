import { providerRelayConfigSchema } from "@kunai/schemas";
import { z } from "zod";

/** Partial on-disk config boundary; additional keys pass through for forward compatibility. */
export const kitsuneConfigPartialSchema = z
  .object({
    automaticStreamFailover: z.boolean().optional(),
    prepareBackupStream: z.boolean().optional(),
    providerRelay: providerRelayConfigSchema.optional(),
  })
  .passthrough();

export { providerRelayConfigSchema as kitsuneProviderRelayConfigSchema };
