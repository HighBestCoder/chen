export function commandModuleForDb(dbType) {
  return dbType === 'mongodb' ? 'raw' : dbType
}

export function commandCommentForDb(dbType) {
  return dbType === 'mongodb' ? 'mongodb' : ''
}

export function snippetMatchesDb(snippet, dbType) {
  const moduleValue = snippet?.module?.value || snippet?.module
  if (moduleValue === dbType) {
    return true
  }
  return dbType === 'mongodb' && moduleValue === 'raw' && snippet?.comment === 'mongodb'
}

export function looksSensitiveCommand(command) {
  if (!command) {
    return false
  }
  return /(mongodb(?:\+srv)?:\/\/|["']?(?:password|passwd|pwd|secret|token|access[_-]?token|client[_-]?secret)["']?\s*[:=]|bearer\s+[a-z0-9._~+\/-]+=*)/i.test(command)
}
