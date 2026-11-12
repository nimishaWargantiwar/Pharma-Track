import React, { createContext, useContext } from 'react';

export const TAB_ROUTES = {
  products: '#/products',
  recipes: '#/recipes',
  orders: '#/orders',
  execution: '#/execution',
  inventory: '#/inventory',
  quality: '#/quality',
  traceability: '#/traceability',
  users: '#/users',
  system: '#/system',
};

export const DEFAULT_TAB = 'products';

export const ROUTE_ALIASES = {
  '': 'products',
  products: 'products',
  'product-master': 'products',
  recipes: 'recipes',
  'recipe-management': 'recipes',
  orders: 'orders',
  'manufacturing-orders': 'orders',
  execution: 'execution',
  'production-execution': 'execution',
  inventory: 'inventory',
  'raw-materials': 'inventory',
  warehouse: 'inventory',
  warehouses: 'inventory',
  quality: 'quality',
  'quality-management': 'quality',
  'quality-compliance': 'quality',
  traceability: 'traceability',
  'batch-traceability': 'traceability',
  qr: 'traceability',
  users: 'users',
  'user-management': 'users',
  'admin-users': 'users',
  system: 'system',
  'system-overview': 'system',
};

export function buildModuleHash(tab, params = {}) {
  const baseHash = TAB_ROUTES[tab] || TAB_ROUTES[DEFAULT_TAB];
  if (!params || typeof params !== 'object') {
    return baseHash;
  }
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      qs.set(key, String(value).trim());
    }
  });
  const queryString = qs.toString();
  return queryString ? `${baseHash}?${queryString}` : baseHash;
}

export function parseHashRoute() {
  if (typeof window === 'undefined') {
    return { tab: DEFAULT_TAB, params: new URLSearchParams() };
  }

  const rawHashFull = (window.location.hash || '').replace(/^#\/?/, '');
  const qIndex = rawHashFull.indexOf('?');
  const rawHashPath = qIndex >= 0 ? rawHashFull.slice(0, qIndex) : rawHashFull;
  const rawHashQuery = qIndex >= 0 ? rawHashFull.slice(qIndex + 1) : '';

  const normalizedHashPath = rawHashPath.replace(/\/+$/, '').toLowerCase();
  const hashParams = new URLSearchParams(rawHashQuery);
  const searchParams = new URLSearchParams(window.location.search || '');

  const mergedParams = new URLSearchParams(searchParams);
  hashParams.forEach((value, key) => {
    mergedParams.set(key, value);
  });

  if (normalizedHashPath && ROUTE_ALIASES[normalizedHashPath]) {
    return { tab: ROUTE_ALIASES[normalizedHashPath], params: mergedParams };
  }

  const queryTab = (searchParams.get('tab') || searchParams.get('module') || '').trim().toLowerCase();
  if (queryTab && ROUTE_ALIASES[queryTab]) {
    return { tab: ROUTE_ALIASES[queryTab], params: mergedParams };
  }

  const rawPath = (window.location.pathname || '').replace(/^\/+|\/+$/g, '').toLowerCase();
  if (rawPath && ROUTE_ALIASES[rawPath]) {
    return { tab: ROUTE_ALIASES[rawPath], params: mergedParams };
  }

  return { tab: DEFAULT_TAB, params: mergedParams };
}

function defaultHashNavigate(tab, params = {}) {
  const targetTab = TAB_ROUTES[tab] ? tab : DEFAULT_TAB;
  const targetHash = buildModuleHash(targetTab, params);
  if (typeof window !== 'undefined' && window.location) {
    if (window.location.hash !== targetHash) {
      window.location.hash = targetHash;
    }
  }
}

export const NavigationContext = createContext({
  activeTab: DEFAULT_TAB,
  routeParams: new URLSearchParams(),
  routeNonce: 0,
  navigateToTab: defaultHashNavigate,
});

export function NavigationProvider({ value, children }) {
  return (
    <NavigationContext.Provider value={value}>{children}</NavigationContext.Provider>
  );
}

export function useNavigation() {
  return useContext(NavigationContext);
}

