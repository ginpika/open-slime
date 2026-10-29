import type { MaPeer } from './client'

export const CODING_AGENT_ID = 'builtin-coding'
export const CORE_AGENT_ID = 'builtin'
export function isCodingAgent(peer: Pick<MaPeer, 'id' | 'executionKind'>) {
  return peer.executionKind === 'CODING' || peer.id === CODING_AGENT_ID
}
export const agentProfileHref = (id = CODING_AGENT_ID) => `#/multi-agent/${encodeURIComponent(id)}`

/** 成员页固定内置身份的顺序，其余成员沿用 sort；同值保留接口顺序。 */
export function sortAgentPeers(peers: MaPeer[]): MaPeer[] {
  const rank = (peer: MaPeer) => peer.id === CORE_AGENT_ID ? 0 : peer.id === CODING_AGENT_ID ? 1 : peer.builtin ? 2 : 3
  return peers.toSorted((a, b) => rank(a) - rank(b) || a.sort - b.sort)
}
