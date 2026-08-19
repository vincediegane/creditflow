import { TableCell, TableRow, Typography } from '@mui/material';

interface Props {
  colSpan: number;
  message?: string;
}

export default function EmptyRow({ colSpan, message = 'Aucune donnée à afficher' }: Props) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} align="center" sx={{ py: 5 }}>
        <Typography variant="body2" color="text.secondary">
          {message}
        </Typography>
      </TableCell>
    </TableRow>
  );
}
