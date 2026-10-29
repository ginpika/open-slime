import { useEffect, useState } from 'react'

export type CollaborationLocation = { projectId: string; taskId: string | null; issueId: string | null }
export function readCollaborationLocation(): CollaborationLocation {
  const params = new URLSearchParams(window.location.hash.split('?')[1] || '')
  return { projectId: params.get('project') || '', taskId: params.get('task'), issueId: params.get('issue') }
}
export function collaborationHref(location: CollaborationLocation) {
  const params = new URLSearchParams()
  if (location.projectId) params.set('project', location.projectId)
  if (location.taskId) params.set('task', location.taskId)
  if (location.issueId && location.taskId) params.set('issue', location.issueId)
  return '/collaboration' + (params.size ? '?' + params.toString() : '')
}
export function useCollaborationNavigation() {
  const [location, setLocation] = useState(readCollaborationLocation)
  useEffect(() => {
    const changed = () => setLocation(readCollaborationLocation())
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  return { location, navigate: (next: CollaborationLocation) => { window.location.hash = collaborationHref(next) } }
}
