import { describe, expect, it } from 'vitest'
import { SseLineParser, dataPayload, unescapeLineBreaks } from './sse'

describe('SseLineParser', () => {
  it('event + data 两行成帧，data 后 event 复位', () => {
    const parser = new SseLineParser()
    expect(parser.parse('event: meta')).toBeNull()
    expect(parser.parse('data: {"sessionId":"s1"}')).toEqual({
      event: 'meta',
      data: '{"sessionId":"s1"}',
    })
    expect(parser.parse('data: next')).toEqual({ event: null, data: 'next' })
  })

  it('无 event 的 data 按 token 兜底', () => {
    const parser = new SseLineParser()
    expect(parser.parse('data: hello')).toEqual({ event: null, data: 'hello' })
  })

  it('注释与未知字段忽略，且不吞掉后续 event', () => {
    const parser = new SseLineParser()
    expect(parser.parse(': keep-alive')).toBeNull()
    expect(parser.parse('id: 1')).toBeNull()
    expect(parser.parse('event: token')).toBeNull()
    expect(parser.parse('data: hi')).toEqual({ event: 'token', data: 'hi' })
  })

  it('多行 data 各自成帧', () => {
    const parser = new SseLineParser()
    const first = parser.parse('data: a')
    const second = parser.parse('data: b')
    expect(first).toEqual({ event: null, data: 'a' })
    expect(second).toEqual({ event: null, data: 'b' })
  })
})

describe('SSE 载荷解析', () => {
  it('dataPayload 只剥一个空格并去掉尾部 \\r', () => {
    expect(dataPayload('data: hello')).toBe('hello')
    expect(dataPayload('data:  hello')).toBe(' hello')
    expect(dataPayload('data:hello\r')).toBe('hello')
    expect(dataPayload('data: \r')).toBe('')
  })

  it('unescapeLineBreaks 还原换行/回车/反斜杠，非法序列原样保留', () => {
    expect(unescapeLineBreaks('a\\nb')).toBe('a\nb')
    expect(unescapeLineBreaks('a\\rb')).toBe('a\rb')
    expect(unescapeLineBreaks('a\\\\b')).toBe('a\\b')
    expect(unescapeLineBreaks('a\\xb')).toBe('a\\xb')
    expect(unescapeLineBreaks('plain')).toBe('plain')
  })
})
