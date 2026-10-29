import { useState, type FormEvent } from 'react'
import type { MaPeer } from '../../api/client'
import { collaborationError } from '../../api/collaboration'
import FloatWindow from '../../components/FloatWindow'
import PeerAvatar from '../../components/PeerAvatar'
import { Select } from '../../components/formKit'

/** Shared form for naming work and explicitly selecting its participants. */
export default function CollaborationDialog({ title, label, initialName = '', initialNote, peers, initialMembers = [], nameReadOnly = false, chooseCoordinator = false, submitLabel = '保存', onSubmit, onClose }: {
  title: string; label: string; initialName?: string; initialNote?: string; peers?: MaPeer[]; initialMembers?: string[]
  nameReadOnly?: boolean; chooseCoordinator?: boolean; submitLabel?: string; onSubmit: (name: string, note: string, members: string[], coordinator?: string) => Promise<void>; onClose: () => void
}) {
  const [name, setName] = useState(initialName), [note, setNote] = useState(initialNote || '')
  const [members, setMembers] = useState(initialMembers), [saving, setSaving] = useState(false), [error, setError] = useState('')
  const [selectedCoordinator, setCoordinator] = useState(initialMembers[0] || '')
  const coordinator = members.includes(selectedCoordinator) ? selectedCoordinator : members[0] || ''
  const missingMembers = !!peers && members.length < (chooseCoordinator ? 2 : 1)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (saving || !name.trim() || missingMembers) return
    setSaving(true); setError('')
    try { await onSubmit(name.trim(), note.trim(), members, chooseCoordinator ? coordinator : undefined); onClose() }
    catch (e) { setError(collaborationError(e)) }
    finally { setSaving(false) }
  }
  return <FloatWindow title={title} width={560} onClose={() => { if (!saving) onClose() }}><form className="collab-form" onSubmit={submit}>
    <label>{label}<input autoFocus={!nameReadOnly} readOnly={nameReadOnly} required maxLength={initialNote !== undefined ? 120 : 160} value={name} disabled={saving} onChange={e => setName(e.target.value)} /></label>
    {initialNote !== undefined && <label>工作方向<textarea rows={4} maxLength={2000} value={note} disabled={saving} onChange={e => setNote(e.target.value)} placeholder="希望推进什么、为什么做，以及你在意的边界…" /></label>}
    {peers && <fieldset disabled={saving}><legend>参与成员 · {members.length}</legend><div className="collab-member-options">{peers.map(p => <label key={p.id}><input type="checkbox" checked={members.includes(p.id)} onChange={() => setMembers(current => current.includes(p.id) ? current.filter(id => id !== p.id) : [...current, p.id])} /><PeerAvatar name={p.name} avatar={p.avatar} size="h-8 w-8" /><span>{p.name}<small>{p.role || '成员'}</small></span></label>)}</div>{!members.length && <p>请选择至少一位成员。</p>}</fieldset>}
    {chooseCoordinator && <><label>主控成员<Select label="主控成员" value={coordinator} disabled={saving} onChange={setCoordinator} options={(peers || []).filter(p => members.includes(p.id)).map(p => ({ value: p.id, label: p.name }))} /></label><p>主控管理计划与交付，执行后由另一位成员独立检查。请至少选择两位成员。</p></>}
    {error && <p role="alert">{error}</p>}<div className="collab-form-actions"><button type="button" className="ui-btn ui-btn--quiet" disabled={saving} onClick={onClose}>取消</button><button type="submit" className="ui-btn ui-btn--primary" disabled={saving || !name.trim() || missingMembers}>{saving ? '正在保存…' : submitLabel}</button></div>
  </form></FloatWindow>
}
