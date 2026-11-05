import { createClient } from '@supabase/supabase-js'

// Только publishable (anon) ключ. Доступ к данным ограничен RLS-политиками (supabase/schema.sql).
const url = (import.meta.env.VITE_SUPABASE_URL || '').trim()
const key = (import.meta.env.VITE_SUPABASE_KEY || '').trim()

function isValidUrl(value) {
  try {
    const u = new URL(value)
    return u.protocol === 'https:' || u.protocol === 'http:'
  } catch {
    return false
  }
}

export const isConfigured = Boolean(url && key && isValidUrl(url))

export const supabase = isConfigured
  ? createClient(url, key, {
      auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false },
    })
  : null
