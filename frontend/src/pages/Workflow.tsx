import Roundtable from './Roundtable'

/** 保留模块兼容入口；执行态已成为协作工作台中的图视图。 */
export default function WorkflowPage() {
  return <Roundtable initialView="graph" />
}
