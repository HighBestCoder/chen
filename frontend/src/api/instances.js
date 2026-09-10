// Use the signed-in Core user, never the Chen service account or Chen session token.
export function coreContext(cookie = document.cookie, search = window.location.search) {
  const cookies = Object.fromEntries(cookie.split(';').filter(x => x.includes('=')).map(x => {
    const at = x.indexOf('=')
    return [x.slice(0, at).trim(), decodeURIComponent(x.slice(at + 1))]
  }))
  const prefix = cookies.SESSION_COOKIE_NAME_PREFIX
  return {
    org: new URLSearchParams(search).get('oid') || cookies['X-JMS-ORG'],
    csrf: cookies[`${!prefix || prefix === '""' || prefix === "''" ? '' : prefix}csrftoken`]
  }
}

export async function coreRequest(path, context, body) {
  if (!context.org) throw new Error('instance_org_required')
  if (body && !context.csrf) throw new Error('instance_login_required')
  const response = await fetch(path, {
    method: body ? 'POST' : 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'X-JMS-ORG': context.org,
      ...(body ? { 'Content-Type': 'application/json', 'X-CSRFToken': context.csrf } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  if (!response.ok) {
    // Do not render raw server responses (which can contain credentials).
    throw new Error(response.status === 401 ? 'instance_login_required' : response.status === 403 ? 'instance_denied' : 'instance_failed')
  }
  return response.json()
}

export async function getInstances(context) {
  const rows = []
  let offset = 0
  for (;;) {
    const page = await coreRequest(`/api/v1/perms/users/self/assets/?type=mongodb&limit=100&offset=${offset}`, context)
    const data = Array.isArray(page) ? page : page && page.results
    if (!Array.isArray(data)) throw new Error('instance_failed')
    rows.push(...data.filter(a => a.is_active !== false && (a.type?.value || a.type) === 'mongodb'))
    if (Array.isArray(page) || !page.next) return rows
    if (!data.length) throw new Error('instance_failed')
    offset += data.length
  }
}

export async function getInstanceAccounts(id, context) {
  const data = await coreRequest(`/api/v1/perms/users/self/assets/${encodeURIComponent(id)}/`, context)
  if (!data.permed_protocols?.some(p => p.name === 'mongodb')) return []
  // Entra accounts can generate their secret on the server; has_secret only reflects stored passwords.
  return (data.permed_accounts || []).filter(a => a.name && a.name !== '@INPUT' && a.username !== '@INPUT')
}

export async function createInstanceSession(asset, account, context) {
  const data = await coreRequest('/api/v1/authentication/connection-token/', context, {
    asset, account, protocol: 'mongodb', connect_method: 'web_gui'
  })
  if (!data.id || data.is_active === false) throw new Error('instance_denied')
  // A fragment is never sent in HTTP requests / Referer; Controller consumes it once.
  return `/chen/?oid=${encodeURIComponent(context.org)}#${new URLSearchParams({ connectionToken: data.id })}`
}

export function consumeInstanceToken(location = window.location, history = window.history) {
  const fragment = new URLSearchParams(location.hash.slice(1))
  const token = fragment.get('connectionToken')
  if (token) history.replaceState(null, '', location.pathname + location.search)
  return token
}
