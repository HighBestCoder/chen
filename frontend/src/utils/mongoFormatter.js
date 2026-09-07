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
      // 只有在紧跟 ':' 时才吃掉尾部空白（把 'a:' 补成 'a: {'）。
      // 早先这里无条件 trimRight()，会把逗号换行后刚写好的缩进一起清掉，
      // 于是 '},\n{' 里的 '{' 顶到行首，聚合流水线看起来没有缩进。
      const trimmed = formatted.replace(/[ \t]+$/g, '')
      if (trimmed.endsWith(':')) {
        formatted = trimmed + ' '
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
