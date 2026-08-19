import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Card,
  CardContent,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { auditLogApi } from '../api/endpoints';
import type { AuditEntityType } from '../types';
import { AUDIT_ACTION_LABELS, formatDateTime } from '../utils/format';
import EmptyRow from './EmptyRow';

interface AuditHistoryCardProps {
  entityType: AuditEntityType;
  entityId: number;
  actionLabels?: Record<string, string>;
}

export default function AuditHistoryCard({
  entityType,
  entityId,
  actionLabels = AUDIT_ACTION_LABELS,
}: AuditHistoryCardProps) {
  const { data } = useQuery({
    queryKey: ['audit-log', entityType, entityId],
    queryFn: () => auditLogApi.list(entityType, entityId),
    enabled: Number.isFinite(entityId),
  });

  const entries = data ?? [];

  return (
    <Card variant="outlined" sx={{ mt: 2.5 }}>
      <CardContent>
        <Typography variant="h6" gutterBottom>
          Historique
        </Typography>
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Action</TableCell>
                <TableCell>Auteur</TableCell>
                <TableCell>Détails</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {entries.map((entry) => (
                <TableRow key={entry.id} hover>
                  <TableCell>{formatDateTime(entry.createdAt)}</TableCell>
                  <TableCell>{actionLabels[entry.action] ?? entry.action}</TableCell>
                  <TableCell>{entry.actor ?? '—'}</TableCell>
                  <TableCell>{entry.details ?? '—'}</TableCell>
                </TableRow>
              ))}
              {!entries.length && <EmptyRow colSpan={4} message="Aucun événement enregistré" />}
            </TableBody>
          </Table>
        </Box>
      </CardContent>
    </Card>
  );
}
