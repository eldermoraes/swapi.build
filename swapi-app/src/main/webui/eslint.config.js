import eslint from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  // Build output and the Quinoa cache. .prettierignore skips the same two.
  {
    ignores: ['dist/**', '.quinoa/**'],
  },
  {
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
  // The Playwright scripts are CommonJS run by Node, and the callbacks they hand
  // to `page.evaluate` run in the page: both sets of globals are real there.
  {
    files: ['scripts/**/*.cjs'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { ...globals.node, ...globals.browser },
    },
    rules: {
      '@typescript-eslint/no-require-imports': 'off',
    },
  },
);
