import { useState } from 'react'
import { overnightReturn } from './strategy'

export default function Overnight() {
  const [values,setValues]=useState({ entry:'',exit:'',capital:'10000',fee:'3',slippage:'5' })
  const result=overnightReturn(Number(values.entry),Number(values.exit),Number(values.capital),Number(values.fee),Number(values.slippage))
  return <section className="eco-section" aria-labelledby="overnight-title">
    <h2 id="overnight-title">隔夜买入收益评估</h2>
    <p className="eco-help">仅试算尾盘买入、下一交易日开盘后立即卖出的情景，计算扣除双边费用与滑点后的收益。</p>
    <div className="eco-price-grid">{([['entry','尾盘买入价'],['exit','次日早盘卖出价（假设）'],['capital','投入金额'],['fee','单边费率（基点）'],['slippage','单边滑点（基点）']] as const).map(([key,label]) => <label key={key}>{label}<input type="number" min="0" step="any" value={values[key]} onChange={e => setValues({ ...values,[key]:e.target.value })} /></label>)}</div>
    {result ? <dl className="eco-return"><div><dt>毛收益率</dt><dd>{(result.gross*100).toFixed(2)}%</dd></div><div><dt>净收益率</dt><dd>{(result.net*100).toFixed(2)}%</dd></div><div><dt>情景净收益</dt><dd>{result.profit.toFixed(2)}</dd></div><div><dt>盈亏平衡卖出价</dt><dd>{result.breakEven.toFixed(4)}</dd></div></dl> : <p className="eco-help">请填写有效的正数价格与金额，费用及滑点不能为负数。</p>}
    <p className="eco-help">1 基点 = 0.01%。按可分割仓位估算；最低佣金、印花税、整手限制和汇率需另行计入。此处不预测次日价格。</p>
  </section>
}
