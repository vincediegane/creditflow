import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Card,
  CardContent,
  LinearProgress,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { searchApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import StatusChip from '../components/StatusChip';
import { formatDate, formatMoney } from '../utils/format';

export default function SearchPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const query = params.get('q') ?? '';

  const { data, isLoading } = useQuery({
    queryKey: ['global-search', query],
    queryFn: () => searchApi.global(query),
    enabled: query.length > 0,
  });

  return (
    <Box>
      <PageHeader
        title="Résultats de recherche"
        subtitle={query ? `Recherche : « ${query} »` : 'Saisissez un terme dans la barre du haut'}
      />

      {isLoading && <LinearProgress sx={{ mb: 2 }} />}

      {data && data.totalResults === 0 && (
        <Alert severity="info">Aucun résultat pour « {query} ».</Alert>
      )}

      {Boolean(data?.customers.length) && (
        <Card variant="outlined" sx={{ mb: 2.5 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Clients
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Nom</TableCell>
                  <TableCell>Téléphone</TableCell>
                  <TableCell>Profession</TableCell>
                  <TableCell>Adresse</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.customers.map((customer) => (
                  <TableRow
                    key={customer.id}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/clients/${customer.id}`)}
                  >
                    <TableCell sx={{ fontWeight: 600 }}>{customer.fullName}</TableCell>
                    <TableCell>{customer.phone}</TableCell>
                    <TableCell>{customer.profession ?? '—'}</TableCell>
                    <TableCell>{customer.address ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {Boolean(data?.sales.length) && (
        <Card variant="outlined" sx={{ mb: 2.5 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Contrats
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Référence</TableCell>
                  <TableCell>Client</TableCell>
                  <TableCell>Produit</TableCell>
                  <TableCell align="right">Reste</TableCell>
                  <TableCell>Fin prévue</TableCell>
                  <TableCell>Statut</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.sales.map((sale) => (
                  <TableRow
                    key={sale.id}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/ventes/${sale.id}`)}
                  >
                    <TableCell sx={{ fontWeight: 600 }}>{sale.reference}</TableCell>
                    <TableCell>{sale.customerName}</TableCell>
                    <TableCell>{sale.productName}</TableCell>
                    <TableCell align="right">{formatMoney(sale.remainingAmount)}</TableCell>
                    <TableCell>{formatDate(sale.endDate)}</TableCell>
                    <TableCell>
                      <StatusChip status={sale.status} kind="sale" />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {Boolean(data?.products.length) && (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Produits
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Produit</TableCell>
                  <TableCell>Catégorie</TableCell>
                  <TableCell align="right">Prix à crédit</TableCell>
                  <TableCell align="right">Stock</TableCell>
                  <TableCell>Statut</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.products.map((product) => (
                  <TableRow key={product.id} hover>
                    <TableCell sx={{ fontWeight: 600 }}>{product.name}</TableCell>
                    <TableCell>{product.category}</TableCell>
                    <TableCell align="right">{formatMoney(product.creditPrice)}</TableCell>
                    <TableCell align="right">{product.stock}</TableCell>
                    <TableCell>
                      <StatusChip status={product.status} kind="product" />
                    </TableCell>
                  </TableRow>
                ))}
                {!data?.products.length && <EmptyRow colSpan={5} />}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </Box>
  );
}
