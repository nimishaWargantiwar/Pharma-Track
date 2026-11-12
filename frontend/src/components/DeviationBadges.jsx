import React from 'react';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ClipboardCheck,
  Clock,
  Search,
  Wrench,
  XCircle,
} from 'lucide-react';
import { StatusBadge } from './ui/index.js';

export function getDeviationStatusBadge(status) {
  switch (status) {
    case 'OPEN':
      return <StatusBadge status="OPEN" domain="deviation" icon={AlertCircle} />;
    case 'UNDER_INVESTIGATION':
      return <StatusBadge status="UNDER_INVESTIGATION" domain="deviation" icon={Search} />;
    case 'ROOT_CAUSE_IDENTIFIED':
      return (
        <StatusBadge
          status="ROOT_CAUSE_IDENTIFIED"
          domain="deviation"
          icon={AlertTriangle}
        />
      );
    case 'CAPA_IN_PROGRESS':
      return <StatusBadge status="CAPA_IN_PROGRESS" domain="deviation" icon={Wrench} />;
    case 'PENDING_VERIFICATION':
      return (
        <StatusBadge
          status="PENDING_VERIFICATION"
          domain="deviation"
          icon={ClipboardCheck}
        />
      );
    case 'CLOSED':
      return <StatusBadge status="CLOSED" domain="deviation" icon={CheckCircle2} />;
    default:
      return (
        <StatusBadge
          status={status || 'UNKNOWN'}
          label={status || 'UNKNOWN'}
          domain="deviation"
          icon={Clock}
        />
      );
  }
}

export function getDeviationSeverityBadge(severity) {
  switch (severity) {
    case 'CRITICAL':
      return (
        <StatusBadge
          status="CRITICAL"
          domain="deviation"
          variant="tag"
          icon={AlertTriangle}
          className="font-bold"
        />
      );
    case 'MAJOR':
      return (
        <StatusBadge
          status="MAJOR"
          domain="deviation"
          variant="tag"
          icon={AlertCircle}
        />
      );
    case 'MINOR':
    default:
      return (
        <StatusBadge
          status={severity || 'MINOR'}
          label={severity || 'MINOR'}
          domain="deviation"
          variant="tag"
        />
      );
  }
}

export function getDeviationPriorityBadge(priority) {
  switch (priority) {
    case 'HIGH':
      return <StatusBadge status="HIGH" domain="deviation" variant="tag" />;
    case 'MEDIUM':
      return <StatusBadge status="MEDIUM" domain="deviation" variant="tag" />;
    case 'LOW':
    default:
      return (
        <StatusBadge
          status={priority || 'LOW'}
          label={priority || 'LOW'}
          domain="deviation"
          variant="tag"
        />
      );
  }
}

export function getCapaStatusBadge(status, overdue = false) {
  const isOverdueActive =
    Boolean(overdue) && (status === 'OPEN' || status === 'IN_PROGRESS');

  let baseBadge;
  switch (status) {
    case 'COMPLETED':
      baseBadge = <StatusBadge status="COMPLETED" domain="capa" icon={CheckCircle2} />;
      break;
    case 'IN_PROGRESS':
      baseBadge = <StatusBadge status="IN_PROGRESS" domain="capa" icon={Wrench} />;
      break;
    case 'CANCELLED':
      baseBadge = <StatusBadge status="CANCELLED" domain="capa" icon={XCircle} />;
      break;
    case 'OPEN':
    default:
      baseBadge = (
        <StatusBadge
          status={status || 'OPEN'}
          label={status || 'OPEN'}
          domain="capa"
          icon={Clock}
        />
      );
  }

  if (!isOverdueActive) {
    return baseBadge;
  }

  return (
    <span className="inline-flex items-center gap-1.5 flex-wrap">
      {baseBadge}
      <StatusBadge status="OVERDUE" domain="capa" size="xs" className="font-bold" />
    </span>
  );
}

export function getCapaTypeBadge(capaType) {
  if (capaType === 'PREVENTIVE') {
    return <StatusBadge status="PREVENTIVE" domain="capa" variant="tag" size="xs" />;
  }
  return <StatusBadge status="CORRECTIVE" domain="capa" variant="tag" size="xs" />;
}
