import { apiRequest } from './apiClient.js';

export async function getInventorySummary() {
  return apiRequest('/inventory/summary');
}

export async function getRecipeMaterialReferences() {
  return apiRequest('/inventory/recipe-material-references');
}

export async function getAllRawMaterials() {
  return apiRequest('/inventory/materials');
}

export async function getRawMaterialById(id) {
  return apiRequest(`/inventory/materials/${encodeURIComponent(id)}`);
}

export async function createRawMaterial(payload) {
  return apiRequest('/inventory/materials', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateRawMaterial(id, payload) {
  return apiRequest(`/inventory/materials/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateRawMaterialStatus(id, active) {
  return apiRequest(`/inventory/materials/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active }),
  });
}

export async function getAllWarehouses() {
  return apiRequest('/inventory/warehouses');
}

export async function createWarehouse(payload) {
  return apiRequest('/inventory/warehouses', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateWarehouse(id, payload) {
  return apiRequest(`/inventory/warehouses/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateWarehouseStatus(id, active) {
  return apiRequest(`/inventory/warehouses/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active }),
  });
}

export async function getAllLocations(warehouseId = null) {
  const path =
    warehouseId != null && warehouseId !== ''
      ? `/inventory/locations?warehouseId=${encodeURIComponent(warehouseId)}`
      : '/inventory/locations';
  return apiRequest(path);
}

export async function createStorageLocation(warehouseId, payload) {
  return apiRequest(`/inventory/warehouses/${encodeURIComponent(warehouseId)}/locations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateStorageLocation(id, payload) {
  return apiRequest(`/inventory/locations/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function updateStorageLocationStatus(id, active) {
  return apiRequest(`/inventory/locations/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active }),
  });
}

export async function getAllLots({ materialId = null, warehouseId = null } = {}) {
  const params = new URLSearchParams();
  if (materialId != null && materialId !== '') params.set('materialId', String(materialId));
  if (warehouseId != null && warehouseId !== '') params.set('warehouseId', String(warehouseId));
  const qs = params.toString();
  return apiRequest(qs ? `/inventory/lots?${qs}` : '/inventory/lots');
}

export async function getLotById(id) {
  return apiRequest(`/inventory/lots/${encodeURIComponent(id)}`);
}

export async function getAllMovements({ materialId = null, orderId = null } = {}) {
  const params = new URLSearchParams();
  if (materialId != null && materialId !== '') params.set('materialId', String(materialId));
  if (orderId != null && orderId !== '') params.set('orderId', String(orderId));
  const qs = params.toString();
  return apiRequest(qs ? `/inventory/movements?${qs}` : '/inventory/movements');
}

export async function getOrderMaterialRequirements(orderId) {
  return apiRequest(`/inventory/orders/${encodeURIComponent(orderId)}/requirements`);
}

export async function receiveGoods(payload) {
  return apiRequest('/inventory/receipts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function issueMaterial(payload) {
  return apiRequest('/inventory/issues', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function adjustStock(payload) {
  return apiRequest('/inventory/adjustments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

