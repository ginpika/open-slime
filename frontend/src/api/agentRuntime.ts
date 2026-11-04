import type { MaPeer } from './client'

/** Retired identity, accepted only to resolve old links and cached rosters. */
export const CODING_AGENT_ID = 'builtin-coding'
export const CORE_AGENT_ID = 'builtin'
export function isCodingAgent(peer: Pick<MaPeer, 'id' | 'executionKind'>) {
  return peer.executionKind === 'CODING' || peer.id === CODING_AGENT_ID
}
export const agentProfileHref = (id = CORE_AGENT_ID) => `#/multi-agent/${encodeURIComponent(id === CODING_AGENT_ID ? CORE_AGENT_ID : id)}`

/** 成员页只展示合并后的身份，固定默认 Agent 的顺序，其余成员沿用 sort；同值保留接口顺序。 */
export function sortAgentPeers(peers: MaPeer[]): MaPeer[] {
  const rank = (peer: MaPeer) => peer.id === CORE_AGENT_ID ? 0 : peer.builtin ? 1 : 2
  return peers.filter(peer => !isCodingAgent(peer)).toSorted((a, b) => rank(a) - rank(b) || a.sort - b.sort)
}
