import { useEffect } from 'react'

export default function Toast({ message, onClose }) {
  useEffect(() => {
    if (!message) return
    const t = setTimeout(onClose, 5000)
    return () => clearTimeout(t)
  }, [message, onClose])

  if (!message) return null
  return (
    <div className="toast" role="alert">
      <span>{message}</span>
      <button type="button" className="icon" aria-label="Закрыть" onClick={onClose}>×</button>
    </div>
  )
}
