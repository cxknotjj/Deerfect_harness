import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { matchesGlob } from 'node:path'
import { test } from 'node:test'

const root = new URL('../', import.meta.url)
const dockerfile = readFileSync(new URL('docker/Dockerfile', root), 'utf8')
const workflow = readFileSync(new URL('.github/workflows/docker-image.yml', root), 'utf8')
const inputs = [...dockerfile.matchAll(/^COPY (?!--)(\S+) /gm)].map((match) => match[1])
const lines = workflow.split(/\r?\n/)

test('dependency prefetch layer stays ahead of every source copy', () => {
  const lines = dockerfile.split(/\r?\n/)
  const prefetch = lines.findIndex((line) => line.includes('dependency:go-offline'))
  assert.notEqual(prefetch, -1)
  const sources = lines.flatMap((line, index) =>
    line.startsWith('COPY ') && /\b\S+\/src\b/.test(line) ? [index] : [])
  assert.ok(sources.length > 0)
  assert.ok(Math.min(...sources) > prefetch, 'source COPY defeats the dependency cache layer')
  const poms = lines.flatMap((line, index) =>
    line.startsWith('COPY ') && line.split(' ')[1].endsWith('.xml') ? [index] : [])
  assert.ok(Math.max(...poms) < prefetch, 'pom COPYs must precede the dependency prefetch')
})

for (const event of ['push', 'pull_request']) {
  test(`${event} covers every local Docker COPY input`, () => {
    const start = lines.findIndex((line) => line === `  ${event}:`)
    assert.notEqual(start, -1)
    const end = lines.findIndex((line, index) => index > start && /^(?:\S| {2}\S)/.test(line))
    const section = lines.slice(start, end === -1 ? undefined : end)
    const patterns = section.flatMap((line) => {
      const match = line.match(/^ {6}- '([^']+)'$/)
      return match ? [match[1]] : []
    })
    assert.ok(inputs.length > 0)
    assert.ok(patterns.length > 0)
    for (const input of inputs) {
      const changed = input.endsWith('.xml') ? input : `${input}/changed.txt`
      assert.ok(patterns.some((pattern) => matchesGlob(changed, pattern)), `${event} misses ${changed}`)
    }
    for (const unrelated of ['README.md', '.env.local', 'deerfect-web/src/App.vue']) {
      assert.ok(!patterns.some((pattern) => matchesGlob(unrelated, pattern)), `${event} includes ${unrelated}`)
    }
  })
}
