import TerminalSession from '../../components/TerminalSession'
import { connectCodingTerminal, type CodingTerminal } from '../../api/codingTerminal'
export default function CodingTerminalSession(props: { project: string; terminal: CodingTerminal; active: boolean; onExit: (id: string, error?: string) => void }) {
  return <TerminalSession scope={props.project} terminal={props.terminal} active={props.active} onExit={props.onExit} connect={connectCodingTerminal} shellName="bash" />
}
