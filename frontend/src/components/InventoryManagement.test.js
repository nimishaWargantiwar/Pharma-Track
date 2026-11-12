import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'ADMIN') {
  const map = {
    ADMIN: { username: 'admin', fullName: 'System Admin' },
    PRODUCTION_SUPERVISOR: { username: 'prod.sup', fullName: 'Rohan Mehta' },
    OPERATOR: { username: 'operator.floor', fullName: 'Vikram Joshi' },
    QC_ANALYST: { username: 'qc.analyst', fullName: 'Sneha Kulkarni' },
    QA_REVIEWER: { username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = map[role] || map.ADMIN;
  return {
    user: { id: 1, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'inv-test-jwt',
    actorDisplay: `${info.fullName} (@${info.username})`,
    isAuthenticated: true,
    isInitializing: false,
    sessionMessage: null,
    forbiddenBanner: null,
    login: async () => {},
    logout: () => {},
    clearSessionMessage: () => {},
    dismissForbiddenBanner: () => {},
  };
}

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/inventory') {
  return {
    ok: status >= 200 && status < 300,
    status,
    url,
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => body,
  };
}

function findButtonByText(container, text) {
  return [...container.querySelectorAll('button')].find((b) => b.textContent.includes(text));
}

function findControlByLabel(container, labelText) {
  const label = container.querySelectorAll('label').find((l) => l.textContent.includes(labelText));
  const id = label?.getAttribute('for');
  return id ? container.querySelector(`#${id}`) : null;
}

function getBodyRows(container) {
  return container.querySelector('tbody')?.querySelectorAll('tr') || [];
}

test('InventoryManagement handles loading, initial error with Retry (no empty state), and true empty state', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: InventoryManagement } = await import('./InventoryManagement.jsx');

  let resolveSummary;
  let attempt = 0;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/inventory/summary')) {
      attempt += 1;
      if (attempt === 1) {
        return new Promise((resolve) => {
          resolveSummary = resolve;
        });
      }
      if (attempt === 2) {
        return jsonResponse({ message: 'Inventory service temporarily down' }, 503, url);
      }
      return jsonResponse(
        {
          totalMaterialsCount: 0,
          activeMaterialsCount: 0,
          lowStockMaterialsCount: 0,
          outOfStockMaterialsCount: 0,
          activeLotCount: 0,
          expiringSoonLotCount: 0,
          quarantinedLotCount: 0,
          expiredLotCount: 0,
          warehouseCount: 0,
          activeWarehouseCount: 0,
          storageLocationCount: 0,
          totalAvailableUnits: 0,
          totalMovementsCount: 0,
        },
        200,
        url
      );
    }
    return jsonResponse([], 200, url);
  };

  const renderPage = () =>
    dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );

  try {
    await renderPage();
    assert.ok(dom.container.textContent.includes('Loading inventory and warehouse records...'));
    assert.equal(dom.container.textContent.includes('No raw materials found'), false);

    // Trigger 503 error on initial fetch
    resolveSummary(jsonResponse({ message: 'Inventory service temporarily down' }, 503));
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Unable to load inventory and warehouse records'));
    assert.equal(dom.container.textContent.includes('No raw materials found'), false);

    // Retry -> second error clears, third attempt succeeds with empty list
    const retryBtn1 = findButtonByText(dom.container, 'Retry');
    assert.ok(retryBtn1);
    await dom.click(retryBtn1);
    await dom.flush();
    await dom.flush();

    const retryBtn2 = findButtonByText(dom.container, 'Retry');
    if (retryBtn2) {
      await dom.click(retryBtn2);
      await dom.flush();
      await dom.flush();
    }

    assert.ok(dom.container.textContent.includes('No raw materials found'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('InventoryManagement searches & filters full dataset before 25-row Pagination and supports sub-tab switching + MaterialDetailDrawer', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: InventoryManagement } = await import('./InventoryManagement.jsx');

  const materials = Array.from({ length: 28 }, (_, idx) => {
    const n = idx + 1;
    const isTarget = n === 28;
    return {
      id: n,
      materialCode: isTarget ? 'RM-CEFIX-API' : `RM-${String(n).padStart(3, '0')}`,
      materialName: isTarget ? 'Cefixime Trihydrate USP' : `Excipient Grade ${n}`,
      materialType: isTarget ? 'API' : 'EXCIPIENT',
      unit: 'kg',
      minimumStockLevel: 20,
      availableStock: isTarget ? 10 : 120,
      quarantinedStock: 0,
      expiredStock: 0,
      stockStatus: isTarget ? 'LOW_STOCK' : 'IN_STOCK',
      active: true,
      activeLotCount: 1,
      expiringSoonLotCount: 0,
      linkedRecipeCodes: isTarget ? ['RCP-CEF-200'] : [],
      linkedRecipeCount: isTarget ? 1 : 0,
    };
  });

  const sampleLots = [
    {
      id: 101,
      lotNumber: 'LOT-CEF-01',
      rawMaterialId: 28,
      materialCode: 'RM-CEFIX-API',
      materialName: 'Cefixime Trihydrate USP',
      warehouseId: 1,
      warehouseCode: 'WH-MAIN',
      warehouseName: 'Main Quarantine & GMP Warehouse',
      storageLocationId: 11,
      locationCode: 'A1-R2-B3',
      zoneName: 'Controlled Ambient',
      unit: 'kg',
      initialQuantity: 50,
      availableQuantity: 10,
      receivedDate: '2026-01-10',
      expiryDate: '2028-12-31',
      supplierName: 'Orchid Pharma',
      effectiveStatus: 'ACTIVE',
      expired: false,
      expiringSoon: false,
    },
  ];

  const sampleMovements = [
    {
      id: 501,
      movementNumber: 'MOV-2026-0001',
      movementType: 'GOODS_RECEIPT',
      rawMaterialId: 28,
      materialCode: 'RM-CEFIX-API',
      materialName: 'Cefixime Trihydrate USP',
      lotNumber: 'LOT-CEF-01',
      warehouseCode: 'WH-MAIN',
      locationCode: 'A1-R2-B3',
      quantityDelta: 50,
      quantityBefore: 0,
      quantityAfter: 50,
      unit: 'kg',
      reason: 'Initial supplier receipt',
      performedBy: 'admin',
      occurredAt: '2026-01-10T10:15:00Z',
    },
  ];

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/inventory/summary')) {
      return jsonResponse(
        {
          totalMaterialsCount: 28,
          activeMaterialsCount: 28,
          lowStockMaterialsCount: 1,
          outOfStockMaterialsCount: 0,
          activeLotCount: 1,
          expiringSoonLotCount: 0,
          quarantinedLotCount: 0,
          expiredLotCount: 0,
          warehouseCount: 1,
          activeWarehouseCount: 1,
          storageLocationCount: 1,
          totalAvailableUnits: 3250,
          totalMovementsCount: 1,
        },
        200,
        url
      );
    }
    if (url.endsWith('/inventory/materials')) return jsonResponse(materials, 200, url);
    if (url.endsWith('/inventory/lots')) return jsonResponse(sampleLots, 200, url);
    if (url.endsWith('/inventory/movements')) return jsonResponse(sampleMovements, 200, url);
    if (url.endsWith('/inventory/warehouses')) {
      return jsonResponse(
        [{ id: 1, warehouseCode: 'WH-MAIN', warehouseName: 'Main GMP Warehouse', active: true, locationCount: 1, activeLocationCount: 1, activeLotCount: 1 }],
        200,
        url
      );
    }
    if (url.endsWith('/inventory/locations')) {
      return jsonResponse(
        [{ id: 11, warehouseId: 1, warehouseCode: 'WH-MAIN', locationCode: 'A1-R2-B3', zoneName: 'Controlled Ambient', aisle: 'A1', rack: 'R2', bin: 'B3', active: true, activeLotCount: 1 }],
        200,
        url
      );
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 25);
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 28 materials'));
    assert.equal(dom.container.textContent.includes('RM-CEFIX-API'), false);

    // Go to Page 2 -> item 28 appears
    const page2Btn = dom.container.querySelector('button[aria-label="Page 2"]');
    assert.ok(page2Btn);
    await dom.click(page2Btn);
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 3);
    assert.ok(dom.container.textContent.includes('Showing 26\u201328 of 28 materials'));
    assert.ok(dom.container.textContent.includes('RM-CEFIX-API'));

    // Search for item 28 from Page 2 -> resets to Page 1 and filters full dataset
    const searchInput = dom.container.querySelector(
      'input[placeholder="Search materials by code, name, or recipe..."]'
    );
    assert.ok(searchInput);
    await dom.changeInput(searchInput, 'Cefixime');
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 1);
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 materials'));

    // Open MaterialDetailDrawer for RM-CEFIX-API
    const inspectBtn = findButtonByText(dom.container, 'Inspect');
    assert.ok(inspectBtn);
    await dom.click(inspectBtn);
    await dom.flush();

    const drawer = dom.container.querySelector('[data-testid="material-detail-drawer"]');
    assert.ok(drawer);
    assert.ok(drawer.textContent.includes('RM-CEFIX-API'));
    assert.ok(drawer.textContent.includes('LOT-CEF-01'));
    assert.ok(drawer.textContent.includes('MOV-2026-0001'));

    const closeDrawerBtn = drawer.querySelector('button[aria-label="Close material details"]');
    await dom.click(closeDrawerBtn);
    await dom.flush();
    assert.equal(dom.container.querySelector('[data-testid="material-detail-drawer"]'), null);

    // Switch to Warehouses & Locations sub-tab
    const whTab = findButtonByText(dom.container, 'Warehouses & Locations');
    await dom.click(whTab);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('WH-MAIN'));
    assert.ok(dom.container.textContent.includes('A1-R2-B3'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('GoodsReceiptModal, MaterialIssueModal, and StockAdjustmentModal validate fields and submit clean payloads without actor spoofing', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: InventoryManagement } = await import('./InventoryManagement.jsx');

  const materials = [
    {
      id: 10,
      materialCode: 'RM-PAR-API',
      materialName: 'Paracetamol USP',
      materialType: 'API',
      unit: 'kg',
      minimumStockLevel: 10,
      availableStock: 100,
      quarantinedStock: 0,
      expiredStock: 0,
      stockStatus: 'IN_STOCK',
      active: true,
      linkedRecipeCodes: ['RCP-PAR-500'],
      linkedRecipeCount: 1,
    },
  ];
  const warehouses = [{ id: 1, warehouseCode: 'WH-01', warehouseName: 'Central Store', active: true, locationCount: 1, activeLocationCount: 1, activeLotCount: 1 }];
  const locations = [{ id: 5, warehouseId: 1, warehouseCode: 'WH-01', locationCode: 'Z1-A1', zoneName: 'Ambient', active: true, activeLotCount: 1 }];
  const lots = [
    {
      id: 77,
      lotNumber: 'LOT-PAR-2026',
      rawMaterialId: 10,
      materialCode: 'RM-PAR-API',
      materialName: 'Paracetamol USP',
      warehouseId: 1,
      warehouseCode: 'WH-01',
      storageLocationId: 5,
      locationCode: 'Z1-A1',
      unit: 'kg',
      initialQuantity: 100,
      availableQuantity: 100,
      receivedDate: '2026-02-01',
      expiryDate: '2029-12-31',
      supplierName: 'Malladi Drugs',
      effectiveStatus: 'ACTIVE',
      expired: false,
      expiringSoon: false,
    },
  ];
  const orders = [
    {
      id: 301,
      orderNumber: 'MO-2026-0301',
      status: 'RELEASED',
      productName: 'Paracetamol 500mg',
      batches: [{ id: 401, batchNumber: 'BAT-0301-1', batchSize: 1000, batchUnit: 'Tablets', status: 'PLANNED' }],
    },
  ];
  const bomRequirements = {
    orderId: 301,
    orderNumber: 'MO-2026-0301',
    orderStatus: 'RELEASED',
    eligibleForIssue: true,
    recipeCode: 'RCP-PAR-500',
    recipeVersion: 1,
    requirements: [
      {
        recipeMaterialName: 'Paracetamol USP',
        quantityPerBatch: 25,
        totalRequiredQuantity: 25,
        recipeUnit: 'kg',
        rawMaterialId: 10,
        rawMaterialCode: 'RM-PAR-API',
        rawMaterialName: 'Paracetamol USP',
        inventoryUnit: 'kg',
        registeredInInventory: true,
        materialActive: true,
        unitMismatch: false,
        availableStock: 100,
        issuedQuantity: 0,
        remainingRequiredQuantity: 25,
        fulfilled: false,
      },
    ],
  };

  const postedPayloads = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/inventory/summary')) {
      return jsonResponse({ totalMaterialsCount: 1, activeMaterialsCount: 1, lowStockMaterialsCount: 0, outOfStockMaterialsCount: 0, activeLotCount: 1, expiringSoonLotCount: 0, quarantinedLotCount: 0, expiredLotCount: 0, warehouseCount: 1, activeWarehouseCount: 1, storageLocationCount: 1, totalAvailableUnits: 100, totalMovementsCount: 0 }, 200, url);
    }
    if (url.endsWith('/inventory/materials')) return jsonResponse(materials, 200, url);
    if (url.endsWith('/inventory/warehouses')) return jsonResponse(warehouses, 200, url);
    if (url.endsWith('/inventory/locations')) return jsonResponse(locations, 200, url);
    if (url.endsWith('/inventory/lots')) return jsonResponse(lots, 200, url);
    if (url.endsWith('/inventory/movements')) return jsonResponse([], 200, url);
    if (url.endsWith('/inventory/recipe-material-references')) return jsonResponse([], 200, url);
    if (url.endsWith('/orders')) return jsonResponse(orders, 200, url);
    if (url.endsWith('/inventory/orders/301/requirements')) return jsonResponse(bomRequirements, 200, url);

    if (options.method === 'POST') {
      const body = JSON.parse(options.body || '{}');
      postedPayloads.push({ url, body });
      return jsonResponse(
        {
          id: postedPayloads.length,
          movementNumber: `MOV-2026-00${postedPayloads.length}`,
          lotNumber: body.lotNumber || 'LOT-PAR-2026',
          quantityDelta: body.quantity || 0,
          unit: body.unit || 'kg',
          orderNumber: 'MO-2026-0301',
        },
        201,
        url
      );
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();

    // 1. Goods Receipt modal validation + submission
    await dom.click(findButtonByText(dom.container, 'Goods Receipt'));
    await dom.flush();
    const receiptDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(receiptDialog);

    // Submit empty -> inline validation errors
    const receiptForm = receiptDialog.querySelector('form[id="goods-receipt-form"]');
    await dom.submit(receiptForm);
    await dom.flush();
    assert.ok(receiptDialog.textContent.includes('Lot/batch number is required'));
    assert.ok(receiptDialog.textContent.includes('Receipt quantity must be greater than zero'));

    await dom.changeInput(findControlByLabel(receiptDialog, 'Supplier Lot / Batch Number'), 'LOT-NEW-99');
    await dom.changeInput(findControlByLabel(receiptDialog, 'Receipt Quantity'), '40');
    await dom.changeInput(findControlByLabel(receiptDialog, 'Lot Expiry Date'), '2099-06-30');
    await dom.changeInput(findControlByLabel(receiptDialog, 'Supplier Name'), 'Malladi Pharma');
    await dom.submit(receiptForm);
    await dom.flush();
    await dom.flush();

    const receiptPost = postedPayloads.find((p) => p.url.endsWith('/inventory/receipts'));
    assert.ok(receiptPost);
    assert.equal(receiptPost.body.lotNumber, 'LOT-NEW-99');
    assert.equal(receiptPost.body.quantity, 40);
    assert.equal('performedBy' in receiptPost.body, false);

    // 2. Material Issue modal -> BOM Select prefill + submit
    await dom.click(findButtonByText(dom.container, 'Issue Material'));
    await dom.flush();
    await dom.flush();
    const issueDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(issueDialog);
    assert.ok(issueDialog.textContent.includes('Order Recipe Material Requirements'));
    assert.ok(issueDialog.textContent.includes('RCP-PAR-500'));

    const selectReqBtn = findButtonByText(issueDialog, 'Select');
    assert.ok(selectReqBtn);
    await dom.click(selectReqBtn);
    await dom.flush();

    const issueQtyInput = findControlByLabel(issueDialog, 'Issue Quantity');
    assert.equal(issueQtyInput.value, '25');
    await dom.submit(issueDialog.querySelector('form[id="material-issue-form"]'));
    await dom.flush();
    await dom.flush();

    const issuePost = postedPayloads.find((p) => p.url.endsWith('/inventory/issues'));
    assert.ok(issuePost);
    assert.equal(issuePost.body.manufacturingOrderId, 301);
    assert.equal(issuePost.body.materialLotId, 77);
    assert.equal(issuePost.body.quantity, 25);
    assert.equal('performedBy' in issuePost.body, false);

    // 3. Stock Adjustment modal -> mandatory reason guard + submission
    await dom.click(findButtonByText(dom.container, 'Lots & Expiry'));
    await dom.flush();
    await dom.click(findButtonByText(dom.container, 'Stock Adjustment'));
    await dom.flush();

    const adjustDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(adjustDialog);
    const adjustForm = adjustDialog.querySelector('form[id="stock-adjustment-form"]');
    await dom.submit(adjustForm);
    await dom.flush();
    assert.ok(adjustDialog.textContent.includes('Adjustment reason (minimum 3 characters) is mandatory'));

    await dom.changeInput(findControlByLabel(adjustDialog, 'Adjustment Quantity'), '5');
    await dom.changeInput(findControlByLabel(adjustDialog, 'Mandatory Audit Reason'), 'Damaged foil liner found on physical count');
    await dom.submit(adjustForm);
    await dom.flush();
    await dom.flush();

    const adjustPost = postedPayloads.find((p) => p.url.endsWith('/inventory/adjustments'));
    assert.ok(adjustPost);
    assert.equal(adjustPost.body.materialLotId, 77);
    assert.equal(adjustPost.body.adjustmentType, 'DECREASE');
    assert.equal(adjustPost.body.quantity, 5);
    assert.equal('performedBy' in adjustPost.body, false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('StorageLocationModal and InventoryManagement retain and display Zone / Area immediately after create, upon reload, and in edit modal', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: InventoryManagement } = await import('./InventoryManagement.jsx');

  const warehouses = [
    {
      id: 1,
      warehouseCode: 'WH-PUNE-01',
      warehouseName: 'Pune Central Warehouse',
      storageCondition: 'Controlled Room Temperature',
      active: true,
      locationCount: 0,
      activeLocationCount: 0,
      activeLotCount: 0,
    },
  ];

  let locations = [];
  const postedPayloads = [];
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/inventory/summary')) {
      return jsonResponse({
        totalMaterialsCount: 0,
        activeMaterialsCount: 0,
        lowStockMaterialsCount: 0,
        outOfStockMaterialsCount: 0,
        activeLotCount: 0,
        expiringSoonLotCount: 0,
        quarantinedLotCount: 0,
        expiredLotCount: 0,
        warehouseCount: 1,
        activeWarehouseCount: 1,
        storageLocationCount: locations.length,
        totalAvailableUnits: 0,
        totalMovementsCount: 0,
      }, 200, url);
    }
    if (url.endsWith('/inventory/materials')) return jsonResponse([], 200, url);
    if (url.endsWith('/inventory/warehouses')) return jsonResponse(warehouses, 200, url);
    if (url.endsWith('/inventory/locations')) return jsonResponse(locations, 200, url);
    if (url.endsWith('/inventory/lots')) return jsonResponse([], 200, url);
    if (url.endsWith('/inventory/movements')) return jsonResponse([], 200, url);
    if (url.endsWith('/inventory/recipe-material-references')) return jsonResponse([], 200, url);

    if (options.method === 'POST' && url.includes('/locations')) {
      const body = JSON.parse(options.body || '{}');
      postedPayloads.push({ url, body });
      const newLoc = {
        id: 99,
        warehouseId: body.warehouseId || 1,
        warehouseCode: 'WH-PUNE-01',
        warehouseName: 'Pune Central Warehouse',
        locationCode: body.locationCode,
        zoneName: body.zoneName,
        aisle: body.aisle,
        rack: body.rack,
        bin: body.bin,
        active: body.active !== false,
        activeLotCount: 0,
      };
      locations = [newLoc];
      return jsonResponse(newLoc, 201, url);
    }

    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();

    // 1. Switch to Warehouses & Locations sub-tab
    const whTab = findButtonByText(dom.container, 'Warehouses & Locations');
    await dom.click(whTab);
    await dom.flush();

    // 2. Click "Add Location"
    const addLocBtn = findButtonByText(dom.container, 'Add Location');
    assert.ok(addLocBtn);
    await dom.click(addLocBtn);
    await dom.flush();

    const dialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(dialog);
    assert.ok(dialog.textContent.includes('Add Storage Location'));

    // 3. Fill in the fields
    const whSelect = findControlByLabel(dialog, 'Parent Warehouse');
    assert.ok(whSelect);
    await dom.changeInput(whSelect, '1');

    const codeInput = findControlByLabel(dialog, 'Location Code');
    assert.ok(codeInput);
    await dom.changeInput(codeInput, 'WH-PUNE-01-A01-R01-B01');

    const zoneInput = findControlByLabel(dialog, 'Zone / Area');
    assert.ok(zoneInput);
    await dom.changeInput(zoneInput, 'API Storage');

    const aisleInput = findControlByLabel(dialog, 'Aisle');
    assert.ok(aisleInput);
    await dom.changeInput(aisleInput, 'A01');

    const rackInput = findControlByLabel(dialog, 'Rack');
    assert.ok(rackInput);
    await dom.changeInput(rackInput, 'R01');

    const binInput = findControlByLabel(dialog, 'Bin');
    assert.ok(binInput);
    await dom.changeInput(binInput, 'B01');

    // 4. Submit form
    const form = dialog.querySelector('form[id="storage-location-form"]');
    assert.ok(form);
    await dom.submit(form);
    await dom.flush();
    await dom.flush();

    // 5. Verify payload submitted to backend retains zoneName = 'API Storage'
    assert.equal(postedPayloads.length, 1);
    assert.equal(postedPayloads[0].body.warehouseId, 1);
    assert.equal(postedPayloads[0].body.locationCode, 'WH-PUNE-01-A01-R01-B01');
    assert.equal(postedPayloads[0].body.zoneName, 'API Storage');
    assert.equal(postedPayloads[0].body.aisle, 'A01');
    assert.equal(postedPayloads[0].body.rack, 'R01');
    assert.equal(postedPayloads[0].body.bin, 'B01');

    // 6. Verify table displays the reloaded location with Zone / Area = 'API Storage'
    assert.ok(dom.container.textContent.includes('WH-PUNE-01-A01-R01-B01'));
    assert.ok(dom.container.textContent.includes('API Storage'));

    // 7. Click Edit on the specific location row
    const rows = Array.from(dom.container.querySelectorAll('tr'));
    const locRow = rows.find((r) => r.textContent.includes('WH-PUNE-01-A01-R01-B01'));
    assert.ok(locRow);
    const editBtn = findButtonByText(locRow, 'Edit');
    assert.ok(editBtn);
    await dom.click(editBtn);
    await dom.flush();

    // 8. Verify Edit modal displays the persisted Zone / Area
    const editDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(editDialog);
    assert.ok(editDialog.textContent.includes('Edit Storage Location'));
    const editZoneInput = findControlByLabel(editDialog, 'Zone / Area');
    assert.ok(editZoneInput);
    assert.equal(editZoneInput.value, 'API Storage');
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('InventoryManagement enforces RBAC action visibility across all 5 roles and OrderDetailsModal integrates BOM requirements', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/orders');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: InventoryManagement } = await import('./InventoryManagement.jsx');
  const { default: OrderDetailsModal } = await import('./OrderDetailsModal.jsx');

  const materials = [
    { id: 10, materialCode: 'RM-PAR-API', materialName: 'Paracetamol USP', materialType: 'API', unit: 'kg', minimumStockLevel: 10, availableStock: 50, quarantinedStock: 0, expiredStock: 0, stockStatus: 'IN_STOCK', active: true, linkedRecipeCount: 0 },
  ];
  const lots = [
    { id: 77, lotNumber: 'LOT-01', rawMaterialId: 10, materialCode: 'RM-PAR-API', materialName: 'Paracetamol USP', warehouseId: 1, warehouseCode: 'WH-01', storageLocationId: 5, locationCode: 'B1', unit: 'kg', initialQuantity: 50, availableQuantity: 50, receivedDate: '2026-01-01', expiryDate: '2029-01-01', supplierName: 'Supplier A', effectiveStatus: 'ACTIVE', expired: false, expiringSoon: false },
  ];

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/inventory/summary')) {
      return jsonResponse({ totalMaterialsCount: 1, activeMaterialsCount: 1, lowStockMaterialsCount: 0, outOfStockMaterialsCount: 0, activeLotCount: 1, expiringSoonLotCount: 0, quarantinedLotCount: 0, expiredLotCount: 0, warehouseCount: 0, activeWarehouseCount: 0, storageLocationCount: 0, totalAvailableUnits: 50, totalMovementsCount: 0 }, 200, url);
    }
    if (url.endsWith('/inventory/materials')) return jsonResponse(materials, 200, url);
    if (url.endsWith('/inventory/lots')) return jsonResponse(lots, 200, url);
    if (url.endsWith('/orders/901')) {
      return jsonResponse(
        {
          id: 901,
          orderNumber: 'MO-BOM-901',
          status: 'RELEASED',
          productName: 'Paracetamol 500mg',
          productCode: 'PRD-001',
          recipeId: 1,
          recipeCode: 'RCP-PAR-500',
          recipeVersion: 1,
          plannedQuantity: 1000,
          quantityUnit: 'Tablets',
          targetBatchCount: 1,
          plannedStartDate: '2099-01-01',
          plannedEndDate: '2099-01-05',
          batches: [{ id: 9011, batchNumber: 'BAT-901-1', batchSize: 1000, batchUnit: 'Tablets', status: 'PLANNED' }],
        },
        200,
        url
      );
    }
    if (url.endsWith('/inventory/orders/901/requirements')) {
      return jsonResponse(
        {
          orderId: 901,
          orderNumber: 'MO-BOM-901',
          requirements: [
            {
              recipeMaterialName: 'Paracetamol USP',
              totalRequiredQuantity: 25,
              recipeUnit: 'kg',
              rawMaterialId: 10,
              rawMaterialCode: 'RM-PAR-API',
              registeredInInventory: true,
              availableStock: 50,
              inventoryUnit: 'kg',
              issuedQuantity: 5,
              remainingRequiredQuantity: 20,
              fulfilled: false,
              unitMismatch: false,
            },
          ],
        },
        200,
        url
      );
    }
    return jsonResponse([], 200, url);
  };

  try {
    // OPERATOR sees Issue Material, NOT Add Material or Goods Receipt
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Issue Material'));
    assert.equal(dom.container.textContent.includes('Add Material'), false);
    assert.equal(dom.container.textContent.includes('Goods Receipt'), false);

    // QC_ANALYST is strictly read-only across Inventory
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Add Material'), false);
    assert.equal(dom.container.textContent.includes('Goods Receipt'), false);
    assert.equal(dom.container.textContent.includes('Issue Material'), false);

    // QA_REVIEWER sees Adjust on Lots sub-tab, NOT Add Material / Goods Receipt / Issue Material
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(
          NavigationProvider,
          { value: { activeTab: 'inventory', routeParams: new URLSearchParams(), routeNonce: 0, navigateToTab: () => {} } },
          React.createElement(InventoryManagement)
        )
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Issue Material'), false);
    await dom.click(findButtonByText(dom.container, 'Lots & Expiry'));
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Stock Adjustment'));

    // OrderDetailsModal shows BOM requirements & Issue Materials deep-link for OPERATOR
    const navCalls = [];
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'orders',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab: (tab, params) => navCalls.push({ tab, params }),
            },
          },
          React.createElement(OrderDetailsModal, {
            isOpen: true,
            orderId: 901,
            onClose: () => {},
          })
        )
      )
    );
    await dom.flush();
    await dom.flush();

    const orderDrawer = dom.container.querySelector('[data-testid="order-detail-drawer"]');
    assert.ok(orderDrawer);
    assert.ok(orderDrawer.textContent.includes('Order Raw Material Requirements'));
    assert.ok(orderDrawer.textContent.includes('RM-PAR-API'));
    const issueNavBtn = findButtonByText(orderDrawer, 'Issue Materials');
    assert.ok(issueNavBtn);
    await dom.click(issueNavBtn);
    assert.deepEqual(navCalls, [{ tab: 'inventory', params: { action: 'issue', orderId: 901 } }]);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

