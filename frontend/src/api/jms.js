import { get, post } from '@/request'

export async function getSnippets() {
  const rows = []
  let offset = 0
  for (;;) {
    const data = await get('/api/v1/ops/adhocs/', { limit: 100, offset })
    if (Array.isArray(data)) return data
    if (!data || !Array.isArray(data.results)) throw new Error('Invalid command library response')
    rows.push(...data.results)
    if (!data.next) return rows
    if (!data.results.length) throw new Error('Command library pagination made no progress')
    offset += data.results.length
  }
}

export function saveSnippet(item) {
  return post(`/api/v1/ops/adhocs/`, item)
}
