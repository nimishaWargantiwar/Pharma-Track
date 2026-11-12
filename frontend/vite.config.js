import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { validateProductionApiBaseUrl } from './src/utils/apiUrlConfig.js';

// https://vitejs.dev/config/
export default defineConfig(({ command, mode }) => {
  const fileEnv = loadEnv(mode, process.cwd(), '');
  const rawApiBaseUrl = process.env.VITE_API_BASE_URL ?? fileEnv.VITE_API_BASE_URL ?? '';
  const isProductionBundle = command === 'build' && mode !== 'development' && mode !== 'test';

  const validatedProdApiBaseUrl = isProductionBundle
    ? validateProductionApiBaseUrl(rawApiBaseUrl)
    : null;

  return {
    plugins: [react()],
    define: {
      __PHARMATRACK_PROD_API_URL__: JSON.stringify(validatedProdApiBaseUrl),
    },
    server: {
      port: 5173,
      strictPort: true,
      host: true,
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            const normalized = id.replace(/\\/g, '/');
            if (normalized.includes('/node_modules/')) {
              if (
                normalized.includes('/node_modules/react/') ||
                normalized.includes('/node_modules/react-dom/') ||
                normalized.includes('/node_modules/scheduler/')
              ) {
                return 'vendor-react';
              }
              if (normalized.includes('/node_modules/lucide-react/')) {
                return 'vendor-icons';
              }
              return undefined;
            }

            if (
              normalized.includes('/src/components/QualityManagement.jsx') ||
              normalized.includes('/src/components/QualityBatchDossier.jsx') ||
              normalized.includes('/src/components/QualityCheckModal.jsx') ||
              normalized.includes('/src/components/QaDispositionModal.jsx') ||
              normalized.includes('/src/components/DeviationManagement.jsx') ||
              normalized.includes('/src/components/DeviationWorkspace.jsx') ||
              normalized.includes('/src/components/DeviationBadges.jsx') ||
              normalized.includes('/src/components/ReportDeviationModal.jsx')
            ) {
              return 'module-quality';
            }

            if (
              normalized.includes('/src/components/OrderManagement.jsx') ||
              normalized.includes('/src/components/OrderTable.jsx') ||
              normalized.includes('/src/components/OrderModal.jsx') ||
              normalized.includes('/src/components/OrderDetailsModal.jsx') ||
              normalized.includes('/src/components/BatchYieldModal.jsx') ||
              normalized.includes('/src/components/ProductionExecution.jsx') ||
              normalized.includes('/src/components/ExecutionWorkspace.jsx') ||
              normalized.includes('/src/components/ExecutionHistoryModal.jsx') ||
              normalized.includes('/src/components/CompleteExecutionStepModal.jsx')
            ) {
              return 'module-manufacturing';
            }

            if (
              normalized.includes('/src/components/ProductManagement.jsx') ||
              normalized.includes('/src/components/ProductTable.jsx') ||
              normalized.includes('/src/components/ProductModal.jsx') ||
              normalized.includes('/src/components/RecipeManagement.jsx') ||
              normalized.includes('/src/components/RecipeTable.jsx') ||
              normalized.includes('/src/components/RecipeModal.jsx') ||
              normalized.includes('/src/components/RecipeDetailDrawer.jsx')
            ) {
              return 'module-master-data';
            }

            return undefined;
          },
        },
      },
    },
  };
});
