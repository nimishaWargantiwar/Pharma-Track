import React, { useEffect, useState } from 'react';
import {
  Download,
  ExternalLink,
  Printer,
  QrCode,
  RefreshCw,
  X,
} from 'lucide-react';
import ModalDialog from './ui/ModalDialog.jsx';
import InlineAlert from './ui/InlineAlert.jsx';
import { fetchBatchQrById, fetchBatchQrByIdentifier } from '../services/batchTraceabilityApi.js';

export default function BatchQrModal({
  isOpen,
  onClose,
  batch,
  batchId,
  batchNumber,
  onOpenDossier,
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [qrData, setQrData] = useState(null);

  useEffect(() => {
    if (!isOpen) {
      setQrData(null);
      setError(null);
      return;
    }

    const effectiveId = batch?.id || batchId;
    const effectiveNumber = batch?.batchNumber || batchNumber;

    if (!effectiveId && !effectiveNumber) {
      setError('No batch specified for QR code generation.');
      return;
    }

    let isMounted = true;
    setLoading(true);
    setError(null);

    const loadQr = async () => {
      try {
        let result;
        if (effectiveId) {
          result = await fetchBatchQrById(effectiveId);
        } else {
          result = await fetchBatchQrByIdentifier(effectiveNumber);
        }
        if (isMounted) {
          setQrData(result);
        }
      } catch (err) {
        if (isMounted) {
          setError(err.message || 'Failed to generate batch QR code.');
        }
      } finally {
        if (isMounted) {
          setLoading(false);
        }
      }
    };

    loadQr();

    return () => {
      isMounted = false;
    };
  }, [isOpen, batch, batchId, batchNumber]);

  const handlePrint = () => {
    if (!qrData?.qrImageBase64) return;
    const printWindow = window.open('', '_blank', 'width=600,height=700');
    if (!printWindow) {
      alert('Please allow popups to print the batch QR code.');
      return;
    }
    const htmlContent = `
      <!DOCTYPE html>
      <html>
        <head>
          <title>Batch QR - ${qrData.batchNumber}</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; text-align: center; padding: 24px; color: #1e293b; }
            .card { border: 2px solid #0f172a; border-radius: 8px; padding: 20px; max-width: 400px; margin: 0 auto; }
            .header { font-size: 14px; text-transform: uppercase; letter-spacing: 0.1em; color: #64748b; margin-bottom: 6px; }
            .batch { font-size: 22px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
            .info { font-size: 13px; color: #475569; margin-bottom: 16px; }
            .qr-img { width: 220px; height: 220px; margin: 0 auto 16px; display: block; }
            .code { font-family: monospace; font-size: 12px; background: #f1f5f9; padding: 6px 10px; border-radius: 4px; display: inline-block; word-break: break-all; }
            .footer { font-size: 10px; color: #94a3b8; margin-top: 16px; border-top: 1px dashed #cbd5e1; padding-top: 8px; }
            @media print {
              body { padding: 0; }
              .card { border: 1px solid #000; box-shadow: none; }
            }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="header">PharmaTrack MES & eBR</div>
            <div class="batch">${qrData.batchNumber}</div>
            <div class="info">
              <div><strong>Product:</strong> ${qrData.productName || 'N/A'}</div>
              <div><strong>Order:</strong> ${qrData.orderNumber || 'N/A'}</div>
            </div>
            <img class="qr-img" src="${qrData.qrImageBase64}" alt="Batch QR Code" />
            <div><span class="code">${qrData.qrCode}</span></div>
            <div class="footer">Secure Batch Traceability • Generated: ${new Date().toLocaleString()}</div>
          </div>
          <script>
            window.onload = function() {
              window.focus();
              window.print();
              setTimeout(function() { window.close(); }, 500);
            };
          </script>
        </body>
      </html>
    `;
    printWindow.document.open();
    printWindow.document.write(htmlContent);
    printWindow.document.close();
  };

  const handleDownload = () => {
    if (!qrData?.qrImageBase64) return;
    const link = document.createElement('a');
    link.href = qrData.qrImageBase64;
    link.download = `Batch-${qrData.batchNumber}-QR.png`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleOpenDossier = () => {
    const targetBatch = qrData?.batchNumber || batch?.batchNumber || batchNumber;
    if (onOpenDossier) {
      onOpenDossier(targetBatch);
    } else {
      window.location.hash = `#/traceability?batchNumber=${encodeURIComponent(targetBatch)}`;
    }
    onClose();
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      title="Manufacturing Batch QR Identity"
      maxWidth="max-w-md"
    >
      <div className="space-y-4">
        {error && <InlineAlert variant="error" message={error} />}

        {loading && (
          <div className="py-12 text-center text-slate-500">
            <RefreshCw className="w-8 h-8 mx-auto animate-spin text-teal-600 mb-2" />
            <p className="text-sm font-medium">Resolving batch QR code...</p>
          </div>
        )}

        {!loading && qrData && (
          <div className="text-center space-y-4">
            <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
              <span className="text-xs uppercase tracking-wide text-slate-500 font-semibold block">
                Batch Identifier
              </span>
              <span className="text-lg font-bold text-slate-900 block font-mono">
                {qrData.batchNumber}
              </span>
              <div className="text-xs text-slate-600 mt-1 flex justify-center gap-4">
                <span>
                  <strong>Order:</strong> {qrData.orderNumber || 'N/A'}
                </span>
                <span>
                  <strong>Product:</strong> {qrData.productName || 'N/A'}
                </span>
              </div>
            </div>

            {qrData.qrImageBase64 ? (
              <div className="flex justify-center p-4 bg-white border-2 border-slate-200 rounded-xl shadow-inner max-w-[260px] mx-auto">
                <img
                  src={qrData.qrImageBase64}
                  alt={`QR Code for batch ${qrData.batchNumber}`}
                  className="w-56 h-56 object-contain"
                />
              </div>
            ) : (
              <div className="py-8 text-slate-400">
                <QrCode className="w-16 h-16 mx-auto mb-2 text-slate-300" />
                <p className="text-sm">QR Code not available</p>
              </div>
            )}

            <div className="text-xs text-slate-500 space-y-1">
              <p className="font-mono bg-slate-100 px-2 py-1 rounded inline-block text-slate-700">
                {qrData.qrCode}
              </p>
              <p className="text-[11px] text-slate-400">
                Scannable with any standard barcode/QR reader or camera scanner
              </p>
            </div>

            <div className="pt-2 border-t border-slate-200 flex flex-wrap gap-2 justify-center">
              <button
                type="button"
                onClick={handlePrint}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white border border-slate-300 rounded hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-teal-500"
              >
                <Printer className="w-3.5 h-3.5" />
                Print QR
              </button>
              <button
                type="button"
                onClick={handleDownload}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white border border-slate-300 rounded hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-teal-500"
              >
                <Download className="w-3.5 h-3.5" />
                Download PNG
              </button>
              <button
                type="button"
                onClick={handleOpenDossier}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-teal-600 rounded hover:bg-teal-700 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-teal-500"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                Open Traceability Dossier
              </button>
            </div>
          </div>
        )}
      </div>
    </ModalDialog>
  );
}

