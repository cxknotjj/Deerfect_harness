import { beforeEach, describe, expect, it } from 'vitest'
import {
  activeWorkspaceId,
  activeWorkspaceName,
  assignSessionToWorkspace,
  createWorkspace,
  readWorkspaces,
  removeWorkspace,
  setActiveWorkspace,
  updateWorkspace,
  workspaceOfSession,
} from './workspaces'

beforeEach(() => {
  localStorage.clear()
})

describe('本机工作区登记', () => {
  it('首次读取写入种子，当前工作区回落到第一个未归档项', () => {
    const list = readWorkspaces()
    expect(list.length).toBeGreaterThan(0)
    expect(activeWorkspaceId()).toBe(list[0].id)
    expect(activeWorkspaceName()).toBe(list[0].name)
  })

  it('打开工作区后当前 id 与名称跟随', () => {
    const list = readWorkspaces()
    const target = list[list.length - 1]
    setActiveWorkspace(target.id)
    expect(activeWorkspaceId()).toBe(target.id)
    expect(activeWorkspaceName()).toBe(target.name)
  })

  it('新会话归入当前工作区，删除该工作区后回落到默认', () => {
    const created = createWorkspace('实验区', 'D:/lab')
    setActiveWorkspace(created.id)
    assignSessionToWorkspace('s-1')
    expect(workspaceOfSession('s-1')).toBe(created.id)

    removeWorkspace(created.id)
    expect(workspaceOfSession('s-1')).toBe('default')
  })

  it('归档当前工作区后当前指针回落到未归档项', () => {
    const created = createWorkspace('临时区', 'D:/tmp')
    setActiveWorkspace(created.id)
    updateWorkspace(created.id, { archived: true })
    expect(activeWorkspaceId()).not.toBe(created.id)
    expect(readWorkspaces().find((w) => w.id === created.id)?.archived).toBe(true)
  })
})
