import type { NextConfig } from "next";

const config: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // next dev would otherwise write an AGENTS.md into web/ whenever it sees a coding agent.
  agentRules: false,
};

export default config;
