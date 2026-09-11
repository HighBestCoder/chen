export function parseFieldType(t) {
  // if (t.indexOf("char") !== -1) {
  //     return ""
  // }
  if (t.indexOf('int') !== -1) {
    return 'numeric'
  }
  // if (t.indexOf("datetime") !== -1) {
  //     return "time"
  // }
  return 'text'
}

export function getUrlParams(url) {
  const queryStart = url.indexOf('?')
  const fragmentStart = url.indexOf('#')
  if (queryStart < 0 || (fragmentStart >= 0 && fragmentStart < queryStart)) {
    return {}
  }
  return Object.fromEntries(new URLSearchParams(url.slice(queryStart + 1).split('#')[0]))
}
