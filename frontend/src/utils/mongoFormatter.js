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

  for (let index = 0; index < command.length; index++) {
    const char = command[index]
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

    if (char === '/') {
      const start = index
      const lineComment = command[index + 1] === '/'
      const blockComment = command[index + 1] === '*'
      let escapedSlash = false
      let inClass = false
      index++
      for (; index < command.length; index++) {
        const current = command[index]
        if (lineComment && current === '\n') break
        if (blockComment && current === '*' && command[index + 1] === '/') { index++; break }
        if (lineComment || blockComment) continue
        if (escapedSlash) { escapedSlash = false; continue }
        if (current === '\\') { escapedSlash = true; continue }
        if (current === '[') inClass = true
        if (current === ']') inClass = false
        if (current === '/' && !inClass) break
      }
      formatted += command.slice(start, index + 1)
      continue
    }
    if (/\s/.test(char)) {
      if (formatted && !/\s$/.test(formatted)) formatted += ' '
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
