import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import globals from 'globals'

// 扁平配置（ESLint 9+）：JS 推荐集 + TS 推荐集 + Vue 推荐集。
// 规则取向：只拦「会出错或会腐化」的问题，不做格式整改——格式由编辑器与
// vue-tsc 兜底，把 lint 变成格式警察会让每次改动都产生无关 diff。
export default tseslint.config(
  {
    // 产物与外部目录不参与检查
    ignores: [
      'dist/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
      'public/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    // Vue SFC 的 <script lang="ts"> 交给 TS parser；<template> 由 vue-eslint-parser 处理
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      // 组件名：App / MonitorView 这类命名已足够表意，不强制多词
      'vue/multi-word-component-names': 'off',
      // 以下为排版类规则，与现有写法冲突且无正确性收益，关闭
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-closing-bracket-spacing': 'off',
      'vue/attributes-order': 'off',
      'vue/first-attribute-linebreak': 'off',
      // any 在 mock / 第三方回调适配处偶有必要，交给 review 判断
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
