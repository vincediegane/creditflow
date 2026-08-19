import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Navigate, useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import StorefrontIcon from '@mui/icons-material/Storefront';

import { errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';

interface FormValues {
  username: string;
  password: string;
}

export default function LoginPage() {
  const { login, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const { register, handleSubmit, formState } = useForm<FormValues>({
    defaultValues: { username: '', password: '' },
  });

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const submit = handleSubmit(async (values) => {
    setError(null);
    setLoading(true);
    try {
      await login(values.username.trim(), values.password);
      navigate('/', { replace: true });
    } catch (err) {
      setError(errorMessage(err, 'Identifiants incorrects'));
    } finally {
      setLoading(false);
    }
  });

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        background: 'linear-gradient(135deg, #21529c 0%, #16386b 100%)',
      }}
    >
      <Card sx={{ width: '100%', maxWidth: 420 }} elevation={8}>
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={1} alignItems="center" sx={{ mb: 3 }}>
            <Box
              sx={{
                width: 56,
                height: 56,
                borderRadius: 2,
                bgcolor: 'primary.main',
                color: 'common.white',
                display: 'grid',
                placeItems: 'center',
              }}
            >
              <StorefrontIcon fontSize="large" />
            </Box>
            <Typography variant="h5">CreditFlow</Typography>
            <Typography variant="body2" color="text.secondary" textAlign="center">
              Gestion des ventes à crédit de votre boutique
            </Typography>
          </Stack>

          <form onSubmit={submit}>
            <Stack spacing={2}>
              {error && <Alert severity="error">{error}</Alert>}

              <TextField
                label="Nom d'utilisateur"
                fullWidth
                autoFocus
                size="medium"
                {...register('username', { required: true })}
                error={Boolean(formState.errors.username)}
              />
              <TextField
                label="Mot de passe"
                type="password"
                fullWidth
                size="medium"
                {...register('password', { required: true })}
                error={Boolean(formState.errors.password)}
              />
              <Button type="submit" variant="contained" size="large" disabled={loading}>
                {loading ? 'Connexion…' : 'Se connecter'}
              </Button>
            </Stack>
          </form>

          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 3 }}>
            Compte par défaut : <strong>admin</strong> / <strong>admin123</strong>
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}
