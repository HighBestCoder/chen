export function formatMongoCommand(command) {
  let formatted = ''
  let indent = 0
  let inString = false
  let stringQuote = ''
  let escaped = false

  const indentation = () => '  '.repeat(Math.max(indent, 0))
  const trimRight = () => {
    formatted = formatted.replace(/[ \t]+$/g, '')
  }

  for (const char of command) {
    if (inString) {
      formatted += char
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === stringQuote) {
        inString = false
        stringQuote = ''
      }
      continue
    }

    if (char === '"' || char === "'") {
      inString = true
      stringQuote = char
      formatted += char
      continue
    }

    if (char === '{' || char === '[') {
      trimRight()
      if (formatted.endsWith(':')) {
        formatted += ' '
      }
      formatted += char + '\n'
      indent++
      formatted += indentation()
      continue
    }

    if (char === '}' || char === ']') {
      trimRight()
      formatted += '\n'
      indent--
      formatted += indentation() + char
      continue
    }

    if (char === ',') {
      trimRight()
      formatted += char + '\n' + indentation()
      continue
    }

    if (char === ':') {
      trimRight()
      formatted += ': '
      continue
    }

    formatted += char
  }
  return formatted.trim()
}
