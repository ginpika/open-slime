import type { ReactNode } from 'react'
import { usePageKit } from '../../kit'
import { Select, type SelectProps } from '../../components/Select'
import { Button, type ButtonProps } from '../../components/ui'

export function MemorySelect({ label, hint, className, ...props }: SelectProps & { label: string; hint?: string; className?: string }) {
  const kit = usePageKit()
  return <kit.Field label={label} hint={hint} wrap={false} className={className}><Select size="sm" label={label} {...props} /></kit.Field>
}
export function MemoryField({ label, hint, children, className }: { label: string; hint?: string; children: ReactNode; className?: string }) {
  const kit = usePageKit()
  return <kit.Field label={label} hint={hint} className={className}>{children}</kit.Field>
}
export function MemoryButton(props: ButtonProps) {
  return <Button size="sm" {...props} />
}
