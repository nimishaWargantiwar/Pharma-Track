import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import esbuild from 'esbuild';

export async function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith('.css')) {
    return {
      shortCircuit: true,
      url: new URL(specifier, context.parentURL).href,
    };
  }

  try {
    return await nextResolve(specifier, context);
  } catch (err) {
    if (
      err.code === 'ERR_MODULE_NOT_FOUND' &&
      (specifier.startsWith('./') || specifier.startsWith('../')) &&
      context.parentURL &&
      context.parentURL.startsWith('file:')
    ) {
      const candidates = [
        `${specifier}.jsx`,
        `${specifier}.js`,
        `${specifier}/index.jsx`,
        `${specifier}/index.js`,
      ];
      for (const candidate of candidates) {
        const resolvedUrl = new URL(candidate, context.parentURL);
        if (fs.existsSync(fileURLToPath(resolvedUrl))) {
          return nextResolve(candidate, context);
        }
      }
    }
    throw err;
  }
}

export async function load(url, context, nextLoad) {
  if (url.endsWith('.css')) {
    return {
      format: 'module',
      shortCircuit: true,
      source: 'export default {};',
    };
  }

  if (url.endsWith('.jsx')) {
    const filePath = fileURLToPath(new URL(url));
    const source = fs.readFileSync(filePath, 'utf8');
    const transformed = esbuild.transformSync(source, {
      loader: 'jsx',
      format: 'esm',
      jsx: 'automatic',
      sourcefile: filePath,
    });
    return {
      format: 'module',
      shortCircuit: true,
      source: transformed.code,
    };
  }

  return nextLoad(url, context);
}

