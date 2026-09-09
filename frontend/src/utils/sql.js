export function compileSQL(template, params) {
  return template.replace(/:(\w+)/g, (match, name) => {
    return params[name] === undefined ? '' : String(params[name])
  })
}
