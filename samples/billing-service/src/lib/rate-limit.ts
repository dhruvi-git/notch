/**
 * Token-bucket rate limiting per API key, kept in Redis so limits hold across instances.
 * 60 requests a minute with bursts of 20.
 */
import { redis } from "./redis";

const RATE = 60;
const BURST = 20;

export async function allow(key: string): Promise<boolean> {
  const bucket = `rl:${key}`;
  const tokens = await redis.incrbyfloat(bucket, -1);
  if (Number(tokens) < -BURST) return false;
  await redis.expire(bucket, 60);
  return Number(tokens) <= RATE;
}
