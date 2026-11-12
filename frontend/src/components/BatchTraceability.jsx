import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  Award,
  Barcode,
  Camera,
  CameraOff,
  CheckCircle2,
  Clock,
  ExternalLink,
  FileCheck2,
  FileText,
  Filter,
  History,
  Layers,
  Package,
  Printer,
  QrCode,
  RefreshCw,
  Search,
  Shield,
  ShieldAlert,
  ShieldCheck,
  User,
  Warehouse,
  XCircle,
} from 'lucide-react';
import StatusBadge from './ui/StatusBadge.jsx';
import InlineAlert from './ui/InlineAlert.jsx';
import BatchQrModal from './BatchQrModal.jsx';
import { fetchBatchTraceabilityByIdentifier } from '../services/batchTraceabilityApi.js';
import { formatAppDateTime, formatCalendarDate } from '../utils/dateTimeUtils.js';

export default function BatchTraceability({ initialBatchNumber }) {
  const [searchInput, setSearchInput] = useState(initialBatchNumber || '');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [dossier, setDossier] = useState(null);

  // QR Modal
  const [isQrModalOpen, setIsQrModalOpen] = useState(false);

  // Camera Scanning State
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraReady, setCameraReady] = useState(false);
  const [cameraError, setCameraError] = useState(null);
  const videoRef = useRef(null);
  const videoStreamRef = useRef(null);
  const scanIntervalRef = useRef(null);
  const isScanningRef = useRef(false);

  // Stop camera tracks cleanly
  const stopCamera = useCallback(() => {
    if (scanIntervalRef.current) {
      clearInterval(scanIntervalRef.current);
      scanIntervalRef.current = null;
    }
    if (videoStreamRef.current) {
      videoStreamRef.current.getTracks().forEach((track) => {
        try {
          track.stop();
        } catch (_) {}
      });
      videoStreamRef.current = null;
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    setCameraActive(false);
    setCameraReady(false);
    isScanningRef.current = false;
  }, []);

  // Safe helper to attach media stream to video DOM element
  const attachStreamToVideo = useCallback((stream, videoEl) => {
    if (!videoEl || !stream) return;
    if (videoEl.srcObject !== stream) {
      videoEl.srcObject = stream;
    }
    if (typeof videoEl.play === 'function') {
      const playPromise = videoEl.play();
      if (playPromise && typeof playPromise.catch === 'function') {
        playPromise.catch((err) => {
          console.warn('Camera video play deferred:', err);
        });
      }
    }
  }, []);

  // Callback ref ensures stream is attached as soon as video DOM node mounts
  const setVideoNode = useCallback(
    (node) => {
      videoRef.current = node;
      if (node && videoStreamRef.current) {
        attachStreamToVideo(videoStreamRef.current, node);
      }
    },
    [attachStreamToVideo]
  );

  // Synchronize stream attachment whenever cameraActive state toggles
  useEffect(() => {
    if (cameraActive && videoRef.current && videoStreamRef.current) {
      attachStreamToVideo(videoStreamRef.current, videoRef.current);
    }
  }, [cameraActive, attachStreamToVideo]);

  // Cleanup camera on unmount or navigation
  useEffect(() => {
    return () => {
      stopCamera();
    };
  }, [stopCamera]);

  // Handle URL hash changes or initial batch param
  const loadDossier = useCallback(async (identifier) => {
    if (!identifier || !String(identifier).trim()) {
      return;
    }
    const cleanId = String(identifier).trim();
    setLoading(true);
    setError(null);

    try {
      const data = await fetchBatchTraceabilityByIdentifier(cleanId);
      setDossier(data);
      setSearchInput(data.batchNumber);
    } catch (err) {
      setDossier(null);
      setError(
        err.message ||
          `Batch "${cleanId}" could not be found or retrieved. Please verify the batch number or QR code.`
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (initialBatchNumber) {
      setSearchInput(initialBatchNumber);
      loadDossier(initialBatchNumber);
    } else if (typeof window !== 'undefined') {
      const hash = window.location.hash || '';
      const queryIdx = hash.indexOf('?');
      if (queryIdx !== -1) {
        const qs = new URLSearchParams(hash.substring(queryIdx));
        const batchParam = qs.get('batchNumber') || qs.get('batch') || qs.get('qr');
        if (batchParam) {
          setSearchInput(batchParam);
          loadDossier(batchParam);
        }
      }
    }
  }, [initialBatchNumber, loadDossier]);

  // Start Camera QR Scanning
  const startCamera = async () => {
    setCameraError(null);
    setCameraReady(false);

    // 1. Verify getUserMedia capability
    if (!navigator?.mediaDevices?.getUserMedia) {
      setCameraError('Camera access is not supported in this browser environment. Please use manual batch lookup.');
      return;
    }

    // 2. Verify BarcodeDetector support before acquiring hardware camera stream
    if (!('BarcodeDetector' in window)) {
      setCameraError(
        'Camera QR scanning is not supported by this browser (BarcodeDetector API unavailable). Please enter the batch number or QR code manually.'
      );
      return;
    }

    try {
      if (typeof window.BarcodeDetector.getSupportedFormats === 'function') {
        const formats = await window.BarcodeDetector.getSupportedFormats();
        if (formats && Array.isArray(formats) && formats.length > 0 && !formats.includes('qr_code')) {
          setCameraError('QR code format is not supported by your camera scanner. Please use manual batch lookup.');
          return;
        }
      }
    } catch (_) {
      // Proceed if format check is not implemented
    }

    stopCamera();

    let stream = null;
    try {
      // Prefer environment-facing (rear) camera on mobile/tablet devices
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode: { ideal: 'environment' },
            width: { ideal: 1280 },
            height: { ideal: 720 },
          },
          audio: false,
        });
      } catch (envErr) {
        // Fallback to any available video input device (desktop webcams, integrated cameras)
        stream = await navigator.mediaDevices.getUserMedia({
          video: true,
          audio: false,
        });
      }

      // Track stream disconnects
      stream.getVideoTracks().forEach((track) => {
        track.onended = () => {
          stopCamera();
          setCameraError('Camera stream ended or disconnected. Please use manual batch lookup.');
        };
      });

      videoStreamRef.current = stream;
      setCameraActive(true);

      // If video element is already mounted, attach immediately
      if (videoRef.current) {
        attachStreamToVideo(stream, videoRef.current);
      }

      // Start detection loop
      const barcodeDetector = new window.BarcodeDetector({ formats: ['qr_code'] });
      scanIntervalRef.current = setInterval(async () => {
        if (
          !videoRef.current ||
          isScanningRef.current ||
          (videoRef.current.readyState !== undefined && videoRef.current.readyState < 2)
        ) {
          return;
        }
        try {
          isScanningRef.current = true;
          const barcodes = await barcodeDetector.detect(videoRef.current);
          if (barcodes && barcodes.length > 0) {
            const rawValue = barcodes[0].rawValue || '';
            if (rawValue.trim()) {
              stopCamera();
              // Extract batch number if it is a full URL or direct code
              let extracted = rawValue.trim();
              if (extracted.includes('batchNumber=')) {
                const match = extracted.match(/batchNumber=([^&]+)/);
                if (match) extracted = decodeURIComponent(match[1]);
              } else if (extracted.includes('qr=')) {
                const match = extracted.match(/qr=([^&]+)/);
                if (match) extracted = decodeURIComponent(match[1]);
              }
              setSearchInput(extracted);
              loadDossier(extracted);
            }
          }
        } catch (_) {
          // Frame detection error, continue next frame
        } finally {
          isScanningRef.current = false;
        }
      }, 300);
    } catch (err) {
      let friendlyMsg = 'Unable to access camera. Please enter the batch number manually.';
      if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
        friendlyMsg = 'Camera permission was denied. Please allow camera access in your browser or use manual batch lookup.';
      } else if (err.name === 'NotFoundError' || err.name === 'DevicesNotFoundError') {
        friendlyMsg = 'No camera found on this device. Please use manual batch lookup.';
      } else if (err.name === 'NotReadableError' || err.name === 'TrackStartError') {
        friendlyMsg = 'Camera is in use by another application or process. Please close it and try again, or use manual lookup.';
      } else if (err.name === 'OverconstrainedError') {
        friendlyMsg = 'No camera matching requirements found. Please use manual batch lookup.';
      } else if (err.message) {
        friendlyMsg = `Camera error: ${err.message}. Please use manual batch lookup.`;
      }
      setCameraError(friendlyMsg);
      stopCamera();
    }
  };

  const handleSearchSubmit = (e) => {
    e.preventDefault();
    if (searchInput.trim()) {
      stopCamera();
      loadDossier(searchInput.trim());
    }
  };

  const handlePrintDossier = () => {
    window.print();
  };

  return (
    <div className="space-y-6 pb-12">
      {/* Top Banner & Header */}
      <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <span className="p-2 bg-teal-50 text-teal-700 rounded-lg">
                <QrCode className="w-6 h-6" />
              </span>
              <div>
                <h1 className="text-xl font-bold text-slate-900">
                  Batch Traceability & eBR Dossier
                </h1>
                <p className="text-xs text-slate-500">
                  Unified manufacturing lifecycle, raw material consumption, eBR execution, QC, QA, and audit trail
                </p>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {dossier && (
              <>
                <button
                  type="button"
                  onClick={() => setIsQrModalOpen(true)}
                  className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-teal-500"
                >
                  <QrCode className="w-4 h-4 text-teal-700" />
                  View Batch QR
                </button>
                <button
                  type="button"
                  onClick={handlePrintDossier}
                  className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold text-slate-700 bg-white border border-slate-300 hover:bg-slate-50 rounded-lg focus:outline-none focus:ring-2 focus:ring-teal-500"
                >
                  <Printer className="w-4 h-4 text-slate-600" />
                  Print Dossier
                </button>
              </>
            )}
          </div>
        </div>

        {/* Search & Camera Bar */}
        <div className="mt-6 pt-6 border-t border-slate-100">
          <form onSubmit={handleSearchSubmit} className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Enter Batch Number (e.g. BAT-2026-0010-01) or QR Code identifier..."
                className="w-full pl-9 pr-3 py-2 text-sm border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-teal-500 font-mono"
              />
            </div>
            <button
              type="submit"
              disabled={loading || !searchInput.trim()}
              className="inline-flex items-center justify-center gap-1.5 px-4 py-2 text-sm font-semibold text-white bg-teal-600 hover:bg-teal-700 disabled:opacity-50 rounded-lg focus:outline-none focus:ring-2 focus:ring-teal-500"
            >
              {loading ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              Lookup Dossier
            </button>
            <button
              type="button"
              onClick={cameraActive ? stopCamera : startCamera}
              className={`inline-flex items-center justify-center gap-1.5 px-4 py-2 text-sm font-semibold rounded-lg border focus:outline-none focus:ring-2 focus:ring-teal-500 ${
                cameraActive
                  ? 'bg-rose-50 text-rose-700 border-rose-200 hover:bg-rose-100'
                  : 'bg-slate-50 text-slate-700 border-slate-300 hover:bg-slate-100'
              }`}
            >
              {cameraActive ? (
                <>
                  <CameraOff className="w-4 h-4" />
                  Stop Camera
                </>
              ) : (
                <>
                  <Camera className="w-4 h-4" />
                  Scan QR
                </>
              )}
            </button>
          </form>

          {/* Quick lookup helper for candidate test batch */}
          <div className="mt-2 flex items-center gap-2 text-xs text-slate-500">
            <span>Quick Batch:</span>
            <button
              type="button"
              onClick={() => {
                setSearchInput('BAT-2026-0010-01');
                loadDossier('BAT-2026-0010-01');
              }}
              className="text-teal-700 hover:underline font-mono"
            >
              BAT-2026-0010-01
            </button>
            <span>•</span>
            <button
              type="button"
              onClick={() => {
                setSearchInput('BAT-2026-0009-01');
                loadDossier('BAT-2026-0009-01');
              }}
              className="text-teal-700 hover:underline font-mono"
            >
              BAT-2026-0009-01
            </button>
          </div>

          {/* Camera Viewport when active */}
          {cameraActive && (
            <div className="mt-4 p-4 bg-slate-900 rounded-xl w-full max-w-sm mx-auto text-center shadow-lg">
              <div className="relative w-full rounded-lg overflow-hidden border-2 border-teal-500 bg-black aspect-square flex items-center justify-center">
                <video
                  ref={setVideoNode}
                  autoPlay
                  playsInline
                  muted
                  onLoadedMetadata={(e) => {
                    if (typeof e.target.play === 'function') {
                      const playPromise = e.target.play();
                      if (playPromise && typeof playPromise.catch === 'function') {
                        playPromise.catch(() => {});
                      }
                    }
                    setCameraReady(true);
                  }}
                  onPlaying={() => setCameraReady(true)}
                  className="w-full h-full object-cover"
                />
                {!cameraReady && (
                  <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/80 text-slate-300 gap-2 p-4">
                    <RefreshCw className="w-6 h-6 animate-spin text-teal-400" />
                    <span className="text-xs font-medium">Starting camera preview...</span>
                  </div>
                )}
                {cameraReady && (
                  <div className="absolute inset-0 border-2 border-dashed border-teal-400 opacity-70 pointer-events-none m-8 rounded-lg animate-pulse" />
                )}
              </div>
              <p className="text-xs text-slate-300 mt-2.5">
                Align the batch QR code within the frame to scan automatically
              </p>
            </div>
          )}

          {cameraError && (
            <div className="mt-3">
              <InlineAlert variant="warning" message={cameraError} />
            </div>
          )}

          {error && (
            <div className="mt-4">
              <InlineAlert variant="error" message={error} />
            </div>
          )}
        </div>
      </div>

      {loading && (
        <div className="bg-white border border-slate-200 rounded-xl p-12 text-center text-slate-500">
          <RefreshCw className="w-8 h-8 mx-auto animate-spin text-teal-600 mb-2" />
          <p className="text-sm font-semibold text-slate-800">Compiling Batch Traceability Dossier...</p>
          <p className="text-xs text-slate-500">Reconciling manufacturing order, inventory lots, eBR steps, QC, and audit trail</p>
        </div>
      )}

      {!loading && !dossier && !error && (
        <div className="bg-white border border-dashed border-slate-300 rounded-xl p-12 text-center text-slate-400">
          <Barcode className="w-12 h-12 mx-auto text-slate-300 mb-3" />
          <h3 className="text-sm font-bold text-slate-700">No Batch Selected</h3>
          <p className="text-xs text-slate-500 mt-1 max-w-md mx-auto">
            Scan a manufacturing batch QR code or enter a batch number above to view the end-to-end traceability chain.
          </p>
        </div>
      )}

      {/* DOSSIER CONTENT */}
      {!loading && dossier && (
        <div className="space-y-6">
          {/* SECTION A: Batch Overview & Completeness Indicator */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6 pb-6 border-b border-slate-200">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-semibold uppercase tracking-wider text-teal-700 bg-teal-50 px-2.5 py-0.5 rounded-full border border-teal-200">
                    Section A • Batch Overview
                  </span>
                  <span className="font-mono text-xs text-slate-500 bg-slate-100 px-2 py-0.5 rounded">
                    QR: {dossier.qrCode}
                  </span>
                </div>
                <h2 className="text-2xl font-extrabold text-slate-900 tracking-tight font-mono">
                  {dossier.batchNumber}
                </h2>
                <p className="text-sm text-slate-600 font-medium">
                  {dossier.productName || 'Unspecified Product'} ({dossier.productCode || 'N/A'})
                </p>
              </div>

              {/* Completeness Card */}
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 min-w-[280px]">
                <div className="flex items-center justify-between gap-2 mb-2">
                  <span className="text-xs font-bold text-slate-700 flex items-center gap-1.5">
                    <ShieldCheck className="w-4 h-4 text-teal-600" />
                    Traceability Completeness
                  </span>
                  <span className="text-sm font-extrabold text-teal-700 font-mono">
                    {dossier.completenessPercent}%
                  </span>
                </div>
                <div className="w-full bg-slate-200 rounded-full h-2.5 overflow-hidden">
                  <div
                    className={`h-2.5 rounded-full transition-all duration-500 ${
                      dossier.completenessPercent >= 90
                        ? 'bg-emerald-500'
                        : dossier.completenessPercent >= 50
                        ? 'bg-teal-500'
                        : 'bg-amber-500'
                    }`}
                    style={{ width: `${dossier.completenessPercent}%` }}
                  />
                </div>
                <div className="mt-2 flex items-center justify-between text-[11px] text-slate-500">
                  <span>Status: <strong>{dossier.completenessStatus}</strong></span>
                  <span className="text-slate-400">8 Pillars Verified</span>
                </div>
              </div>
            </div>

            {/* Completeness Highlights Checklist */}
            {dossier.completenessHighlights && dossier.completenessHighlights.length > 0 && (
              <div className="mt-4 p-3 bg-teal-50/50 border border-teal-100 rounded-lg">
                <span className="text-xs font-semibold text-teal-900 block mb-1">
                  Traceability Verification Highlights:
                </span>
                <ul className="grid grid-cols-1 md:grid-cols-2 gap-1 text-xs text-teal-800">
                  {dossier.completenessHighlights.map((hl, idx) => (
                    <li key={idx} className="flex items-center gap-1.5">
                      <CheckCircle2 className="w-3.5 h-3.5 text-teal-600 shrink-0" />
                      <span>{hl}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* Metadata Grid */}
            <div className="mt-6 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 text-xs">
              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">Manufacturing Order</span>
                <span className="font-bold text-slate-900 font-mono text-sm">
                  {dossier.orderNumber || 'N/A'}
                </span>
                <span className="text-[11px] text-slate-400 block mt-0.5">
                  Order Status: {dossier.orderStatus || 'N/A'}
                </span>
              </div>

              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">Recipe & Version</span>
                <span className="font-bold text-slate-900 font-mono text-sm">
                  {dossier.recipeCode || 'N/A'} (v{dossier.recipeVersion || 1})
                </span>
              </div>

              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">Batch Size & Yield</span>
                <span className="font-bold text-slate-900 text-sm">
                  {dossier.batchSize} {dossier.batchUnit}
                </span>
                <span className="text-[11px] text-slate-500 block mt-0.5">
                  Actual: {dossier.actualYield != null ? `${dossier.actualYield} ${dossier.batchUnit}` : 'Not recorded'}
                </span>
              </div>

              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">Manufacturing Status</span>
                <div className="mt-1">
                  <StatusBadge status={dossier.batchStatus} />
                </div>
              </div>

              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">QA Disposition</span>
                <div className="mt-1">
                  <StatusBadge status={dossier.qaStatus || 'PENDING_QA'} />
                </div>
              </div>

              <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                <span className="text-slate-500 block">Dates (Mfg / Exp)</span>
                <span className="font-medium text-slate-800 block text-xs mt-0.5">
                  Mfg: {dossier.manufactureDate ? formatCalendarDate(dossier.manufactureDate) : 'Not recorded'}
                </span>
                <span className="font-medium text-slate-800 block text-xs">
                  Exp: {dossier.expiryDate ? formatCalendarDate(dossier.expiryDate) : 'Not recorded'}
                </span>
              </div>
            </div>
          </section>

          {/* SECTION B: Manufacturing Traceability */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <Package className="w-5 h-5 text-indigo-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section B • Manufacturing Traceability
              </h3>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 text-xs">
              <div className="p-3 border border-slate-200 rounded-lg">
                <span className="text-slate-500 block">Parent Manufacturing Order</span>
                <span className="font-bold text-slate-900 font-mono text-sm">{dossier.orderNumber || 'N/A'}</span>
                <div className="mt-1 flex items-center gap-2">
                  <span className="text-slate-500">Planned Quantity:</span>
                  <span className="font-semibold">{dossier.plannedQuantity} {dossier.batchUnit}</span>
                </div>
              </div>
              <div className="p-3 border border-slate-200 rounded-lg">
                <span className="text-slate-500 block">Target Batches</span>
                <span className="font-bold text-slate-900 text-sm">{dossier.targetBatchCount || 1} batch(es) planned</span>
                <span className="text-slate-400 block mt-1">Single Batch Execution Unit</span>
              </div>
              <div className="p-3 border border-slate-200 rounded-lg">
                <span className="text-slate-500 block">Planned Schedule</span>
                <span className="block mt-1">Start: {dossier.orderPlannedStartDate ? formatCalendarDate(dossier.orderPlannedStartDate) : 'Not recorded'}</span>
                <span className="block">End: {dossier.orderPlannedEndDate ? formatCalendarDate(dossier.orderPlannedEndDate) : 'Not recorded'}</span>
              </div>
              <div className="p-3 border border-slate-200 rounded-lg">
                <span className="text-slate-500 block">Actual Execution Timestamps</span>
                <span className="block mt-1">Started: {dossier.startTime ? formatAppDateTime(dossier.startTime) : 'Not recorded'}</span>
                <span className="block">Completed: {dossier.endTime ? formatAppDateTime(dossier.endTime) : 'Not recorded'}</span>
              </div>
            </div>
          </section>

          {/* SECTION C: Raw Material Traceability */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <Warehouse className="w-5 h-5 text-sky-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section C • Raw Material Traceability (Inventory & Consumption)
              </h3>
            </div>

            {/* Sub-table 1: Recipe Requirements vs Actual Issued */}
            <div className="mb-6">
              <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-2">
                1. Recipe Material Requirements Reconciliation
              </h4>
              {dossier.materialRequirements && dossier.materialRequirements.length > 0 ? (
                <div className="overflow-x-auto border border-slate-200 rounded-lg">
                  <table className="min-w-full divide-y divide-slate-200 text-xs">
                    <thead className="bg-slate-50 text-slate-600 font-semibold">
                      <tr>
                        <th className="px-3 py-2 text-left">Recipe Material</th>
                        <th className="px-3 py-2 text-left">Material Code</th>
                        <th className="px-3 py-2 text-right">Required (Order)</th>
                        <th className="px-3 py-2 text-right">Issued Quantity</th>
                        <th className="px-3 py-2 text-right">Remaining</th>
                        <th className="px-3 py-2 text-center">Fulfillment</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-200 bg-white">
                      {dossier.materialRequirements.map((req, idx) => (
                        <tr key={idx} className="hover:bg-slate-50">
                          <td className="px-3 py-2 font-medium text-slate-900">{req.recipeMaterialName}</td>
                          <td className="px-3 py-2 font-mono text-slate-600">{req.rawMaterialCode || 'Not Linked'}</td>
                          <td className="px-3 py-2 text-right font-mono">{req.totalRequiredQuantity} {req.recipeUnit}</td>
                          <td className="px-3 py-2 text-right font-mono font-semibold text-teal-700">
                            {req.issuedQuantity || 0} {req.recipeUnit}
                          </td>
                          <td className="px-3 py-2 text-right font-mono text-slate-500">
                            {req.remainingRequiredQuantity || 0} {req.recipeUnit}
                          </td>
                          <td className="px-3 py-2 text-center">
                            <span
                              className={`px-2 py-0.5 rounded text-[11px] font-semibold ${
                                req.fulfilled
                                  ? 'bg-emerald-100 text-emerald-800'
                                  : 'bg-amber-100 text-amber-800'
                              }`}
                            >
                              {req.fulfilled ? 'FULFILLED' : 'PARTIAL / PENDING'}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                  No recipe material requirements linked to parent order.
                </p>
              )}
            </div>

            {/* Sub-table 2: Actual Material Issue Stock Movements */}
            <div>
              <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-2">
                2. Actual Consumed Material Lots & Stock Movements
              </h4>
              {dossier.materialMovements && dossier.materialMovements.length > 0 ? (
                <div className="overflow-x-auto border border-slate-200 rounded-lg">
                  <table className="min-w-full divide-y divide-slate-200 text-xs">
                    <thead className="bg-slate-50 text-slate-600 font-semibold">
                      <tr>
                        <th className="px-3 py-2 text-left">Movement #</th>
                        <th className="px-3 py-2 text-left">Material & Lot</th>
                        <th className="px-3 py-2 text-left">Warehouse / Location</th>
                        <th className="px-3 py-2 text-left">Supplier & COA Reference</th>
                        <th className="px-3 py-2 text-right">Issued Quantity</th>
                        <th className="px-3 py-2 text-left">Issued By</th>
                        <th className="px-3 py-2 text-left">Timestamp</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-200 bg-white">
                      {dossier.materialMovements.map((mv) => (
                        <tr key={mv.id} className="hover:bg-slate-50">
                          <td className="px-3 py-2 font-mono text-slate-700">{mv.movementNumber}</td>
                          <td className="px-3 py-2">
                            <div className="font-semibold text-slate-900">{mv.materialName} ({mv.materialCode})</div>
                            <div className="font-mono text-[11px] text-teal-700 font-bold">Lot: {mv.lotNumber}</div>
                          </td>
                          <td className="px-3 py-2 text-slate-600">
                            <div>{mv.warehouseCode} ({mv.warehouseName})</div>
                            <div className="text-[11px] text-slate-400">
                              Loc: {mv.locationCode} {mv.zoneName ? `[${mv.zoneName}]` : ''}
                            </div>
                          </td>
                          <td className="px-3 py-2 text-slate-600">
                            <div>{mv.supplierName || 'Not recorded'}</div>
                            <div className="text-[11px] font-mono text-slate-500">
                              COA/Ref: {mv.supplierReference || 'Not recorded'}
                            </div>
                          </td>
                          <td className="px-3 py-2 text-right font-mono font-bold text-slate-900">
                            {Math.abs(mv.quantityDelta)} {mv.unit}
                          </td>
                          <td className="px-3 py-2 text-slate-700">{mv.performedBy}</td>
                          <td className="px-3 py-2 text-slate-500 font-mono text-[11px]">
                            {mv.occurredAt ? formatAppDateTime(mv.occurredAt) : 'Not recorded'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                  No material issue transactions recorded against this batch yet.
                </p>
              )}
            </div>
          </section>

          {/* SECTION D: Electronic Batch Record (eBR Execution Steps) */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <FileCheck2 className="w-5 h-5 text-amber-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section D • Electronic Batch Record (eBR Execution Steps)
              </h3>
            </div>
            {dossier.executionSteps && dossier.executionSteps.length > 0 ? (
              <div className="overflow-x-auto border border-slate-200 rounded-lg">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  <thead className="bg-slate-50 text-slate-600 font-semibold">
                    <tr>
                      <th className="px-3 py-2 text-center w-12">Step</th>
                      <th className="px-3 py-2 text-left">Operation Name & Instructions</th>
                      <th className="px-3 py-2 text-center">Status</th>
                      <th className="px-3 py-2 text-left">Actual Recorded Value</th>
                      <th className="px-3 py-2 text-left">Executed By</th>
                      <th className="px-3 py-2 text-left">Timestamps</th>
                      <th className="px-3 py-2 text-left">Observations</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-200 bg-white">
                    {dossier.executionSteps.map((step) => (
                      <tr key={step.id} className="hover:bg-slate-50">
                        <td className="px-3 py-2 text-center font-bold font-mono text-slate-700">
                          {step.stepNumber}
                        </td>
                        <td className="px-3 py-2">
                          <span className="font-semibold text-slate-900 block">{step.operationName}</span>
                          {step.instructions && (
                            <span className="text-[11px] text-slate-500 block">{step.instructions}</span>
                          )}
                        </td>
                        <td className="px-3 py-2 text-center">
                          <StatusBadge status={step.status} />
                        </td>
                        <td className="px-3 py-2 font-mono font-semibold text-slate-800">
                          {step.actualValue || <span className="text-slate-400 italic font-normal">Not recorded</span>}
                        </td>
                        <td className="px-3 py-2 text-slate-700">
                          {step.executedBy || <span className="text-slate-400 italic">Not recorded</span>}
                        </td>
                        <td className="px-3 py-2 text-[11px] font-mono text-slate-500">
                          <div>Started: {step.startedAt ? formatAppDateTime(step.startedAt) : '-'}</div>
                          <div>Completed: {step.completedAt ? formatAppDateTime(step.completedAt) : '-'}</div>
                        </td>
                        <td className="px-3 py-2 text-slate-600 italic">
                          {step.observations || '-'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                No eBR execution steps recorded or initialized for this batch.
              </p>
            )}
          </section>

          {/* SECTION E: Quality Control (QC Inspections) */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <Award className="w-5 h-5 text-teal-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section E • Quality Control (QC Inspection Records)
              </h3>
            </div>
            {dossier.qualityChecks && dossier.qualityChecks.length > 0 ? (
              <div className="overflow-x-auto border border-slate-200 rounded-lg">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  <thead className="bg-slate-50 text-slate-600 font-semibold">
                    <tr>
                      <th className="px-3 py-2 text-left">Test Name</th>
                      <th className="px-3 py-2 text-left">Specification / Limits</th>
                      <th className="px-3 py-2 text-left">Measured Result</th>
                      <th className="px-3 py-2 text-center">Result</th>
                      <th className="px-3 py-2 text-center">Required</th>
                      <th className="px-3 py-2 text-left">Inspector</th>
                      <th className="px-3 py-2 text-left">Inspected At</th>
                      <th className="px-3 py-2 text-left">Remarks & Corrections</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-200 bg-white">
                    {dossier.qualityChecks.map((qc) => (
                      <tr key={qc.id} className="hover:bg-slate-50">
                        <td className="px-3 py-2 font-semibold text-slate-900">{qc.testName}</td>
                        <td className="px-3 py-2 font-mono text-slate-600">{qc.specification}</td>
                        <td className="px-3 py-2 font-mono font-bold text-slate-900">
                          {qc.measuredValue ? `${qc.measuredValue} ${qc.unit || ''}` : <span className="text-slate-400 italic font-normal">Pending</span>}
                        </td>
                        <td className="px-3 py-2 text-center">
                          <span
                            className={`px-2 py-0.5 rounded text-[11px] font-bold ${
                              qc.result === 'PASS'
                                ? 'bg-emerald-100 text-emerald-800'
                                : qc.result === 'FAIL'
                                ? 'bg-rose-100 text-rose-800'
                                : 'bg-amber-100 text-amber-800'
                            }`}
                          >
                            {qc.result}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-center text-[11px] text-slate-500">
                          {qc.required ? 'Mandatory' : 'Optional'}
                        </td>
                        <td className="px-3 py-2 text-slate-700">{qc.inspectorName}</td>
                        <td className="px-3 py-2 text-slate-500 font-mono text-[11px]">
                          {qc.checkedAt ? formatAppDateTime(qc.checkedAt) : '-'}
                        </td>
                        <td className="px-3 py-2 text-slate-600">
                          {qc.remarks || '-'}
                          {qc.correctionReason && (
                            <span className="block text-[11px] text-amber-700 font-mono mt-0.5">
                              Corrected ({qc.correctionCount}x): {qc.correctionReason}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                No Quality Control (QC) inspection records linked to this batch.
              </p>
            )}
          </section>

          {/* SECTION F: Quality Assurance (QA Disposition) */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <ShieldCheck className="w-5 h-5 text-emerald-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section F • Quality Assurance (QA Disposition Reviews)
              </h3>
            </div>
            {dossier.qaReviews && dossier.qaReviews.length > 0 ? (
              <div className="space-y-3">
                {dossier.qaReviews.map((rev) => (
                  <div key={rev.id} className="p-4 bg-slate-50 border border-slate-200 rounded-lg text-xs space-y-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-slate-700">Decision Disposition:</span>
                        <StatusBadge status={rev.decisionStatus} />
                        {rev.previousStatus && (
                          <span className="text-[11px] text-slate-400">
                            (Previous: {rev.previousStatus})
                          </span>
                        )}
                      </div>
                      <div className="text-[11px] font-mono text-slate-500">
                        Reviewed: {rev.reviewedAt ? formatAppDateTime(rev.reviewedAt) : 'Not recorded'}
                      </div>
                    </div>
                    <div>
                      <span className="font-semibold text-slate-700">Reviewed By QA:</span>{' '}
                      <span className="text-slate-900 font-medium">{rev.reviewerName}</span>
                    </div>
                    <div>
                      <span className="font-semibold text-slate-700">Decision Rationale:</span>
                      <p className="mt-0.5 text-slate-800 bg-white p-2.5 rounded border border-slate-200 font-medium whitespace-pre-wrap">
                        {rev.rationale || 'No rationale recorded.'}
                      </p>
                    </div>
                    <div className="text-[11px] text-slate-500 flex gap-4 pt-1">
                      <span>QC Passed: <strong>{rev.passedChecksCount}</strong></span>
                      <span>QC Failed: <strong>{rev.failedChecksCount}</strong></span>
                      <span>QC Total: <strong>{rev.totalChecksCount}</strong></span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                No formal QA disposition records recorded. Batch status is currently PENDING QA review.
              </p>
            )}
          </section>

          {/* SECTION G: Deviations & CAPA */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <AlertTriangle className="w-5 h-5 text-rose-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section G • Deviations & Corrective Actions (CAPA)
              </h3>
            </div>
            {dossier.deviations && dossier.deviations.length > 0 ? (
              <div className="space-y-4">
                {dossier.deviations.map((dev) => (
                  <div key={dev.id} className="border border-slate-200 rounded-lg p-4 bg-slate-50/50 space-y-3 text-xs">
                    <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-2">
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-slate-900 font-mono text-sm">{dev.deviationNumber}</span>
                        <StatusBadge status={dev.status} />
                        <span className={`px-2 py-0.5 rounded text-[11px] font-bold ${
                          dev.severity === 'CRITICAL' ? 'bg-rose-100 text-rose-800' :
                          dev.severity === 'MAJOR' ? 'bg-amber-100 text-amber-800' :
                          'bg-slate-100 text-slate-700'
                        }`}>
                          {dev.severity}
                        </span>
                      </div>
                      <div className="text-[11px] text-slate-500 font-mono">
                        Reported: {dev.reportedAt ? formatAppDateTime(dev.reportedAt) : 'Not recorded'} by {dev.reportedBy}
                      </div>
                    </div>

                    <div>
                      <span className="font-bold text-slate-800 block text-sm">{dev.title}</span>
                      <p className="text-slate-600 mt-1 whitespace-pre-wrap">{dev.description}</p>
                    </div>

                    {dev.rootCauseAnalysis && (
                      <div className="bg-amber-50/80 border border-amber-200 rounded p-2.5 text-amber-900">
                        <span className="font-bold block text-[11px] uppercase tracking-wide">
                          Root Cause Analysis ({dev.rootCauseCategory || 'Uncategorized'}):
                        </span>
                        <p className="mt-0.5 text-xs">{dev.rootCauseAnalysis}</p>
                      </div>
                    )}

                    {/* Linked CAPAs */}
                    {dev.capaActions && dev.capaActions.length > 0 && (
                      <div className="mt-2 pt-2 border-t border-slate-200">
                        <span className="font-bold text-slate-700 block mb-1">
                          Linked CAPA Actions ({dev.capaActions.length}):
                        </span>
                        <div className="overflow-x-auto border border-slate-200 rounded bg-white">
                          <table className="min-w-full divide-y divide-slate-200 text-[11px]">
                            <thead className="bg-slate-50 text-slate-600 font-semibold">
                              <tr>
                                <th className="px-2 py-1.5 text-left">CAPA #</th>
                                <th className="px-2 py-1.5 text-left">Action Plan</th>
                                <th className="px-2 py-1.5 text-center">Type</th>
                                <th className="px-2 py-1.5 text-center">Status</th>
                                <th className="px-2 py-1.5 text-left">Owner</th>
                                <th className="px-2 py-1.5 text-left">Due Date</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                              {dev.capaActions.map((capa) => (
                                <tr key={capa.id}>
                                  <td className="px-2 py-1.5 font-mono font-semibold text-slate-900">{capa.capaNumber}</td>
                                  <td className="px-2 py-1.5 text-slate-700">{capa.actionPlan || capa.title}</td>
                                  <td className="px-2 py-1.5 text-center">{capa.capaType}</td>
                                  <td className="px-2 py-1.5 text-center font-bold">
                                    <StatusBadge status={capa.status} />
                                  </td>
                                  <td className="px-2 py-1.5 text-slate-600">{capa.ownerName}</td>
                                  <td className="px-2 py-1.5 font-mono text-slate-500">
                                    {capa.dueDate ? formatCalendarDate(capa.dueDate) : '-'}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-lg text-emerald-800 text-xs flex items-center gap-2">
                <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                <span>No deviations or non-conformances recorded for this batch (100% Compliant).</span>
              </div>
            )}
          </section>

          {/* SECTION H: Audit Trail History */}
          <section className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-2 mb-4 pb-2 border-b border-slate-200">
              <History className="w-5 h-5 text-indigo-600" />
              <h3 className="text-base font-bold text-slate-900">
                Section H • Chronological Audit History (Append-Only)
              </h3>
            </div>
            {dossier.auditHistory && dossier.auditHistory.length > 0 ? (
              <div className="overflow-x-auto border border-slate-200 rounded-lg">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  <thead className="bg-slate-50 text-slate-600 font-semibold">
                    <tr>
                      <th className="px-3 py-2 text-left">Timestamp</th>
                      <th className="px-3 py-2 text-left">Action</th>
                      <th className="px-3 py-2 text-left">Entity</th>
                      <th className="px-3 py-2 text-left">Reference</th>
                      <th className="px-3 py-2 text-left">Actor</th>
                      <th className="px-3 py-2 text-left">Reason / Context</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-200 bg-white">
                    {dossier.auditHistory.map((audit) => (
                      <tr key={audit.id} className="hover:bg-slate-50">
                        <td className="px-3 py-2 font-mono text-slate-500 text-[11px] whitespace-nowrap">
                          {audit.occurredAt ? formatAppDateTime(audit.occurredAt) : '-'}
                        </td>
                        <td className="px-3 py-2 font-bold font-mono text-teal-800">
                          {audit.action}
                        </td>
                        <td className="px-3 py-2 text-slate-600">
                          {audit.entityType}
                        </td>
                        <td className="px-3 py-2 font-mono text-slate-800">
                          {audit.entityReference || '-'}
                        </td>
                        <td className="px-3 py-2 font-semibold text-slate-900">
                          {audit.actorName}
                        </td>
                        <td className="px-3 py-2 text-slate-600 max-w-xs truncate" title={audit.reason}>
                          {audit.reason || '-'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic bg-slate-50 p-3 rounded">
                No audit log events linked directly to this batch.
              </p>
            )}
          </section>
        </div>
      )}

      {/* QR Preview & Download/Print Modal */}
      <BatchQrModal
        isOpen={isQrModalOpen}
        onClose={() => setIsQrModalOpen(false)}
        batchId={dossier?.batchId}
        batchNumber={dossier?.batchNumber}
        onOpenDossier={(bNum) => {
          setSearchInput(bNum);
          loadDossier(bNum);
        }}
      />
    </div>
  );
}
