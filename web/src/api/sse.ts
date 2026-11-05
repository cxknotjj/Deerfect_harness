/**
 * SSE 单行解析（纯函数 + 有状态行解析器）：event:/data: 两行成帧，注释与未知字段忽略。
 * 服务端协议每帧自带 event + data（无空行），因此 data 行到达即成一帧并复位 event。
 */

/** 帧：event 为 null 表示该 data 行没有前置 event（按 token 兜底） */
export interface SseFrame {
  event: string | null
  data: string
}

/** 剥掉 "data:" 前缀与其后至多一个空格（SSE 规范），保留载荷原文（缩进空格不丢）；
 *  尾部 \r 去除（服务端若发 \r\n 行尾，split('\n') 后载荷尾残留 \r 会污染 JSON/token） */
export function dataPayload(line: string): string {
  const rest = line.slice('data:'.length)
  const noSpace = rest.startsWith(' ') ? rest.slice(1) : rest
  return noSpace.endsWith('\r') ? noSpace.slice(0, -1) : noSpace
}

/**
 * 还原 SseProtocol.escapeLineBreaks 的转义（接收侧）：
 * 字面量 \n → 换行、\r → 回车、\\ → 反斜杠；非法序列原样保留。
 */
export function unescapeLineBreaks(s: string): string {
  if (!s.includes('\\')) return s
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i)
    if (c === '\\' && i + 1 < s.length) {
      const n = s.charAt(++i)
      if (n === 'n') out += '\n'
      else if (n === 'r') out += '\r'
      else if (n === '\\') out += '\\'
      else out += c + n
    } else {
      out += c
    }
  }
  return out
}

/** 有状态行解析器：event: 行暂存，下一个 data: 行产出帧并复位；空行/注释/未知字段忽略 */
export class SseLineParser {
  private event: string | null = null

  /** @return 成帧返回 { event, data }；非 data 行返回 null */
  parse(line: string): SseFrame | null {
    if (line.trim() === '') return null
    if (line.startsWith('event:')) {
      this.event = line.slice('event:'.length).trim()
      return null
    }
    if (!line.startsWith('data:')) return null
    const data = dataPayload(line)
    const event = this.event
    this.event = null
    return { event, data }
  }
}
