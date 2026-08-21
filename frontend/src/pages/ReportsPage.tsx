import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import TableViewIcon from '@mui/icons-material/TableView';

import { errorMessage } from '../api/client';
import { reportsApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import { useShop } from '../context/ShopContext';
import type { ReportType } from '../types';
import { formatDateTime, formatMoney, formatNumber, today } from '../utils/format';

const REPORTS: { value: ReportType; label: string; needsDate: boolean }[] = [
  { value: 'DAILY_PAYMENTS', label: 'Paiements du jour', needsDate: true },
  { value: 'MONTHLY_PAYMENTS', label: 'Paiements du mois', needsDate: true },
  { value: 'LATE_CUSTOMERS', label: 'Clients en retard', needsDate: false },
  { value: 'OUTSTANDING', label: 'Créances restantes', needsDate: false },
];

export default function ReportsPage() {
  const { activeShopId, accessibleShops } = useShop();
  const [type, setType] = useState<ReportType>('DAILY_PAYMENTS');
  const [date, setDate] = useState(today());
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const current = REPORTS.find((report) => report.value === type)!;
  const params = current.needsDate ? { from: date } : {};

  const query = useQuery({
    queryKey: ['report', type, current.needsDate ? date : null],
    queryFn: () => reportsApi.get(type, params),
  });

  const download = async (format: 'pdf' | 'excel') => {
    setError(null);
    setDownloading(true);
    try {
      await reportsApi.download(type, format, params);
    } catch (err) {
      setError(errorMessage(err, "L'export n'a pas pu être généré"));
    } finally {
      setDownloading(false);
    }
  };

  const data = query.data;

  const renderCell = (value: string | number, columnType: string) => {
    if (columnType === 'MONEY' && typeof value === 'number') {
      return formatMoney(value);
    }
    if (columnType === 'NUMBER' && typeof value === 'number') {
      return formatNumber(value);
    }
    return String(value);
  };

  const isNumericColumn = (columnType?: string) =>
    columnType === 'MONEY' || columnType === 'NUMBER';

  return (
    <Box>
      <PageHeader
        title="Rapports"
        subtitle="Consultez et exportez vos rapports en PDF ou Excel"
        action={
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Button
              variant="outlined"
              startIcon={<TableViewIcon />}
              onClick={() => download('excel')}
              disabled={downloading}
            >
              Export Excel
            </Button>
            <Button
              variant="contained"
              startIcon={<PictureAsPdfIcon />}
              onClick={() => download('pdf')}
              disabled={downloading}
            >
              Export PDF
            </Button>
          </Stack>
        }
      />

      {accessibleShops.length > 1 && (
        <Chip
          sx={{ mb: 2 }}
          color={activeShopId ? 'primary' : 'secondary'}
          label={
            activeShopId
              ? `Boutique : ${accessibleShops.find((s) => s.id === activeShopId)?.name ?? ''}`
              : `Vue consolidée (${accessibleShops.length} boutiques)`
          }
        />
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Card variant="outlined" sx={{ mb: 2.5 }}>
        <CardContent>
          <Grid container spacing={2} alignItems="center">
            <Grid item xs={12} md={8}>
              <ToggleButtonGroup
                exclusive
                value={type}
                onChange={(_, value) => value && setType(value as ReportType)}
                sx={{ flexWrap: 'wrap' }}
              >
                {REPORTS.map((report) => (
                  <ToggleButton key={report.value} value={report.value} sx={{ px: 2 }}>
                    {report.label}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            </Grid>
            {current.needsDate && (
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  type="date"
                  label={type === 'DAILY_PAYMENTS' ? 'Jour' : 'Mois de référence'}
                  InputLabelProps={{ shrink: true }}
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                />
              </Grid>
            )}
          </Grid>
        </CardContent>
      </Card>

      {query.isFetching && <LinearProgress sx={{ mb: 2 }} />}

      {data && (
        <Card variant="outlined">
          <CardContent>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              justifyContent="space-between"
              alignItems={{ sm: 'center' }}
              spacing={1}
              sx={{ mb: 2 }}
            >
              <Box>
                <Typography variant="h6">{data.title}</Typography>
                <Typography variant="body2" color="text.secondary">
                  Période : {data.period} • généré le {formatDateTime(data.generatedAt)}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1} flexWrap="wrap">
                {data.totals.map((total) => (
                  <Chip
                    key={total.label}
                    color={total.type === 'MONEY' ? 'primary' : 'default'}
                    label={`${total.label} : ${renderCell(total.value, total.type)}`}
                  />
                ))}
              </Stack>
            </Stack>

            <TableContainer sx={{ maxHeight: 560 }}>
              <Table stickyHeader size="small">
                <TableHead>
                  <TableRow>
                    {data.columns.map((column) => (
                      <TableCell
                        key={column.label}
                        align={isNumericColumn(column.type) ? 'right' : 'left'}
                      >
                        {column.label}
                      </TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.rows.map((row, rowIndex) => (
                    // eslint-disable-next-line react/no-array-index-key
                    <TableRow key={rowIndex} hover>
                      {row.map((cell, cellIndex) => (
                        // eslint-disable-next-line react/no-array-index-key
                        <TableCell
                          key={cellIndex}
                          align={isNumericColumn(data.columns[cellIndex]?.type) ? 'right' : 'left'}
                        >
                          {renderCell(cell, data.columns[cellIndex]?.type ?? 'TEXT')}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                  {!data.rows.length && (
                    <EmptyRow
                      colSpan={data.columns.length}
                      message="Aucune donnée pour cette période"
                    />
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          </CardContent>
        </Card>
      )}
    </Box>
  );
}
