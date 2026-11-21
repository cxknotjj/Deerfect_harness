import { describe, expect, it } from 'vitest'
import { withBase } from './brand'

describe('withBase', () => {
  it('prefixes deployed subpaths', () => {
    expect(withBase('/Deerfect_harness/', 'deerfect-mark.svg')).toBe('/Deerfect_harness/deerfect-mark.svg')
    expect(withBase('/Deerfect_harness', '/deerfect-mark.svg')).toBe('/Deerfect_harness/deerfect-mark.svg')
  })

  it('keeps root deployments at the domain root', () => {
    expect(withBase('/', 'deerfect-mark.svg')).toBe('/deerfect-mark.svg')
    expect(withBase('', 'deerfect-mark.svg')).toBe('/deerfect-mark.svg')
    expect(withBase('./', 'deerfect-mark.svg')).toBe('./deerfect-mark.svg')
  })
})
