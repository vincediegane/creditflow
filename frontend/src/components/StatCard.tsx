import type { ReactNode } from 'react';
import { Box, Card, CardActionArea, CardContent, Stack, Typography } from '@mui/material';

interface Props {
  label: string;
  value: string;
  hint?: string;
  icon: ReactNode;
  color?: 'primary' | 'success' | 'warning' | 'error' | 'secondary';
  onClick?: () => void;
}

export default function StatCard({ label, value, hint, icon, color = 'primary', onClick }: Props) {
  const content = (
    <CardContent sx={{ py: 2.5 }}>
      <Stack direction="row" spacing={2} alignItems="center">
        <Box
          sx={{
            width: 48,
            height: 48,
            borderRadius: 2,
            display: 'grid',
            placeItems: 'center',
            bgcolor: `${color}.main`,
            color: 'common.white',
            flexShrink: 0,
          }}
        >
          {icon}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" color="text.secondary" noWrap>
            {label}
          </Typography>
          <Typography variant="h5" sx={{ mt: 0.25 }}>
            {value}
          </Typography>
          {hint && (
            <Typography variant="caption" color="text.secondary">
              {hint}
            </Typography>
          )}
        </Box>
      </Stack>
    </CardContent>
  );

  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      {onClick ? <CardActionArea onClick={onClick}>{content}</CardActionArea> : content}
    </Card>
  );
}
