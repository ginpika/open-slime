import { useEffect, useState } from 'react'
import { observationImage } from '../../api/economy'
import { webError } from '../../api/webSnapshots'
import ImagePreview from '../../components/ImagePreview'

export default function ObservationImage({ id, label }: { id:string;label:string }) {
  const [image,setImage]=useState('')
  const [error,setError]=useState('')
  useEffect(()=>{
    let stopped=false
    setImage('');setError('')
    void observationImage(id).then(result=>{if(!stopped)setImage(result.image)}).catch(e=>{if(!stopped)setError(webError(e))})
    return ()=>{stopped=true}
  },[id])
  if(error)return <p role="alert" className="eco-error">截图读取失败：{error}</p>
  return image?<ImagePreview src={image} alt={label} className="eco-saved-image"/>:<p role="status" className="eco-help">正在读取已保存截图…</p>
}
