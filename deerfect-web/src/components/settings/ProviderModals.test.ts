import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { LlmModelView, LlmProviderView } from '../../api/admin'
import ProviderConfigModal from './ProviderConfigModal.vue'
import ProviderModelsModal from './ProviderModelsModal.vue'

const PROVIDER: LlmProviderView = {
  id: 1,
  code: 'dashscope',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  credentialRef: 'DASHSCOPE_API_KEY',
  protocol: 'openai',
  enabled: 1,
}

const MODELS: LlmModelView[] = [
  {
    id: 1,
    providerId: 1,
    providerCode: 'dashscope',
    upstreamName: 'qwen-plus',
    displayName: 'qwen-plus',
    capabilities: '{"context_window":131072}',
    defaultParams: null,
    enabled: 1,
  },
  {
    id: 2,
    providerId: 1,
    providerCode: 'dashscope',
    upstreamName: 'qwen-turbo',
    displayName: 'qwen-turbo',
    capabilities: null,
    defaultParams: null,
    enabled: 0,
  },
]

function mountConfig(creating: boolean, provider: LlmProviderView | null) {
  return mount(ProviderConfigModal, {
    props: { open: true, provider, creating, saving: false, error: '' },
  })
}

describe('ProviderConfigModal', () => {
  it('blocks empty required fields before emitting', async () => {
    const wrapper = mountConfig(true, null)
    await wrapper.find('.res-btn-primary').trigger('click')
    expect(wrapper.find('.res-alert').text()).toContain('code 不能为空')
    expect(wrapper.emitted('save')).toBeUndefined()
  })

  it('emits trimmed create payload', async () => {
    const wrapper = mountConfig(true, null)
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue(' moonshot ')
    await inputs[1].setValue(' https://api.moonshot.cn/v1 ')
    await inputs[2].setValue(' MOONSHOT_API_KEY ')
    await wrapper.findAll('select')[0].setValue('anthropic')
    await wrapper.find('.res-btn-primary').trigger('click')

    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({
      providerId: null,
      body: {
        code: 'moonshot',
        baseUrl: 'https://api.moonshot.cn/v1',
        credentialRef: 'MOONSHOT_API_KEY',
        protocol: 'anthropic',
        enabled: 1,
      },
    })
  })

  it('keeps code read-only and prefills fields when editing', () => {
    const wrapper = mountConfig(false, PROVIDER)
    const inputs = wrapper.findAll('input')
    expect((inputs[0].element as HTMLInputElement).disabled).toBe(true)
    expect((inputs[0].element as HTMLInputElement).value).toBe('dashscope')
    expect((inputs[1].element as HTMLInputElement).value).toBe(PROVIDER.baseUrl)
    expect((inputs[2].element as HTMLInputElement).value).toBe(PROVIDER.credentialRef)
  })
})

describe('ProviderModelsModal', () => {
  it('renders model rows with context window and status', () => {
    const wrapper = mount(ProviderModelsModal, {
      props: { open: true, provider: PROVIDER, models: MODELS },
    })
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('qwen-plus')
    expect(wrapper.text()).toContain('128K')
    expect(wrapper.text()).toContain('已停用')
  })

  it('shows an empty state when the provider has no models', () => {
    const wrapper = mount(ProviderModelsModal, {
      props: { open: true, provider: PROVIDER, models: [] },
    })
    expect(wrapper.find('.provider-empty').text()).toBe('该提供商下还没有模型')
  })
})
