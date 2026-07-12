import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import { defineConfig, globalIgnores } from 'eslint/config';

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{js,jsx}'],
    extends: [
      js.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      semi: ['error', 'always'],
      'no-var': 'error',
      'prefer-const': 'error',
      // 대문자 시작 식별자(React, lucide 아이콘 등)와 미사용 catch 파라미터는 현행 관행으로 허용
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]', caughtErrors: 'none' }],
      'react-hooks/set-state-in-effect': 'warn',
    },
  },
  {
    // 컴포넌트에서 fetch 직접 호출 금지 — 앱 데이터는 db_service.js 경유
    files: ['src/App.jsx', 'src/components/**/*.jsx'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: '컴포넌트에서 fetch를 직접 호출하지 않는다 — db_service.js의 export를 사용할 것' },
      ],
    },
  },
]);
